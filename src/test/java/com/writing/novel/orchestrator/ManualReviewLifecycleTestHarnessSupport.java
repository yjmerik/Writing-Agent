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
import com.writing.novel.domain.ChapterSynopsis;
import com.writing.novel.domain.Outline;
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
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

/**
 * 编排器写作链路测试公共 harness（自 ManualReviewLifecycleTest 抽出，p22-04 复用）：
 * SQLite + 桩网关 + 可覆写的 ConsistencyGuard，子类通过 guardOverride() 注入守卫策略。
 */
public abstract class ManualReviewLifecycleTestHarnessSupport {

    @TempDir Path tempDir;
    protected NovelOrchestrator orchestrator;
    protected SqliteNovelRepository repo;
    protected ProjectService projectService;

    /** 子类可覆写：注入一致性守卫策略（null=不启用） */
    protected ConsistencyGuard guardOverride() { return null; }

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
                guardOverride(),
                null, null, null, null, null, null, null);
        orchestrator.setRepairArbiter(new RepairArbiter());
    }

    protected NovelOrchestrator orchestrator() { return orchestrator; }

    protected SqliteNovelRepository repo() { return repo; }

    protected ProjectService projectService() { return projectService; }

    protected ChapterProduction production() { return orchestrator.production(); }

    /** 构思到 WRITING 的种子项目 */
    protected Project seedProject() {
        Project p = projectService.create("人工复核小说", "剑修故事", "玄幻");
        orchestrator.runConceiving(p.getId());
        orchestrator.step(p.getId());
        return p;
    }

    /** 按章号取梗概 */
    protected static ChapterSynopsis synopsisOf(Outline outline, int chapterIndex) {
        for (var v : outline.getVolumes()) {
            for (ChapterSynopsis s : v.getSynopses()) {
                if (s.getChapterIndex() == chapterIndex) return s;
            }
        }
        return null;
    }
}
