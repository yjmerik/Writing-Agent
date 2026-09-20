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
    protected NovelOrchestrator orchestrator;
    protected ProjectService projectService;
    private final AtomicInteger guardCalls = new AtomicInteger();
    protected com.writing.novel.spi.NovelRepository repository;
    private final java.util.concurrent.atomic.AtomicReference<LlmRequest> correction = new java.util.concurrent.atomic.AtomicReference<>();

    protected com.writing.novel.persistence.sqlite.CheckResultRepository checkResults;
    protected String failureMode = "";
    protected final AtomicInteger verificationCalls = new AtomicInteger();
    private final AtomicInteger continuityCalls = new AtomicInteger();
    private final AtomicInteger readerCalls = new AtomicInteger();

    @BeforeEach
    void setUp() {
        ConnectionProvider cp = new ConnectionProvider(tempDir.resolve("arb.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        var repo = new SqliteNovelRepository(cp);
        repository = repo;
        checkResults = new com.writing.novel.persistence.sqlite.CheckResultRepository(cp);
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
                if (req.getSystemPrompt().startsWith("你是纠偏复验员")) {
                    verificationCalls.incrementAndGet();
                    try {
                        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                        var input = mapper.readTree(req.getUserMessage());
                        var output = mapper.createObjectNode();
                        output.put("chapterVersion", input.path("chapterVersion").asInt()
                                - (failureMode.equals("wrong-version") ? 1 : 0));
                        var results = output.putArray("results");
                        if (!failureMode.equals("missing-finding")) {
                            for (var finding : input.path("findings")) {
                                var result = results.addObject();
                                result.put("issueId", finding.path("issueId").asText());
                                result.put("status", failureMode.equals("unresolved") ? "UNRESOLVED"
                                        : failureMode.equals("unknown") ? "UNKNOWN" : "RESOLVED");
                                result.put("evidence", failureMode.equals("invented-evidence")
                                        ? "正文中不存在的证据" : "先出示官印，再作判断。");
                                result.put("reason", "补充了出示官印的顺序");
                            }
                        }
                        if (failureMode.equals("duplicate-finding")) results.add(results.get(0).deepCopy());
                        return new LlmResponse(output.toString(), 10, 20);
                    } catch (Exception e) { throw new RuntimeException(e); }
                }
                if (req.getSystemPrompt().startsWith("你是一位资深网络小说读者")) {
                    int call = readerCalls.incrementAndGet();
                    if (failureMode.equals("reader-invalid")) return new LlmResponse("{}", 10, 10);
                    int score = failureMode.equals("reader-recheck-low") && call > 1 ? 4 : 8;
                    return new LlmResponse("{\"scores\":{\"engagement\":8,\"coherence\":8,\"character\":8,\"hook\":8},\"overall\":"
                            + score + ",\"summary\":\"阅读评价\",\"improvement\":\"补足证据\"}", 10, 20);
                }
                if (req.getSystemPrompt().startsWith("你是小说一致性审查专家")) {
                    boolean first = guardCalls.incrementAndGet() == 1;
                    if (first && failureMode.equals("guard-exception")) throw new IllegalStateException("模拟检查传输错误");
                    if (first && failureMode.equals("guard-invalid")) return new LlmResponse("{}", 10, 10);
                    return new LlmResponse(first
                            ? "{\"abilityViolations\":[],\"evidenceProblems\":[{\"evidence\":\"未出示先使用的官印\",\"reason\":\"必须补足获取过程\"}],\"anachronisms\":[]}"
                            : "{\"abilityViolations\":[],\"evidenceProblems\":[],\"anachronisms\":[]}", 50, 100);
                }
                if (req.getTaskType() == com.writing.novel.llm.LlmTaskType.CHAPTER_WRITING) {
                    boolean repairing = req.getSystemPrompt().contains("【本次任务：基于原稿纠偏");
                    if (repairing) correction.set(req);
                    if (repairing && failureMode.equals("correction-exception")) throw new IllegalStateException("模拟纠偏错误");
                    if (repairing && failureMode.equals("correction-empty")) return new LlmResponse("", 10, 10);
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
                    case CONTINUITY_CHECK -> {
                        int call = continuityCalls.incrementAndGet();
                        if (call > 1 && failureMode.equals("recheck-exception")) throw new IllegalStateException("模拟复检失败");
                        yield new LlmResponse(failureMode.equals("continuity-invalid") ? "无法判断" : "未发现连续性问题。", 50, 50);
                    }
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
                null, null, null, null, null, null, null, null, null, null, new ReaderReviewAgent(gateway, promptSvc), null,
                new SceneClassifier(gateway, promptSvc),
                new com.writing.novel.service.DiversityTracker(),
                null, new ConsistencyGuard(gateway, repo, promptSvc, null, checkResults),
                new com.writing.novel.service.ConsistencyGate(repo) {
                    @Override public java.util.List<com.writing.novel.domain.ContinuityIssue> check(
                            String id, int index, String content, com.writing.novel.domain.GenreProfile genre) {
                        if (failureMode.equals("rule-exception")) throw new IllegalStateException("规则检查失败");
                        if (failureMode.equals("rule-high")) return java.util.List.of(new com.writing.novel.domain.ContinuityIssue(
                                id, index, com.writing.novel.domain.ContinuityIssue.Type.WORLDBUILDING,
                                com.writing.novel.domain.ContinuityIssue.Severity.HIGH, "规则发现严重时代错误"));
                        return java.util.List.of();
                    }
                }, null, null, null, null, null, null,
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
