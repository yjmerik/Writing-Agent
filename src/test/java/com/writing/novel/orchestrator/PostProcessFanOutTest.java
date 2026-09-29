package com.writing.novel.orchestrator;

import com.writing.novel.agent.TestPromptHelper;
import com.writing.novel.llm.LlmGateway;
import com.writing.novel.llm.LlmRequest;
import com.writing.novel.llm.LlmResponse;
import com.writing.novel.llm.client.StreamHandler;
import com.writing.novel.persistence.sqlite.ConnectionProvider;
import com.writing.novel.persistence.sqlite.PostProcessLedgerRepository;
import com.writing.novel.persistence.sqlite.SqliteNovelRepository;
import com.writing.novel.persistence.sqlite.SqliteSchemaInitializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * NCS-132: 后处理并行扇出——ENTITY_GRAPH/NARRATIVE_THREADS/FORESHADOWING 并行执行，
 * ledger 逐阶段语义不变。
 */
class PostProcessFanOutTest {

    @TempDir
    Path tempDir;
    private SqliteNovelRepository repo;
    private PostProcessLedgerRepository ledger;
    private final AtomicInteger active = new AtomicInteger();
    private final AtomicInteger maxConcurrent = new AtomicInteger();
    private final Map<String, Integer> callsByStage = new ConcurrentHashMap<>();

    @BeforeEach
    void setUp() {
        ConnectionProvider cp = new ConnectionProvider(tempDir.resolve("fanout.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        repo = new SqliteNovelRepository(cp);
        ledger = new PostProcessLedgerRepository(cp);
        var project = new com.writing.novel.domain.Project("扇出", "构思", "wuxia");
        repo.saveProject(project);
    }

    /** 慢速网关：按 prompt 判阶段、统计并发峰值 */
    private LlmGateway trackingGateway() {
        return new LlmGateway() {
            @Override public LlmResponse complete(LlmRequest req) {
                String sys = req.getSystemPrompt() == null ? "" : req.getSystemPrompt();
                String stage;
                if (sys.contains("foreshadowing")) stage = "FORESHADOWING";
                else if (sys.contains("实体") || sys.contains("entity")) stage = "ENTITY_GRAPH";
                else stage = "NARRATIVE_THREADS";
                callsByStage.merge(stage, 1, Integer::sum);
                int now = active.incrementAndGet();
                maxConcurrent.accumulateAndGet(now, Math::max);
                try { Thread.sleep(150); } catch (InterruptedException ignored) {}
                active.decrementAndGet();
                return stage.equals("FORESHADOWING")
                        ? new LlmResponse("{\"foreshadowings\":[]}", 1, 1)
                        : new LlmResponse("[]", 1, 1);
            }
            @Override public void streamComplete(LlmRequest req, StreamHandler h) {}
        };
    }

    @Test
    void 三独立提取器并行_存在重叠_结果等价() {
        var gateway = trackingGateway();
        var promptSvc = TestPromptHelper.create();
        var entityGraph = new com.writing.novel.service.EntityGraphService(repo,
                new com.writing.novel.agent.EntityExtractorAgent(gateway, repo, promptSvc));
        var threads = new com.writing.novel.agent.NarrativeThreadExtractor(gateway,
                new com.writing.novel.service.NarrativeThreadTracker(repo), promptSvc);
        var foreshadow = new com.writing.novel.agent.ForeshadowingExtractor(gateway, repo, promptSvc);
        var orch = mock(NovelOrchestrator.class);
        var production = mock(ChapterProduction.class);
        when(orch.production()).thenReturn(production);
        when(production.qualityGate(anyString(), anyInt(), any(), any()))
                .thenAnswer(inv -> false);
        when(orch.relevanceGateway()).thenReturn(null);


        var processor = new ChapterPostProcessor(orch, repo, null, entityGraph, threads, null,
                foreshadow, null, null, null, null, null, null, null, null, null, null, ledger);

        var chapter = new com.writing.novel.domain.Chapter("扇出", 1, "第一章");
        chapter.setContent("足够长的正文以确保抽取器门槛。".repeat(30));
        repo.saveChapter(chapter);

        processor.process("扇出", chapter, 1, null, null);

        assertEquals(1, callsByStage.getOrDefault("ENTITY_GRAPH", 0));
        assertEquals(1, callsByStage.getOrDefault("FORESHADOWING", 0));
        assertTrue(maxConcurrent.get() >= 2,
                "独立提取器应并行（最大并发=" + maxConcurrent.get() + "）calls=" + callsByStage);
        assertTrue(ledger.allCompleted("扇出", 1), "扇出后 ledger 仍全部 COMPLETED");
    }
}
