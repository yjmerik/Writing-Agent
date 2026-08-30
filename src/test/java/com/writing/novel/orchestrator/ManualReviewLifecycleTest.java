package com.writing.novel.orchestrator;

import com.writing.novel.agent.CharacterDesignerAgent;
import com.writing.novel.agent.ChapterWriterAgent;
import com.writing.novel.agent.ConsistencyGuard;
import com.writing.novel.agent.ContinuityCheckerAgent;
import com.writing.novel.agent.MemoryManagerAgent;
import com.writing.novel.agent.PlotterAgent;
import com.writing.novel.agent.StyleGuardianAgent;
import com.writing.novel.agent.TestPromptHelper;
import com.writing.novel.agent.WorldBuilderAgent;
import com.writing.novel.domain.Chapter;
import com.writing.novel.domain.ContinuityIssue;
import com.writing.novel.domain.Project;
import com.writing.novel.llm.LlmGateway;
import com.writing.novel.llm.LlmRequest;
import com.writing.novel.llm.LlmResponse;
import com.writing.novel.llm.client.StreamHandler;
import com.writing.novel.memory.ContextAssembler;
import com.writing.novel.memory.KnowledgeBaseService;
import com.writing.novel.memory.SummaryService;
import com.writing.novel.memory.VectorRetrievalService;
import com.writing.novel.persistence.embedding.EmbeddedVectorStore;
import com.writing.novel.persistence.sqlite.ConnectionProvider;
import com.writing.novel.persistence.sqlite.SqliteNovelRepository;
import com.writing.novel.persistence.sqlite.SqliteSchemaInitializer;
import com.writing.novel.service.EntityGraphService;
import com.writing.novel.service.RepairArbiter;
import com.writing.novel.spi.EmbeddingProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * p20-01: 人工复核结果优先级。
 *
 * 修复前缺陷：checkContinuityAfterWriting 内 applyRepairClassification 把章节标记为
 * NEEDS_MANUAL_REVIEW，但 doWriteOneChapter 末尾无条件 setStatus(DONE) 覆盖了人工复核标记；
 * 且下一章写作不检查前章状态，直接越过待人工章节继续写。
 *
 * 验收：
 * 1. 触发 MANUAL_REVIEW 仲裁后，后处理全部跑完章节仍保持 NEEDS_MANUAL_REVIEW；
 * 2. 待人工章节未处理前，下一章写作被拒绝；
 * 3. 连续写作循环在待人工章节处停下，不推进；
 * 4. 人工复核通过（置 DONE）后写作可继续。
 */
class ManualReviewLifecycleTest {

    @TempDir Path tempDir;
    private NovelOrchestrator orchestrator;
    private SqliteNovelRepository repo;
    private ProjectService projectService;

    /** 恒定返回 HIGH CHARACTER 矛盾的一致性守卫——稳定触发重写后仍存 → MANUAL_REVIEW 仲裁 */
    static class AlwaysHighGuard extends ConsistencyGuard {
        AlwaysHighGuard() {
            super(null, null, null);
        }
        @Override
        public List<ContinuityIssue> check(String projectId, int chapterIndex, String chapterContent, List<String> cast) {
            return List.of(new ContinuityIssue(projectId, chapterIndex,
                    ContinuityIssue.Type.CHARACTER, ContinuityIssue.Severity.HIGH,
                    "测试注入：根源性人物矛盾"));
        }
    }

    @BeforeEach
    void setUp() {
        ConnectionProvider cp = new ConnectionProvider(tempDir.resolve("mrl.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        repo = new SqliteNovelRepository(cp);

        EmbeddingProvider emb = new EmbeddingProvider() {
            @Override public float[] embed(String text) { return new float[]{1.0f}; }
            @Override public int dimension() { return 1; }
        };
        var store = new EmbeddedVectorStore(tempDir.resolve("mrl.idx").toString());
        VectorRetrievalService vectorSvc = new VectorRetrievalService(store, emb);
        KnowledgeBaseService kbSvc = new KnowledgeBaseService(repo, vectorSvc);
        SummaryService summarySvc = new SummaryService(repo);
        EntityGraphService entityGraphSvc = new EntityGraphService(repo, null) {
            @Override public String recallConsistencySection(String projectId, int currentChapter) { return ""; }
            @Override public List<String> getRecentEntityNames(String projectId, int currentChapter) { return List.of(); }
            @Override public String resolveCanonical(String projectId, String rawName) { return rawName; }
            @Override public com.writing.novel.agent.EntityExtractorAgent.ExtractionResult extractFromChapter(String projectId, com.writing.novel.domain.Chapter chapter) {
                return new com.writing.novel.agent.EntityExtractorAgent.ExtractionResult(0, 0, 0, null);
            }
        };
        ContextAssembler assembler = new ContextAssembler(repo, kbSvc, summarySvc, vectorSvc,
                entityGraphSvc, new com.writing.novel.service.ClimaxDiversityTracker(), null, null, null);

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
        var promptSvc = TestPromptHelper.create();
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
                entityGraphSvc,
                null, null, null, null, null, null, null, null, null, null, null, null,
                null, null,
                new AlwaysHighGuard(),
                null, null, null, null, null, null, null);
        orchestrator.setRepairArbiter(new RepairArbiter());
    }

    private Project seedProject() {
        Project p = projectService.create("人工复核小说", "剑修故事", "玄幻");
        orchestrator.runConceiving(p.getId());
        orchestrator.step(p.getId());
        return p;
    }

    @Test
    void 人工复核标记不被后处理完成覆盖() {
        String pid = seedProject().getId();
        Chapter ch = orchestrator.runWritingOneChapter(pid);
        assertNotNull(ch, "章节应写成");
        assertEquals(Chapter.Status.NEEDS_MANUAL_REVIEW, ch.getStatus(),
                "MANUAL_REVIEW 仲裁后章节必须保持 NEEDS_MANUAL_REVIEW，不得被末尾 DONE 覆盖");
    }

    @Test
    void 待人工章节未处理前拒绝写下一章() {
        String pid = seedProject().getId();
        orchestrator.runWritingOneChapter(pid);
        assertEquals(Chapter.Status.NEEDS_MANUAL_REVIEW,
                repo.findChapterByIndex(pid, 1).orElseThrow().getStatus());

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> orchestrator.runWritingOneChapter(pid),
                "前章待人工复核时写下一章必须被拒绝");
        assertTrue(ex.getMessage().contains("人工复核") || ex.getMessage().contains("人工"));
    }

    @Test
    void 连续写作循环在待人工章节处停下() {
        String pid = seedProject().getId();
        int written = orchestrator.runWritingLoop(pid, 3);
        assertEquals(1, written, "第1章待人工复核后循环必须停止，不得继续写第2章");
        assertTrue(repo.findChapterByIndex(pid, 2).isEmpty(), "不得越过待人工章节写后续章节");
        assertEquals(1, repo.findProject(pid).orElseThrow().getCurrentChapterIndex());
    }

    @Test
    void 人工复核通过后写作继续() {
        String pid = seedProject().getId();
        orchestrator.runWritingOneChapter(pid);
        assertEquals(Chapter.Status.NEEDS_MANUAL_REVIEW,
                repo.findChapterByIndex(pid, 1).orElseThrow().getStatus());

        // 人工复核通过（等价 resolve-review 端点行为：NEEDS_MANUAL_REVIEW → DONE）
        Chapter resolved = repo.findChapterByIndex(pid, 1).orElseThrow();
        resolved.setStatus(Chapter.Status.DONE);
        repo.saveChapter(resolved);

        Chapter next = orchestrator.runWritingOneChapter(pid);
        assertNotNull(next, "复核通过后应可继续写下一章");
        assertEquals(2, next.getChapterIndex());
    }
}
