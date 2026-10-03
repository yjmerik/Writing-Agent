package com.writing.novel.orchestrator;

import com.writing.novel.agent.ForeshadowingExtractor;
import com.writing.novel.agent.TestPromptHelper;
import com.writing.novel.llm.LlmGateway;
import com.writing.novel.llm.LlmRequest;
import com.writing.novel.llm.LlmResponse;
import com.writing.novel.llm.client.StreamHandler;
import com.writing.novel.persistence.sqlite.ConnectionProvider;
import com.writing.novel.persistence.sqlite.PostProcessLedgerRepository;
import com.writing.novel.persistence.sqlite.SqliteNovelRepository;
import com.writing.novel.persistence.sqlite.SqliteSchemaInitializer;
import com.writing.novel.telemetry.EventLog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * NCS-173: 后处理扇出阶段超时防护——上游 LLM 停滞导致扇出子任务永不完成时，
 * awaitFanout 必须在可配置时限内放弃该阶段（cancel + 事件 + 抛出待恢复），
 * 写作循环不得无限冻结；其余健康阶段正常完成并记账。
 *
 * <p>复现 2026-10-03 验证轮 P1：ForeshadowingExtractor 阻塞在读，
 * awaitFanout 无超时 f.get() 使整条写作循环挂死 15 分钟。
 */
class ChapterPostProcessorFanoutTimeoutTest {

    private static final String TIMEOUT_PROP = "novel.postprocess.fanout-timeout-seconds";

    @TempDir
    Path tempDir;
    private SqliteNovelRepository repo;
    private PostProcessLedgerRepository ledger;
    private String previousTimeoutProp;
    private String previousEventsProp;

    @BeforeEach
    void setUp() {
        previousTimeoutProp = System.getProperty(TIMEOUT_PROP);
        System.setProperty(TIMEOUT_PROP, "1");
        previousEventsProp = System.getProperty(EventLog.PATH_PROPERTY);
        System.setProperty(EventLog.PATH_PROPERTY, tempDir.resolve("events.jsonl").toString());
        ConnectionProvider cp = new ConnectionProvider(tempDir.resolve("timeout.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        repo = new SqliteNovelRepository(cp);
        ledger = new PostProcessLedgerRepository(cp);
        var project = new com.writing.novel.domain.Project("停滞", "构思", "wuxia");
        repo.saveProject(project);
    }

    @AfterEach
    void tearDown() {
        if (previousTimeoutProp == null) System.clearProperty(TIMEOUT_PROP);
        else System.setProperty(TIMEOUT_PROP, previousTimeoutProp);
        if (previousEventsProp == null) System.clearProperty(EventLog.PATH_PROPERTY);
        else System.setProperty(EventLog.PATH_PROPERTY, previousEventsProp);
    }

    /** 伏笔提取调用永不返回（模拟上游停滞的不可中断 socket 读），其余阶段快速返回 */
    private LlmGateway stalledForeshadowGateway() {
        return new LlmGateway() {
            @Override public LlmResponse complete(LlmRequest req) {
                String sys = req.getSystemPrompt() == null ? "" : req.getSystemPrompt();
                if (sys.contains("foreshadowing") || sys.contains("伏笔")) {
                    CountDownLatch latch = new CountDownLatch(1);
                    boolean interrupted = false;
                    while (true) {
                        try {
                            latch.await();
                            break;
                        } catch (InterruptedException e) {
                            // 真实停滞的 HTTP 读不响应中断——吞掉中断继续等待
                            interrupted = true;
                        }
                    }
                    if (interrupted) Thread.currentThread().interrupt();
                }
                return new LlmResponse("[]", 1, 1);
            }
            @Override public void streamComplete(LlmRequest req, StreamHandler h) {}
        };
    }

    @Test
    void 扇出阶段停滞_超时放弃_写作循环在时限内返回且停滞阶段不入完成账() throws Exception {
        var gateway = stalledForeshadowGateway();
        var promptSvc = TestPromptHelper.create();
        var entityGraph = new com.writing.novel.service.EntityGraphService(repo,
                new com.writing.novel.agent.EntityExtractorAgent(gateway, repo, promptSvc));
        var threads = new com.writing.novel.agent.NarrativeThreadExtractor(gateway,
                new com.writing.novel.service.NarrativeThreadTracker(repo), promptSvc);
        var foreshadow = new ForeshadowingExtractor(gateway, repo, promptSvc);
        var orch = mock(NovelOrchestrator.class);
        var production = mock(ChapterProduction.class);
        when(orch.production()).thenReturn(production);
        when(production.qualityGate(anyString(), anyInt(), any(), any())).thenAnswer(inv -> false);
        when(orch.relevanceGateway()).thenReturn(null);

        var processor = new ChapterPostProcessor(orch, repo, null, entityGraph, threads, null,
                foreshadow, null, null, null, null, null, null, null, null, null, null, ledger);

        var chapter = new com.writing.novel.domain.Chapter("停滞", 1, "第一章");
        chapter.setContent("足够长的正文以确保抽取器门槛。".repeat(30));
        repo.saveChapter(chapter);

        // 守护：当前实现会无限挂死——用独立线程 + 有限 join 让失败可判定而非拖死测试套件
        CompletableFuture<Object> run = CompletableFuture.supplyAsync(() ->
                processor.process("停滞", chapter, 1, null, null));
        try {
            run.get(30, TimeUnit.SECONDS);
            fail("扇出阶段停滞应以上待恢复异常结束（当前实现无超时防护 → 挂死）");
        } catch (java.util.concurrent.ExecutionException e) {
            assertTrue(e.getCause() instanceof IllegalStateException,
                    "应抛待恢复异常，实际: " + e.getCause());
            String msg = String.valueOf(e.getCause().getMessage());
            assertTrue(msg.contains("FORESHADOWING") && msg.contains("超时"),
                    "异常应指明停滞阶段与超时语义: " + msg);
        }
        // 停滞/被超时放弃的阶段不标 COMPLETED（不视为无问题，重启续跑补齐）；健康阶段正常完成
        List<ChapterPostProcessor.Stage> unfinished = ledger.unfinishedStages("停滞", 1);
        assertTrue(unfinished.contains(ChapterPostProcessor.Stage.FORESHADOWING),
                "停滞阶段应保持未完成: " + unfinished);
        assertFalse(unfinished.contains(ChapterPostProcessor.Stage.ENTITY_GRAPH),
                "先于停滞完成的健康阶段不应回退: " + unfinished);
    }
}
