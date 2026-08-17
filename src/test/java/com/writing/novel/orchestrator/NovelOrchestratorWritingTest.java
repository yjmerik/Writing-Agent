package com.writing.novel.orchestrator;

import com.writing.novel.agent.*;
import com.writing.novel.domain.*;
import com.writing.novel.llm.LlmGateway;
import com.writing.novel.llm.LlmRequest;
import com.writing.novel.llm.LlmResponse;
import com.writing.novel.llm.client.StreamHandler;
import com.writing.novel.memory.*;
import com.writing.novel.service.EntityGraphService;
import com.writing.novel.persistence.sqlite.ConnectionProvider;
import com.writing.novel.persistence.sqlite.SqliteNovelRepository;
import com.writing.novel.persistence.sqlite.SqliteSchemaInitializer;
import com.writing.novel.spi.EmbeddingProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class NovelOrchestratorWritingTest {

    @TempDir Path tempDir;
    protected NovelOrchestrator orchestrator;
    protected SqliteNovelRepository repo;
    protected ProjectService projectService;

    @BeforeEach
    void setUp() {
        ConnectionProvider cp = new ConnectionProvider(tempDir.resolve("w.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        repo = new SqliteNovelRepository(cp);

        EmbeddingProvider emb = new EmbeddingProvider() {
            @Override public float[] embed(String text) { return new float[]{1.0f}; }
            @Override public int dimension() { return 1; }
        };
        var store = new com.writing.novel.persistence.embedding.EmbeddedVectorStore(
                tempDir.resolve("w.idx").toString());
        VectorRetrievalService vectorSvc = new VectorRetrievalService(store, emb);
        KnowledgeBaseService kbSvc = new KnowledgeBaseService(repo, vectorSvc);
        SummaryService summarySvc = new SummaryService(repo);
        EntityGraphService entityGraphSvc = new EntityGraphService(repo, null) {
            @Override public String recallConsistencySection(String projectId, int currentChapter) { return ""; }
            @Override public java.util.List<String> getRecentEntityNames(String projectId, int currentChapter) { return java.util.List.of(); }
            @Override public String resolveCanonical(String projectId, String rawName) { return rawName; }
            @Override public com.writing.novel.agent.EntityExtractorAgent.ExtractionResult extractFromChapter(String projectId, com.writing.novel.domain.Chapter chapter) {
                return new com.writing.novel.agent.EntityExtractorAgent.ExtractionResult(0, 0, 0, null);
            }
        };
        ContextAssembler assembler = new ContextAssembler(repo, kbSvc, summarySvc, vectorSvc, entityGraphSvc, new com.writing.novel.service.ClimaxDiversityTracker(), null, null, null);

        LlmGateway gateway = new LlmGateway() {
            @Override
            public LlmResponse complete(LlmRequest req) {
                return switch (req.getTaskType()) {
                    case WORLDBUILDING -> new LlmResponse(
                            "【地理】玄黄大陆：主大陆。\n【力量体系】灵气体系：修炼。", 50, 100);
                    case CHARACTER_DESIGN -> new LlmResponse(
                            "【姓名】张三\n【角色定位】主角\n【性格】火爆\n【能力】剑修", 50, 100);
                    case PLOTTING -> new LlmResponse("""
                            【总纲】张三的修炼之路。

                            【卷1】初入仙门
                            拜入宗门。
                            第1章 测试：灵根测试。
                            第2章 修炼：初次修炼。
                            第3章 比武：外门比武。
                            """, 50, 100);
                    case CHAPTER_WRITING -> new LlmResponse(
                            "这是本章的正文内容，张三的故事继续。".repeat(120), 100, 500);
                    case MEMORY_SUMMARY -> new LlmResponse("章节摘要：张三的修炼进展。", 50, 50);
                    default -> new LlmResponse("ok", 1, 1);
                };
            }
            @Override
            public void streamComplete(LlmRequest req, StreamHandler h) {}
        };

        projectService = new ProjectService(repo);
        var promptSvc = com.writing.novel.agent.TestPromptHelper.create();
        orchestrator = new NovelOrchestrator(repo, projectService,
                new WorldBuilderAgent(gateway, kbSvc, promptSvc),
                new CharacterDesignerAgent(gateway, kbSvc, promptSvc),
                new PlotterAgent(gateway, repo, promptSvc),
                new ChapterWriterAgent(gateway, assembler, repo, vectorSvc, promptSvc, null),
                new ContinuityCheckerAgent(gateway, repo, promptSvc),
                new StyleGuardianAgent(gateway, repo, promptSvc),
                new MemoryManagerAgent(gateway, summarySvc, promptSvc),
                summarySvc, assembler,
                new com.writing.novel.agent.QualityEvaluationAgent(gateway, repo, promptSvc),
                null, null, null, null, null, null, null, null, null, null, null, null,
                new com.writing.novel.agent.SceneClassifier(gateway, promptSvc),
                new com.writing.novel.service.DiversityTracker(), null, null, null, null, null, null, null, null, null);
    }

    @Test
    void 全流程_构思_规划_写三章() {
        Project p = projectService.create("测试小说", "剑修故事", "玄幻");
        orchestrator.runConceiving(p.getId());
        orchestrator.step(p.getId());
        orchestrator.runWritingOneChapter(p.getId());
        orchestrator.runWritingOneChapter(p.getId());
        orchestrator.runWritingOneChapter(p.getId());

        List<Chapter> chapters = repo.findChaptersByProject(p.getId());
        assertEquals(3, chapters.size());
        assertEquals(3, repo.findProject(p.getId()).get().getCurrentChapterIndex());
        assertFalse(repo.findSummariesByProject(p.getId()).isEmpty(), "第3章后应有摘要");
    }

    @Test
    void 写完所有章节后进入卷完结() {
        Project p = projectService.create("t", "c", "tag");
        orchestrator.runConceiving(p.getId());
        orchestrator.step(p.getId());

        orchestrator.runWritingOneChapter(p.getId());
        orchestrator.runWritingOneChapter(p.getId());
        orchestrator.runWritingOneChapter(p.getId());
        orchestrator.runWritingOneChapter(p.getId());

        assertEquals(NovelState.VOLUME_DONE, repo.findProject(p.getId()).get().getState());
    }

    @Test
    void 交互模式在构思后暂停() {
        Project p = projectService.create("t", "c", "tag", ProjectMode.INTERACTIVE);
        orchestrator.runConceiving(p.getId());

        Project found = repo.findProject(p.getId()).orElseThrow();
        assertEquals(NovelState.PAUSED_AT_CHECKPOINT, found.getState());
        assertFalse(repo.findPendingCheckpoints(p.getId()).isEmpty(), "应有待审核 checkpoint");
    }

    @Test
    void 交互模式连续写作只写一章就暂停() {
        Project p = projectService.create("t", "c", "tag", ProjectMode.INTERACTIVE);
        orchestrator.runConceiving(p.getId());
        orchestrator.step(p.getId());
        orchestrator.step(p.getId());

        int written = orchestrator.runWritingLoop(p.getId(), 5);
        assertEquals(1, written);
        assertEquals(NovelState.PAUSED_AT_CHECKPOINT, repo.findProject(p.getId()).orElseThrow().getState());
    }

    @Test
    void pause将项目置为暂停() {
        Project p = projectService.create("t", "c", "tag");
        orchestrator.runConceiving(p.getId());
        orchestrator.step(p.getId());
        orchestrator.pause(p.getId());
        assertEquals(NovelState.PAUSED_AT_CHECKPOINT, repo.findProject(p.getId()).orElseThrow().getState());
        assertEquals(0, orchestrator.runWritingLoop(p.getId(), 3));
    }
}
