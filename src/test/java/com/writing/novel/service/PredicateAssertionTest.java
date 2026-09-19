package com.writing.novel.service;

import com.writing.novel.agent.FactHarvesterAgent;
import com.writing.novel.domain.*;
import com.writing.novel.llm.LlmGateway;
import com.writing.novel.llm.LlmRequest;
import com.writing.novel.llm.LlmResponse;
import com.writing.novel.llm.prompt.PromptTemplateService;
import com.writing.novel.persistence.sqlite.*;
import com.writing.novel.spi.StoryCanon;
import com.writing.novel.telemetry.EventLog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * NCS-008（方案 §09/R02/F03）：谓词化断言与否定冲突。
 * 事实带 predicate/object/polarity/validFrom/To；不同谓词并存（R02：不识字与在城门共存）；
 * 同谓词相反极性 → 冲突记录（事件+问题）而非静默覆盖；旧数据（无谓词）走原路径。
 */
class PredicateAssertionTest {

    @TempDir
    Path tempDir;

    private SqliteNovelRepository repo;
    private StoryFactRepository factRepo;
    private SqliteStoryCanon canon;
    private String projectId;
    private EventLog.Listener listener;
    private final List<String> events = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        var cp = new ConnectionProvider(tempDir.resolve("predicate.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        repo = new SqliteNovelRepository(cp);
        factRepo = new StoryFactRepository(cp);
        canon = new SqliteStoryCanon(factRepo, repo);
        Project project = new Project("谓词项目", "构思", "武侠");
        repo.saveProject(project);
        projectId = project.getId();
        listener = e -> events.add(String.valueOf(e.get("event")));
        EventLog.subscribe(listener);
    }

    @AfterEach
    void tearDown() {
        EventLog.unsubscribe(listener);
    }

    private StoryFact fact(String subject, String predicate, String objectValue, String polarity, String assertion, int chapter) {
        StoryFact f = new StoryFact(projectId, StoryFact.FactType.CHARACTER_STATE, subject, assertion, chapter);
        f.setPredicate(predicate);
        f.setObjectValue(objectValue);
        f.setPolarity(polarity);
        return f;
    }

    private List<StoryFact> active(String subject) {
        return canon.recallBySubject(projectId, subject);
    }

    @Test
    void R02_不同谓词并存_不互相覆盖() {
        canon.harvest(fact("沈昭", "ability.read", "false", "negative", "沈昭不识字", 8));
        canon.harvest(fact("沈昭", "location", "城门", "positive", "沈昭位于城门", 8));

        List<StoryFact> facts = active("沈昭");
        assertEquals(2, facts.size(), "不同谓词的两个属性必须并存，不得互相取代");
        assertTrue(facts.stream().anyMatch(f -> "ability.read".equals(f.getPredicate())
                && "negative".equals(f.getPolarity())));
        assertTrue(facts.stream().anyMatch(f -> "location".equals(f.getPredicate())
                && "城门".equals(f.getObjectValue())));
    }

    @Test
    void 同谓词相反极性_产出冲突记录_不静默覆盖() {
        canon.harvest(fact("沈昭", "ability.read", "false", "negative", "沈昭不识字", 8));
        var result = canon.harvest(fact("沈昭", "ability.read", "true", "positive", "沈昭识字", 12));

        assertEquals(StoryCanon.Outcome.CONFLICT, result.outcome(),
                "同谓词相反极性必须产出冲突，不能当同义确认也不能静默覆盖");
        assertEquals(2, active("沈昭").size(), "冲突双方都保留 ACTIVE 待裁决");
        assertTrue(events.contains("FACT_CONFLICT"), "必须留 FACT_CONFLICT 事件");
        var issues = repo.findUnresolvedIssues(projectId);
        assertEquals(1, issues.size(), "冲突必须形成待处理问题");
        assertEquals(ContinuityIssue.Severity.HIGH, issues.get(0).getSeverity());
        assertTrue(issues.get(0).getDescription().contains("ability.read"));
    }

    @Test
    void 同谓词同极性_状态变化正常取代() {
        canon.harvest(fact("沈昭", "location", "城门", "positive", "沈昭位于城门", 8));
        var result = canon.harvest(fact("沈昭", "location", "码头", "positive", "沈昭位于码头", 10));

        assertEquals(StoryCanon.Outcome.SUPERSEDED, result.outcome(),
                "同谓词同极性的取值变化是正常状态推进");
        assertEquals(1, active("沈昭").size());
        assertEquals("码头", active("沈昭").get(0).getObjectValue());
    }

    @Test
    void 同谓词同极性同义复述_按确认处理() {
        canon.harvest(fact("沈昭", "location", "城门", "positive", "沈昭位于城门", 8));
        var result = canon.harvest(fact("沈昭", "location", "城门", "positive", "沈昭位于城门（复述）", 9));
        assertEquals(StoryCanon.Outcome.VALIDATED, result.outcome(),
                "同谓词同极性同取值的复述按确认处理");
        assertEquals(1, active("沈昭").size());
    }

    @Test
    void 旧数据无谓词_保持原有语义() {
        StoryFact legacy = new StoryFact(projectId, StoryFact.FactType.CHARACTER_STATE, "江砚", "江砚持有密信", 5);
        canon.harvest(legacy);
        // 新谓词化事实不与无谓词旧事实比对（各自独立）
        canon.harvest(fact("江砚", "location", "渡口", "positive", "江砚位于渡口", 8));
        assertEquals(2, active("江砚").size(), "无谓词旧事实与谓词化新事实互不干扰");

        // 无谓词 vs 无谓词：沿用旧 containment/supersede 路径
        StoryFact legacy2 = new StoryFact(projectId, StoryFact.FactType.CHARACTER_STATE, "江砚", "江砚持有残信", 9);
        var result = canon.harvest(legacy2);
        assertEquals(StoryCanon.Outcome.SUPERSEDED, result.outcome());
    }

    @Test
    void 收割器解析新字段并落库() {
        LlmGateway gateway = new LlmGateway() {
            @Override public LlmResponse complete(LlmRequest req) {
                return new LlmResponse("""
                        [{"type":"CHARACTER_STATE","subject":"沈昭","assertion":"沈昭不识字",
                          "predicate":"ability.read","object":"false","polarity":"negative"},
                         {"type":"OBJECT_STATE","subject":"密信","assertion":"密信被焚毁",
                          "predicate":"状态","object":"已焚毁","polarity":"negative"}]
                        """, 100, 200);
            }
            @Override public void streamComplete(LlmRequest req, com.writing.novel.llm.client.StreamHandler h) {}
        };
        PromptTemplateService promptSvc = com.writing.novel.agent.TestPromptHelper.create();
        var harvester = new FactHarvesterAgent(gateway, canon, promptSvc);
        var result = harvester.harvest(projectId, chapter(8));

        assertEquals(2, result.added());
        var shen = canon.recallBySubject(projectId, "沈昭");
        assertEquals(1, shen.size());
        assertEquals("ability.read", shen.get(0).getPredicate());
        assertEquals("negative", shen.get(0).getPolarity());
        var letter = canon.recallBySubject(projectId, "密信");
        assertEquals("已焚毁", letter.get(0).getObjectValue());
        assertEquals(8, shen.get(0).getValidFromChapter());
    }

    @Test
    void 旧格式JSON仍可解析_谓词字段缺省() {
        LlmGateway gateway = new LlmGateway() {
            @Override public LlmResponse complete(LlmRequest req) {
                return new LlmResponse(
                        "[{\"type\":\"TIMELINE_EVENT\",\"subject\":\"甲\",\"assertion\":\"甲入城\"}]", 10, 20);
            }
            @Override public void streamComplete(LlmRequest req, com.writing.novel.llm.client.StreamHandler h) {}
        };
        var harvester = new FactHarvesterAgent(gateway, canon, com.writing.novel.agent.TestPromptHelper.create());
        var result = harvester.harvest(projectId, chapter(3));
        assertEquals(1, result.added());
        var f = canon.recallBySubject(projectId, "甲").get(0);
        assertNull(f.getPredicate(), "旧格式无谓词字段时保持 null（兼容）");
        assertEquals(3, f.getValidFromChapter(), "validFrom 缺省为建立章");
    }

    private Chapter chapter(int index) {
        Chapter ch = new Chapter(projectId, index, "第" + index + "章");
        ch.setContent("渡口的雾气还没有散尽的正文内容。".repeat(40));
        ch.setStatus(Chapter.Status.DONE);
        return ch;
    }
}
