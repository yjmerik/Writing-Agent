package com.writing.novel.orchestrator;

import com.writing.novel.agent.*;
import com.writing.novel.domain.*;
import com.writing.novel.llm.LlmGateway;
import com.writing.novel.llm.LlmRequest;
import com.writing.novel.llm.LlmResponse;
import com.writing.novel.llm.client.StreamHandler;
import com.writing.novel.memory.*;
import com.writing.novel.persistence.sqlite.ConnectionProvider;
import com.writing.novel.persistence.sqlite.SqliteNovelRepository;
import com.writing.novel.persistence.sqlite.SqliteSchemaInitializer;
import com.writing.novel.service.EntityGraphService;
import com.writing.novel.spi.EmbeddingProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * p11-q0-05: continuity 重写后必须复检（之前只标 resolved 不重跑，重写引入的新矛盾不可见）。
 */
class RewriteRecheckTest {

    @TempDir Path tempDir;
    private NovelOrchestrator orchestrator;
    private ProjectService projectService;
    private final AtomicInteger continuityCheckCalls = new AtomicInteger();

    @BeforeEach
    void setUp() {
        ConnectionProvider cp = new ConnectionProvider(tempDir.resolve("r.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        var repo = new SqliteNovelRepository(cp);
        EmbeddingProvider emb = new EmbeddingProvider() {
            @Override public float[] embed(String t) { return new float[]{1f}; }
            @Override public int dimension() { return 1; }
        };
        var store = new com.writing.novel.persistence.embedding.EmbeddedVectorStore(tempDir.resolve("r.idx").toString());
        VectorRetrievalService vectorSvc = new VectorRetrievalService(store, emb);
        KnowledgeBaseService kbSvc = new KnowledgeBaseService(repo, vectorSvc);
        SummaryService summarySvc = new SummaryService(repo);
        EntityGraphService egSvc = new EntityGraphService(repo, null) {
            @Override public String recallConsistencySection(String p, int c) { return ""; }
            @Override public List<String> getRecentEntityNames(String p, int c) { return List.of(); }
            @Override public String resolveCanonical(String p, String n) { return n; }
            @Override public EntityExtractorAgent.ExtractionResult extractFromChapter(String p, Chapter c) {
                return new EntityExtractorAgent.ExtractionResult(0, 0, 0, null);
            }
        };
        ContextAssembler assembler = new ContextAssembler(repo, kbSvc, summarySvc, vectorSvc, egSvc,
                new com.writing.novel.service.ClimaxDiversityTracker(), null, null, null);

        LlmGateway gateway = new LlmGateway() {
            @Override
            public LlmResponse complete(LlmRequest req) {
                return switch (req.getTaskType()) {
                    case WORLDBUILDING -> new LlmResponse("【地理】大陆。\n【力量体系】灵气体系。", 50, 100);
                    case CHARACTER_DESIGN -> new LlmResponse(
                            "【姓名】李四\n【角色定位】主角\n【能力】剑修初成", 50, 100);
                    case PLOTTING -> new LlmResponse("""
                            【总纲】李四的修炼之路。
                            【卷1】开端
                            第1章 起：开端。
                            第2章 承：发展。
                            第3章 转：转折。
                            """, 50, 100);
                    case CHAPTER_WRITING -> new LlmResponse(
                            "李四的故事内容继续展开，情节推进。".repeat(150), 100, 500);
                    case CONTINUITY_CHECK -> {
                        continuityCheckCalls.incrementAndGet();
                        yield new LlmResponse("【人物|高】测试矛盾：李四能力前后不一致。", 50, 50);
                    }
                    case MEMORY_SUMMARY -> new LlmResponse("摘要：进展。", 50, 50);
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
                null, null, null, null, null, null, null, null, null, null);
    }

    @Test
    void continuity重写后必须复检_检测重写引入的新矛盾() {
        Project p = projectService.create("测试", "剑修", "玄幻");
        orchestrator.runConceiving(p.getId());
        orchestrator.step(p.getId());
        orchestrator.runWritingOneChapter(p.getId());

        // continuity HIGH 触发重写，q0-05 复检：CONTINUITY_CHECK 至少调用 2 次（重写前 + 复检）
        assertTrue(continuityCheckCalls.get() >= 2,
                "continuity 应重写后复检，实际 CONTINUITY_CHECK 调用次数=" + continuityCheckCalls.get());
    }
}
