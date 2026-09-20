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

class GuardFindingRetentionTest {

    @TempDir Path tempDir;
    private NovelOrchestrator orchestrator;
    private ProjectService projectService;
    private final AtomicInteger guardCalls = new AtomicInteger();
    private com.writing.novel.spi.NovelRepository repository;
    private final java.util.concurrent.atomic.AtomicReference<LlmRequest> correction = new java.util.concurrent.atomic.AtomicReference<>();

    @BeforeEach
    void setUp() {
        ConnectionProvider cp = new ConnectionProvider(tempDir.resolve("arb.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        var repo = new SqliteNovelRepository(cp);
        repository = repo;
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
                if (req.getSystemPrompt().startsWith("你是小说一致性审查专家")) {
                    boolean first = guardCalls.incrementAndGet() == 1;
                    return new LlmResponse(first
                            ? "{\"abilityViolations\":[],\"evidenceProblems\":[{\"evidence\":\"未出示先使用的官印\",\"reason\":\"必须补足获取过程\"}]}"
                            : "{\"abilityViolations\":[],\"evidenceProblems\":[]}", 50, 100);
                }
                if (req.getTaskType() == com.writing.novel.llm.LlmTaskType.CHAPTER_WRITING) {
                    boolean repairing = req.getSystemPrompt().contains("【本次任务：基于原稿纠偏");
                    if (repairing) correction.set(req);
                    return new LlmResponse((repairing ? "修订稿：先出示官印，再作判断。" : "初稿：未出示官印，先作判断。").repeat(250), 100, 500);
                }
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
                    case CONTINUITY_CHECK -> new LlmResponse("未发现连续性问题。", 50, 50);
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
                null, new ConsistencyGuard(gateway, repo, promptSvc), null, null, null, null, null, null, null,
                new OrchestratorPolicies(null, null, null, null, null,
                        new QualityCouncil(), new Arbiter(), null, null, null, null));
    }

    @Test
    void firstGuardFindingMustReachCorrectionInsteadOfBeingReplacedByRescan() {
        Project p = projectService.create("测", "剑修", "玄幻");
        orchestrator.runConceiving(p.getId());
        orchestrator.step(p.getId());
        orchestrator.runWritingOneChapter(p.getId());
        assertTrue(repository.findChapterByIndex(p.getId(), 1).orElseThrow().getContent().contains("修订稿"),
                "第一次守卫已发现HIGH，不能因为重复扫描返回空而跳过纠偏");
        assertNotNull(correction.get());
        assertTrue(correction.get().getSystemPrompt().contains("未出示先使用的官印"));
        assertEquals(2, guardCalls.get(), "同一稿只检查一次，纠偏后的新稿再检查一次");
    }
}
