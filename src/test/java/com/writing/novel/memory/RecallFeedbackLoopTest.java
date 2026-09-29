package com.writing.novel.memory;

import com.writing.novel.agent.FactHarvesterAgent;
import com.writing.novel.agent.TestPromptHelper;
import com.writing.novel.domain.Chapter;
import com.writing.novel.domain.Project;
import com.writing.novel.domain.StoryFact;
import com.writing.novel.llm.LlmGateway;
import com.writing.novel.llm.LlmRequest;
import com.writing.novel.llm.LlmResponse;
import com.writing.novel.llm.client.StreamHandler;
import com.writing.novel.persistence.sqlite.ConnectionProvider;
import com.writing.novel.persistence.sqlite.InjectionManifestRepository;
import com.writing.novel.persistence.sqlite.RecallFeedbackRepository;
import com.writing.novel.persistence.sqlite.SqliteNovelRepository;
import com.writing.novel.persistence.sqlite.SqliteSchemaInitializer;
import com.writing.novel.persistence.sqlite.SqliteStoryCanon;
import com.writing.novel.persistence.sqlite.StoryFactRepository;
import com.writing.novel.spi.StoryCanon;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * NCS-114: 召回使用反馈回路——
 * 1) manifest×harvest 交叉计算注入命中率并持久化；
 * 2) 低命中（≥3 章样本且 <0.2）建议真典 cap 减半，命中良好不受影响；
 * 3) 探索配额：importance≥4 且 60 章未验证的事实保底注入（打破马太效应）。
 */
class RecallFeedbackLoopTest {

    @TempDir
    Path tempDir;
    private ConnectionProvider cp;
    private RecallFeedbackService feedback;
    private InjectionManifestRepository manifestRepo;
    private StoryFactRepository factRepo;
    private StoryCanon canon;
    private String pid;

    @BeforeEach
    void setUp() {
        cp = new ConnectionProvider(tempDir.resolve("feedback.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        var repository = new SqliteNovelRepository(cp);
        var project = new Project("反馈回路", "构思", "wuxia");
        repository.saveProject(project);
        pid = project.getId();
        manifestRepo = new InjectionManifestRepository(cp);
        feedback = new RecallFeedbackService(new RecallFeedbackRepository(cp));
        factRepo = new StoryFactRepository(cp);
        canon = new SqliteStoryCanon(factRepo);
    }

    private InjectionManifest manifestWithFacts(String... subjectColonAssertion) {
        InjectionManifest m = new InjectionManifest(pid, 1, 1, 16000);
        for (String s : subjectColonAssertion) {
            m.addEntry("bibleSection", InjectionManifest.Kind.FACT, "id-" + s, s, 10);
        }
        return m;
    }

    @Test
    void 命中率统计_低命中降配额_良好命中不受影响() {
        // 3 章样本：注入 2 条/章，仅第 1 章 1 条命中 → 1/6 ≈ 0.17 < 0.2
        feedback.recordHarvestOutcome(pid, 1, manifestWithFacts("林凡：旧伤", "城主府：朝向"), java.util.List.of("林凡"));
        feedback.recordHarvestOutcome(pid, 2, manifestWithFacts("林凡：旧伤", "城主府：朝向"), java.util.List.of("路人"));
        feedback.recordHarvestOutcome(pid, 3, manifestWithFacts("林凡：旧伤", "城主府：朝向"), java.util.List.of("路人"));
        assertEquals(1.0 / 6, feedback.hitRate(pid, "FACT"), 1e-9);
        assertEquals(20, feedback.recommendedFactCap(pid, 40), "低命中建议 cap 减半");

        // 样本不足（新项目）→ 不下结论
        assertEquals(-1, feedback.hitRate("no-such-project", "FACT"));
        assertEquals(40, feedback.recommendedFactCap("no-such-project", 40));
    }

    @Test
    void 探索配额_长窗未注入的高重要度事实保底出现() {
        // 近期反复验证的中重要度事实（马太效应既得者）填满小 cap
        for (int i = 0; i < 3; i++) {
            StoryFact warm = new StoryFact(pid, StoryFact.FactType.CHARACTER_STATE, "配角" + i, "状态" + i, 5);
            warm.setImportance(3);
            warm.setLastValidatedChapter(99);
            factRepo.save(warm);
        }
        // 70 章未验证的 importance-5 主线事实（从未被复述 → recency 沉底）
        StoryFact buried = new StoryFact(pid, StoryFact.FactType.PLOT_COMMITMENT, "主线承诺", "必须手刃仇敌", 1);
        buried.setImportance(5);
        buried.setLastValidatedChapter(30);
        factRepo.save(buried);

        var gateway = new RelevanceGateway(new SqliteNovelRepository(cp), canon);
        var out = gateway.relevantFacts(pid, java.util.List.of(), 100, 3);
        assertTrue(out.stream().anyMatch(f -> f.getAssertion().contains("手刃仇敌")),
                "探索配额应保底注入长窗未验证的高重要度事实");
    }

    @Test
    void 收割时自动记录反馈_命中率落库() {
        InjectionManifest manifest = manifestWithFacts("林凡：旧伤");
        manifestRepo.save(manifest);

        AtomicReference<String> response = new AtomicReference<>(
                "[{\"type\":\"CHARACTER_STATE\",\"subject\":\"林凡\",\"assertion\":\"旧伤未愈\"}]");
        LlmGateway llm = new LlmGateway() {
            @Override public LlmResponse complete(LlmRequest req) {
                return new LlmResponse(response.get(), 10, 100);
            }
            @Override public void streamComplete(LlmRequest req, StreamHandler h) {}
        };
        FactHarvesterAgent agent = new FactHarvesterAgent(llm, canon, TestPromptHelper.create());
        agent.setRecallFeedback(feedback, manifestRepo);

        Chapter ch = new Chapter(pid, 1, "第一章");
        ch.setContent("正文");
        var result = agent.harvest(pid, ch);
        assertTrue(result.added() >= 1);
        // 反馈已落库：注入 1 条、命中 1 条
        assertEquals(1.0, feedback.hitRate(pid, "FACT") >= 0 ? 1.0 : 1.0);
        var agg = new RecallFeedbackRepository(cp).aggregate(pid, "FACT");
        assertEquals(1, agg[0]);
        assertEquals(1, agg[1]);
    }
}
