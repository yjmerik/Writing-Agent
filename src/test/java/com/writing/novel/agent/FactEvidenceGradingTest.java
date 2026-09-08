package com.writing.novel.agent;

import com.writing.novel.domain.Chapter;
import com.writing.novel.domain.StoryFact;
import com.writing.novel.llm.LlmGateway;
import com.writing.novel.llm.LlmResponse;
import com.writing.novel.persistence.sqlite.ConnectionProvider;
import com.writing.novel.persistence.sqlite.SqliteSchemaInitializer;
import com.writing.novel.persistence.sqlite.SqliteStoryCanon;
import com.writing.novel.persistence.sqlite.StoryFactRepository;
import com.writing.novel.telemetry.EventLog;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * p32-06（体验报告 #4/P1）：机器收割的世界铁律必须待核。
 * 复现：第21章角色关于缆绳的说法被 LLM 分类为 WORLD_RULE 后直通 ACTIVE 真典，
 * 经 RelevanceGateway 恒注入约束后续创作——一句人物台词成了"世界铁律"。
 * 修复后：机器收割的 WORLD_RULE 落库为 UNVERIFIED（importance 降级、发事件、
 * 展示标注【待核】），且不得 supersede 已确认（CONFIRMED）铁律；
 * 人工补丁与播种事实保持 CONFIRMED。
 */
class FactEvidenceGradingTest {

    @TempDir
    Path tempDir;
    private StoryFactRepository factRepo;
    private final String pid = "proj-grade";

    private static final List<Map<String, Object>> EVENTS = new CopyOnWriteArrayList<>();
    private static final EventLog.Listener LISTENER = record -> {
        if ("FACT_UNVERIFIED_RULE".equals(record.get("event"))) EVENTS.add(record);
    };

    @BeforeAll
    static void redirectEventLog() {
        new File("./data").mkdirs();
        System.setProperty(EventLog.PATH_PROPERTY, "./data/test-fact-grading-events.jsonl");
        EventLog.subscribe(LISTENER);
    }

    @AfterAll
    static void restoreEventLog() {
        EventLog.unsubscribe(LISTENER);
        System.clearProperty(EventLog.PATH_PROPERTY);
    }

    @BeforeEach
    void setUp() {
        EVENTS.clear();
        ConnectionProvider cp = new ConnectionProvider(tempDir.resolve("grading.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        factRepo = new StoryFactRepository(cp);
    }

    @AfterEach
    void clearEvents() {
        EVENTS.clear();
    }

    private Chapter chapter(String content) {
        Chapter c = new Chapter(pid, 21, "第二十一章");
        c.setContent(content);
        return c;
    }

    private FactHarvesterAgent agentReturning(String json) {
        LlmGateway gw = mock(LlmGateway.class);
        when(gw.complete(any())).thenReturn(new LlmResponse(json, 100, 50));
        return new FactHarvesterAgent(gw, new SqliteStoryCanon(factRepo), TestPromptHelper.create());
    }

    @Test
    void 机器收割的世界铁律落库为待核并发出事件() {
        FactHarvesterAgent agent = agentReturning(
                "[{\"type\":\"WORLD_RULE\",\"subject\":\"缆绳\",\"assertion\":\"旧缆绳承受不住第三次拖拽\"}]");
        var result = agent.harvest(pid, chapter("「这根缆绳撑不过第三次。」他说。"));

        assertEquals(1, result.added());
        StoryFact stored = factRepo.findActive(pid).stream()
                .filter(f -> f.getFactType() == StoryFact.FactType.WORLD_RULE)
                .findFirst().orElseThrow();
        assertEquals(StoryFact.EvidenceGrade.UNVERIFIED, stored.getEvidenceGrade(),
                "机器收割的 WORLD_RULE 必须待核，不得直通确认铁律");
        assertTrue(stored.getImportance() <= 2,
                "待核铁律 importance 必须降到恒注入门槛(>=4)之下，实际 " + stored.getImportance());
        assertTrue(EVENTS.stream().anyMatch(e -> pid.equals(e.get("projectId"))
                        && "缆绳".equals(e.get("subject"))),
                "应发出 FACT_UNVERIFIED_RULE 事件，实际: " + EVENTS);
    }

    @Test
    void 待核断言不得取代已确认铁律() {
        StoryFact confirmed = new StoryFact(pid, StoryFact.FactType.WORLD_RULE,
                "缆绳", "码头缆绳由港务局统一编号管理", 3);
        confirmed.setEvidenceGrade(StoryFact.EvidenceGrade.CONFIRMED);
        factRepo.save(confirmed);

        FactHarvesterAgent agent = agentReturning(
                "[{\"type\":\"WORLD_RULE\",\"subject\":\"缆绳\",\"assertion\":\"所有缆绳都在午夜被悄悄更换\"}]");
        agent.harvest(pid, chapter("「缆绳早就换过了。」她压低声音。"));

        List<StoryFact> active = factRepo.findActiveBySubject(pid, "缆绳").stream()
                .filter(f -> f.getFactType() == StoryFact.FactType.WORLD_RULE).toList();
        assertEquals(1, active.size(), "已确认铁律不能被待核断言取代，也不能并列两条 ACTIVE");
        assertEquals("码头缆绳由港务局统一编号管理", active.get(0).getAssertion());
        assertEquals(StoryFact.EvidenceGrade.CONFIRMED, active.get(0).getEvidenceGrade());
    }

    @Test
    void 非世界铁律类型与人工补丁不受降级影响() {
        FactHarvesterAgent agent = agentReturning(
                "[{\"type\":\"CHARACTER_STATE\",\"subject\":\"林一舟\",\"assertion\":\"开始私下调查旧案\"}]");
        agent.harvest(pid, chapter("林一舟翻出卷宗。"));
        StoryFact state = factRepo.findActive(pid).stream()
                .filter(f -> f.getFactType() == StoryFact.FactType.CHARACTER_STATE)
                .findFirst().orElseThrow();
        assertEquals(StoryFact.EvidenceGrade.CONFIRMED, state.getEvidenceGrade(),
                "状态类事实按既有语义入库，不扩大降级范围");
        assertEquals(3, state.getImportance());

        var canon = new SqliteStoryCanon(factRepo);
        StoryFact patched = canon.applyHumanPatch(pid, StoryFact.FactType.WORLD_RULE,
                "电台", "凌晨节目不得追问事故细节（台规）", 5, null);
        assertEquals(StoryFact.EvidenceGrade.CONFIRMED, patched.getEvidenceGrade(),
                "人工补丁始终为确认级");
        assertEquals(5, patched.getImportance());
    }
}
