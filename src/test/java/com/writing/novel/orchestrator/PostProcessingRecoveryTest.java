package com.writing.novel.orchestrator;

import com.writing.novel.agent.CharacterDesignerAgent;
import com.writing.novel.agent.ChapterWriterAgent;
import com.writing.novel.agent.ContinuityCheckerAgent;
import com.writing.novel.agent.FactHarvesterAgent;
import com.writing.novel.agent.MemoryManagerAgent;
import com.writing.novel.agent.PlotterAgent;
import com.writing.novel.agent.StyleGuardianAgent;
import com.writing.novel.agent.TestPromptHelper;
import com.writing.novel.agent.WorldBuilderAgent;
import com.writing.novel.domain.Chapter;
import com.writing.novel.domain.Project;
import com.writing.novel.llm.LlmGateway;
import com.writing.novel.llm.LlmRequest;
import com.writing.novel.llm.LlmResponse;
import com.writing.novel.llm.LlmTaskType;
import com.writing.novel.llm.client.StreamHandler;
import com.writing.novel.memory.ContextAssembler;
import com.writing.novel.memory.KnowledgeBaseService;
import com.writing.novel.memory.SummaryService;
import com.writing.novel.memory.VectorRetrievalService;
import com.writing.novel.persistence.embedding.EmbeddedVectorStore;
import com.writing.novel.persistence.sqlite.ConnectionProvider;
import com.writing.novel.persistence.sqlite.PostProcessLedgerRepository;
import com.writing.novel.persistence.sqlite.SqliteNovelRepository;
import com.writing.novel.persistence.sqlite.SqliteSchemaInitializer;
import com.writing.novel.service.EntityGraphService;
import com.writing.novel.spi.EmbeddingProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * p20-04: 重启续跑未完成的后处理。
 *
 * 故障注入：ENTITY_GRAPH 阶段崩溃（Error 逃逸阶段内 catch，模拟进程死亡）。
 * 在同一 SQLite 上重建 Spring 上下文后恢复：
 * - 只补跑未完成阶段（CONTINUITY 不重跑：0 次 CONTINUITY_CHECK LLM 调用）
 * - 无重复事实/摘要（收割恰好 1 次，0 次 MEMORY_SUMMARY）
 * - 终态 DONE，账本全部 COMPLETED
 */
class PostProcessingRecoveryTest {

    @TempDir Path tempDir;
    private String dbPath;
    private String idxPath;

    /** 按任务类型计数的桩网关 */
    static class CountingGateway implements LlmGateway {
        final Map<String, AtomicInteger> callsByTask = new ConcurrentHashMap<>();

        CountingGateway() {}

        private void count(LlmRequest req) {
            callsByTask.computeIfAbsent(req.getTaskType().name(), k -> new AtomicInteger()).incrementAndGet();
        }

        int calls(String taskType) {
            return callsByTask.getOrDefault(taskType, new AtomicInteger()).get();
        }

        @Override
        public LlmResponse complete(LlmRequest req) {
            count(req);
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
                case CONTINUITY_CHECK -> new LlmResponse("未发现连续性问题。", 10, 10);
                case MEMORY_SUMMARY -> new LlmResponse("章节摘要：张三的修炼进展。", 50, 50);
                default -> new LlmResponse("ok", 1, 1);
            };
        }

        @Override
        public void streamComplete(LlmRequest req, StreamHandler h) {}
    }

    /** 可在 armed 时崩溃（Error 级，逃逸 stage 内 Exception catch）的实体图服务 */
    static class CrashableEntityGraphService extends EntityGraphService {
        private final AtomicInteger extracts = new AtomicInteger();
        private volatile boolean armed;

        CrashableEntityGraphService(com.writing.novel.spi.NovelRepository repo) {
            super(repo, null);
        }

        int extracts() { return extracts.get(); }

        void arm(boolean on) { this.armed = on; }

        @Override
        public String recallConsistencySection(String projectId, int currentChapter) { return ""; }

        @Override
        public List<String> getRecentEntityNames(String projectId, int currentChapter) { return List.of(); }

        @Override
        public String resolveCanonical(String projectId, String rawName) { return rawName; }

        @Override
        public com.writing.novel.agent.EntityExtractorAgent.ExtractionResult extractFromChapter(
                String projectId, com.writing.novel.domain.Chapter chapter) {
            extracts.incrementAndGet();
            if (armed) {
                throw new AssertionError("注入的进程崩溃");
            }
            return new com.writing.novel.agent.EntityExtractorAgent.ExtractionResult(0, 0, 0, null);
        }
    }

    /** 计数收割 Agent（gateway 返回空数组 → 不产生事实，计数即执行次数） */
    static class CountingFactHarvester extends FactHarvesterAgent {
        private final AtomicInteger harvests = new AtomicInteger();

        CountingFactHarvester(LlmGateway gw, com.writing.novel.persistence.sqlite.StoryFactRepository factRepo) {
            super(gw, new com.writing.novel.persistence.sqlite.SqliteStoryCanon(factRepo), TestPromptHelper.create());
        }

        int harvests() { return harvests.get(); }

        @Override
        public HarvestResult harvest(String projectId, Chapter chapter) {
            harvests.incrementAndGet();
            return new HarvestResult(0, 0, 0);
        }
    }

    @BeforeEach
    void setUp() {
        dbPath = tempDir.resolve("recover.db").toString();
        idxPath = tempDir.resolve("recover.idx").toString();
    }

    private record Ctx(SqliteNovelRepository repo, NovelOrchestrator orchestrator,
                       ProjectService projectService, PostProcessLedgerRepository ledger,
                       CrashableEntityGraphService entityGraph, CountingGateway gateway,
                       CountingFactHarvester factHarvester) {}

    private Ctx buildContext() {
        ConnectionProvider cp = new ConnectionProvider(dbPath);
        new SqliteSchemaInitializer(cp).initialize();
        SqliteNovelRepository repo = new SqliteNovelRepository(cp);

        EmbeddingProvider emb = new EmbeddingProvider() {
            @Override public float[] embed(String text) { return new float[]{1.0f}; }
            @Override public int dimension() { return 1; }
        };
        var store = new EmbeddedVectorStore(idxPath);
        VectorRetrievalService vectorSvc = new VectorRetrievalService(store, emb);
        KnowledgeBaseService kbSvc = new KnowledgeBaseService(repo, vectorSvc);
        SummaryService summarySvc = new SummaryService(repo);
        CrashableEntityGraphService entityGraph = new CrashableEntityGraphService(repo);
        ContextAssembler assembler = new ContextAssembler(repo, kbSvc, summarySvc, vectorSvc,
                entityGraph, new com.writing.novel.service.ClimaxDiversityTracker(), null, null, null);

        CountingGateway gateway = new CountingGateway();
        var factRepo = new com.writing.novel.persistence.sqlite.StoryFactRepository(cp);
        CountingFactHarvester factHarvester = new CountingFactHarvester(
                mockGatewayReturning("[]"), factRepo);

        ProjectService projectService = new ProjectService(repo);
        var promptSvc = TestPromptHelper.create();
        PostProcessLedgerRepository ledger = new PostProcessLedgerRepository(cp);
        NovelOrchestrator orchestrator = new NovelOrchestrator(repo, projectService,
                new WorldBuilderAgent(gateway, kbSvc, promptSvc),
                new CharacterDesignerAgent(gateway, kbSvc, promptSvc),
                new PlotterAgent(gateway, repo, promptSvc),
                new ChapterWriterAgent(gateway, assembler, repo, vectorSvc, promptSvc, null),
                new ContinuityCheckerAgent(gateway, repo, promptSvc),
                new StyleGuardianAgent(gateway, repo, promptSvc),
                new MemoryManagerAgent(gateway, summarySvc, promptSvc),
                summarySvc, assembler,
                new com.writing.novel.agent.QualityEvaluationAgent(gateway, repo, promptSvc),
                entityGraph,
                null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null,
                // p22-05: ledger 与收割桩经策略包组合期注入（原后置 setter）
                new OrchestratorPolicies(null, null, null, null, null, null, null,
                        factHarvester, null, null, ledger));
        return new Ctx(repo, orchestrator, projectService, ledger, entityGraph, gateway, factHarvester);
    }

    private static LlmGateway mockGatewayReturning(String content) {
        return new LlmGateway() {
            @Override public LlmResponse complete(LlmRequest req) { return new LlmResponse(content, 10, 10); }
            @Override public void streamComplete(LlmRequest req, StreamHandler h) {}
        };
    }

    @Test
    void 崩溃后同库重建上下文只补跑未完成阶段() {
        // —— 第一次上下文：写第1章，ENTITY_GRAPH 阶段崩溃 ——
        Ctx first = buildContext();
        Project p = first.projectService().create("恢复测试", "剑修故事", "玄幻");
        first.orchestrator().runConceiving(p.getId());
        first.orchestrator().step(p.getId());
        String pid = p.getId();

        first.entityGraph().arm(true);
        assertThrows(AssertionError.class, () -> first.orchestrator().runWritingOneChapter(pid),
                "注入的崩溃应逃逸到调用方（模拟进程死亡）");

        // 崩溃现场：章节停在 POST_PROCESSING，CONTINUITY 已完成，ENTITY_GRAPH 中断于 RUNNING
        assertEquals(Chapter.Status.POST_PROCESSING,
                first.repo().findChapterByIndex(pid, 1).orElseThrow().getStatus());
        assertEquals(PostProcessLedgerRepository.StageStatus.COMPLETED,
                first.ledger().statusOf(pid, 1, ChapterPostProcessor.Stage.CONTINUITY).orElse(null));
        assertEquals(PostProcessLedgerRepository.StageStatus.RUNNING,
                first.ledger().statusOf(pid, 1, ChapterPostProcessor.Stage.ENTITY_GRAPH).orElse(null));

        // —— 第二次上下文：同一 DB 重建，启动清扫恢复 ——
        Ctx second = buildContext();
        int resumed = second.orchestrator().resumeAllInterruptedPostProcessing();
        assertEquals(1, resumed, "应恢复恰好 1 个中断章节");

        // 终态：章节 DONE，账本全部 COMPLETED
        assertEquals(Chapter.Status.DONE,
                second.repo().findChapterByIndex(pid, 1).orElseThrow().getStatus());
        assertTrue(second.ledger().allCompleted(pid, 1), "续跑后账本应全部 COMPLETED");

        // 只补跑未完成阶段：
        assertEquals(1, second.entityGraph().extracts(),
                "ENTITY_GRAPH（中断点）应恰好补跑 1 次");
        assertEquals(1, second.factHarvester().harvests(),
                "FACT_HARVEST（PENDING）应补跑 1 次且不重复");
        assertEquals(0, second.gateway().calls(LlmTaskType.CONTINUITY_CHECK.name()),
                "CONTINUITY 已完成，续跑不得重跑（0 次 LLM 调用）");
        assertEquals(0, second.gateway().calls(LlmTaskType.MEMORY_SUMMARY.name()),
                "第1章无摘要计划，续跑不得产生重复摘要");
    }
}
