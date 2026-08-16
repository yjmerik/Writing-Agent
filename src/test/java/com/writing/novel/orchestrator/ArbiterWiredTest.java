package com.writing.novel.orchestrator;

import com.writing.novel.agent.*;
import com.writing.novel.domain.Project;
import com.writing.novel.llm.LlmGateway;
import com.writing.novel.llm.LlmRequest;
import com.writing.novel.llm.LlmResponse;
import com.writing.novel.llm.client.StreamHandler;
import com.writing.novel.memory.*;
import com.writing.novel.persistence.sqlite.ConnectionProvider;
import com.writing.novel.persistence.sqlite.SqliteNovelRepository;
import com.writing.novel.persistence.sqlite.SqliteSchemaInitializer;
import com.writing.novel.service.Arbiter;
import com.writing.novel.service.QualityCouncil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ArbiterWiredTest {

    @TempDir Path tempDir;
    private NovelOrchestrator orchestrator;
    private ProjectService projectService;
    private final AtomicInteger arbiterCalls = new AtomicInteger();

    @BeforeEach
    void setUp() {
        ConnectionProvider cp = new ConnectionProvider(tempDir.resolve("arb.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        var repo = new SqliteNovelRepository(cp);
        var emb = new com.writing.novel.spi.EmbeddingProvider() {
            @Override public float[] embed(String t) { return new float[]{1f}; }
            @Override public int dimension() { return 1; }
        };
        var store = new com.writing.novel.persistence.embedding.EmbeddedVectorStore(tempDir.resolve("arb.idx").toString());
        var vectorSvc = new VectorRetrievalService(store, emb);
        var kbSvc = new KnowledgeBaseService(repo, vectorSvc);
        var summarySvc = new SummaryService(repo);
        var assembler = new ContextAssembler(repo, kbSvc, summarySvc, vectorSvc, null,
                new com.writing.novel.service.ClimaxDiversityTracker(), null,
                new com.writing.novel.service.DiversityTracker(), null);

        LlmGateway gateway = new LlmGateway() {
            @Override
            public LlmResponse complete(LlmRequest req) {
                return switch (req.getTaskType()) {
                    case WORLDBUILDING -> new LlmResponse("【地理】大陆。\n【力量体系】灵气。", 50, 100);
                    case CHARACTER_DESIGN -> new LlmResponse("【姓名】李四\n【角色定位】主角\n【能力】剑修", 50, 100);
                    case PLOTTING -> new LlmResponse("""
                            【总纲】李四之路。
                            【卷1】开端
                            第1章 起：开端。
                            第2章 承：发展。
                            第3章 转：转折。
                            """, 50, 100);
                    case CHAPTER_WRITING -> new LlmResponse("李四的故事继续展开。".repeat(250), 100, 500);
                    case CONTINUITY_CHECK -> new LlmResponse("【人物|高】能力前后不一致。", 50, 50);
                    default -> new LlmResponse("ok", 1, 1);
                };
            }
            @Override public void streamComplete(LlmRequest req, StreamHandler h) {}
        };

        var promptSvc = TestPromptHelper.create();
        projectService = new ProjectService(repo);
        orchestrator = new NovelOrchestrator(repo, projectService,
                new WorldBuilderAgent(gateway, kbSvc, promptSvc),
                new CharacterDesignerAgent(gateway, kbSvc, promptSvc),
                new PlotterAgent(gateway, repo, promptSvc),
                new ChapterWriterAgent(gateway, assembler, repo, vectorSvc, promptSvc, null),
                new ContinuityCheckerAgent(gateway, repo, promptSvc),
                new StyleGuardianAgent(gateway, repo, promptSvc),
                new MemoryManagerAgent(gateway, summarySvc, promptSvc),
                summarySvc, assembler,
                new QualityEvaluationAgent(gateway, repo, promptSvc),
                null, null, null, null, null, null, null, null, null, null, null, null,
                new SceneClassifier(gateway, promptSvc),
                new com.writing.novel.service.DiversityTracker(),
                null, null, null, null, null, null, null, null, null);
        orchestrator.setQualityCouncil(new QualityCouncil());
        orchestrator.setArbiter(new Arbiter() {
            @Override
            public Arbitration arbitrate(QualityCouncil.CouncilReport report, int rewriteBudget, boolean alreadyRewritten) {
                arbiterCalls.incrementAndGet();
                return super.arbitrate(report, rewriteBudget, alreadyRewritten);
            }
        });
    }

    @Test
    void 写作环调用Arbiter_有HIGH时合并重写() {
        Project p = projectService.create("测", "剑修", "玄幻");
        orchestrator.runConceiving(p.getId());
        orchestrator.step(p.getId());
        orchestrator.runWritingOneChapter(p.getId());
        assertTrue(arbiterCalls.get() >= 1, "checkContinuityAfterWriting 必须调用 Arbiter.arbitrate，实际=" + arbiterCalls.get());
    }
}
