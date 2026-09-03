package com.writing.novel.orchestrator;

import com.writing.novel.agent.FactHarvesterAgent;
import com.writing.novel.agent.PassageEditor;
import com.writing.novel.agent.TestPromptHelper;
import com.writing.novel.domain.Chapter;
import com.writing.novel.domain.Project;
import com.writing.novel.llm.LlmGateway;
import com.writing.novel.llm.LlmRequest;
import com.writing.novel.llm.LlmResponse;
import com.writing.novel.llm.client.StreamHandler;
import com.writing.novel.memory.ContextAssembler;
import com.writing.novel.memory.KnowledgeBaseService;
import com.writing.novel.memory.SummaryService;
import com.writing.novel.memory.VectorRetrievalService;
import com.writing.novel.persistence.sqlite.ConnectionProvider;
import com.writing.novel.persistence.sqlite.SqliteStoryCanon;
import com.writing.novel.persistence.sqlite.SqliteNovelRepository;
import com.writing.novel.persistence.sqlite.SqliteSchemaInitializer;
import com.writing.novel.persistence.sqlite.StoryFactRepository;
import com.writing.novel.spi.EmbeddingProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** p14-03: editPassage must return after persist even if harvest is slow. */
class EditPassageReturnsBeforeHarvestTest {

    @TempDir Path tempDir;
    private NovelOrchestrator orchestrator;
    private SqliteNovelRepository repo;
    // 后台 harvest 阻塞至 @AfterEach 释放并等待完成，避免 @TempDir 清理与后台任务竞态
    // （不能用 join：POST_PROCESS 线程池的空闲线程常驻不退出）
    private final CountDownLatch harvestRelease = new CountDownLatch(1);
    private final CountDownLatch harvestStarted = new CountDownLatch(1);
    private final CountDownLatch harvestDone = new CountDownLatch(1);
    private final CountDownLatch twoHarvestsDone = new CountDownLatch(2);

    @BeforeEach
    void setUp() {
        ConnectionProvider cp = new ConnectionProvider(tempDir.resolve("e.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        repo = new SqliteNovelRepository(cp);
        StoryFactRepository facts = new StoryFactRepository(cp);

        EmbeddingProvider emb = new EmbeddingProvider() {
            @Override public float[] embed(String text) { return new float[]{1.0f}; }
            @Override public int dimension() { return 1; }
        };
        var store = new com.writing.novel.persistence.embedding.EmbeddedVectorStore(
                tempDir.resolve("e.idx").toString());
        VectorRetrievalService vectorSvc = new VectorRetrievalService(store, emb);
        KnowledgeBaseService kbSvc = new KnowledgeBaseService(repo, vectorSvc);
        SummaryService summarySvc = new SummaryService(repo);
        ContextAssembler assembler = new ContextAssembler(repo, kbSvc, summarySvc, vectorSvc, null,
                new com.writing.novel.service.ClimaxDiversityTracker(), null, null, null);

        LlmGateway gateway = new LlmGateway() {
            @Override public LlmResponse complete(LlmRequest req) { return new LlmResponse("ok", 1, 1); }
            @Override public void streamComplete(LlmRequest req, StreamHandler h) {}
        };
        var promptSvc = TestPromptHelper.create();
        PassageEditor editor = new PassageEditor(gateway, promptSvc) {
            @Override
            public String editPassage(String passageText, String contextBefore, String contextAfter,
                                      String instruction, String mode) {
                return passageText + "屋檐滴水成串。";
            }
        };

        // p22-05: 收割桩经策略包组合期注入（原后置 setter）
        FactHarvesterAgent gatedHarvest = new FactHarvesterAgent(gateway, new SqliteStoryCanon(facts), promptSvc) {
            @Override
            public HarvestResult harvest(String projectId, Chapter chapter) {
                harvestStarted.countDown();
                try { harvestRelease.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                harvestDone.countDown();
                twoHarvestsDone.countDown();
                return new HarvestResult(0, 0, 0);
            }
        };
        orchestrator = new NovelOrchestrator(repo, new ProjectService(repo),
                null, null, null, null, null, null, null,
                summarySvc, assembler, null,
                null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, editor, null, null,
                new OrchestratorPolicies(null, null, null, null, null, null, null,
                        gatedHarvest, null, null, null));
    }

    @AfterEach
    void releaseHarvest() throws InterruptedException {
        if (!harvestStarted.await(5, TimeUnit.SECONDS)) return;
        harvestRelease.countDown();
        harvestDone.await(5, TimeUnit.SECONDS);
    }

    @Test
    void editPassage_returnsBeforeSlowHarvest() {
        Project p = new Project("t", "c", "tag");
        repo.saveProject(p);
        Chapter ch = new Chapter(p.getId(), 1, "雨夜账");
        ch.setContent("雨点砸在账桌的窗棂上，噼啪作响。\n\n吴掌柜摔下账册。");
        repo.saveChapter(ch);

        long t0 = System.nanoTime();
        Chapter out = orchestrator.editPassage(p.getId(), 1, 0, "雨点砸在账桌的窗棂上，噼啪作响。",
                "加雨声", "REWRITE");
        long ms = (System.nanoTime() - t0) / 1_000_000;

        assertTrue(ms < 800, "should return before 2s harvest, took " + ms);
        assertTrue(out.getContent().contains("屋檐滴水成串"));
    }

    @Test
    void directRevisionRefresh_doesNotReturnBeforeDerivedStateIsCurrent() throws Exception {
        Project p = new Project("直接编辑", "c", "tag");
        repo.saveProject(p);
        Chapter ch = new Chapter(p.getId(), 1, "雨夜账");
        ch.setContent("新版正文");
        repo.saveChapter(ch);

        CompletableFuture<Void> refresh = CompletableFuture.runAsync(
                () -> orchestrator.refreshChapterAfterRevision(p.getId(), ch));
        assertTrue(harvestStarted.await(2, TimeUnit.SECONDS), "derived refresh should reach harvest");
        try {
            assertFalse(refresh.isDone(), "direct edit response boundary must wait for derived refresh");
        } finally {
            harvestRelease.countDown();
        }
        refresh.get(2, TimeUnit.SECONDS);
        assertTrue(harvestDone.await(2, TimeUnit.SECONDS));
    }

    @Test
    void secondPassageEdit_returnsBeforePreviousDerivedRefreshCompletes() throws Exception {
        Project p = new Project("连续段落编辑", "c", "tag");
        repo.saveProject(p);
        Chapter ch = new Chapter(p.getId(), 1, "雨夜账");
        ch.setContent("雨点砸在账桌的窗棂上，噼啪作响。\n\n吴掌柜摔下账册。");
        repo.saveChapter(ch);

        orchestrator.editPassage(p.getId(), 1, 0, "雨点砸在账桌的窗棂上，噼啪作响。",
                "加雨声", "REWRITE");
        assertTrue(harvestStarted.await(2, TimeUnit.SECONDS));

        long t0 = System.nanoTime();
        try {
            Chapter second = orchestrator.editPassage(p.getId(), 1, 0,
                    "雨点砸在账桌的窗棂上，噼啪作响。屋檐滴水成串。",
                    "再加风声", "REWRITE");
            long ms = (System.nanoTime() - t0) / 1_000_000;
            assertTrue(ms < 800, "second passage save must not wait for old derived work, took " + ms);
            assertTrue(second.getVersion() >= 3);
        } finally {
            harvestRelease.countDown();
        }
        assertTrue(twoHarvestsDone.await(2, TimeUnit.SECONDS), "both queued refreshes should finish");
    }
}
