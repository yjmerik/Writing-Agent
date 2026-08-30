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
import com.writing.novel.service.ConceiveGate;
import com.writing.novel.service.EntityGraphService;
import com.writing.novel.spi.EmbeddingProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * p11-q1-03: 软门禁策略——门禁重试上限后仍不过，标记 quality_risk（不阻塞推进）。
 */
class GatePolicyTest {

    @TempDir Path tempDir;
    private NovelOrchestrator orchestrator;
    private ProjectService projectService;
    private SqliteNovelRepository repo;

    @BeforeEach
    void setUp() {
        ConnectionProvider cp = new ConnectionProvider(tempDir.resolve("g.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        repo = new SqliteNovelRepository(cp);
        EmbeddingProvider emb = new EmbeddingProvider() {
            @Override public float[] embed(String t) { return new float[]{1f}; }
            @Override public int dimension() { return 1; }
        };
        var store = new com.writing.novel.persistence.embedding.EmbeddedVectorStore(tempDir.resolve("g.idx").toString());
        VectorRetrievalService vectorSvc = new VectorRetrievalService(store, emb);
        KnowledgeBaseService kbSvc = new KnowledgeBaseService(repo, vectorSvc);
        SummaryService summarySvc = new SummaryService(repo);
        EntityGraphService egSvc = new EntityGraphService(repo, null) {
            @Override public String recallConsistencySection(String p, int c) { return ""; }
            @Override public List<String> getRecentEntityNames(String p, int c) { return List.of(); }
            @Override public String resolveCanonical(String p, String n) { return n; }
            @Override public EntityExtractorAgent.ExtractionResult extractFromChapter(String p, com.writing.novel.domain.Chapter c) {
                return new EntityExtractorAgent.ExtractionResult(0, 0, 0, null);
            }
        };
        ContextAssembler assembler = new ContextAssembler(repo, kbSvc, summarySvc, vectorSvc, egSvc,
                new com.writing.novel.service.ClimaxDiversityTracker(), null, null, null);
        // WorldBuilder 故意只返回【地理】（缺 POWER_SYSTEM）→ 触发门禁失败
        LlmGateway gateway = new LlmGateway() {
            @Override
            public LlmResponse complete(LlmRequest req) {
                return switch (req.getTaskType()) {
                    case WORLDBUILDING -> new LlmResponse("【地理】玄黄大陆：主大陆。", 50, 100);
                    case CHARACTER_DESIGN -> new LlmResponse(
                            "【姓名】李四\n【角色定位】主角\n【能力】剑修初成", 50, 100);
                    case PLOTTING -> new LlmResponse("""
                            【总纲】李四的修炼之路。
                            【卷1】开端
                            第1章 起：开端。
                            第2章 承：发展。
                            第3章 转：转折。
                            """, 50, 100);
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
                null, null, null, null, null, null, null, null, null,
                new OrchestratorPolicies(null, null, null, new ConceiveGate(),
                        null, null, null, null, null, null, null));
    }

    @Test
    void worldBuilder门禁重试上限后仍不过_标记quality_risk不阻塞() {
        Project p = projectService.create("测试", "剑修", "玄幻");
        // 门禁失败不抛异常（软门禁），runConceiving 正常完成
        assertDoesNotThrow(() -> orchestrator.runConceiving(p.getId()));

        // 应记录 quality_risk（WorldBuilder 缺力量体系）
        var issues = repo.findUnresolvedIssues(p.getId());
        assertTrue(issues.stream().anyMatch(i -> i.getDescription().contains("quality_risk")
                        && i.getDescription().contains("WorldBuilder")),
                "WorldBuilder 门禁失败应标记 quality_risk，实际 issues=" 
                        + issues.stream().map(ContinuityIssue::getDescription).toList());
    }
}
