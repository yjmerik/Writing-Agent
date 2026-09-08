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
import com.writing.novel.domain.ChapterVersion;
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
import com.writing.novel.service.ClimaxDiversityTracker;
import com.writing.novel.service.EntityGraphService;
import com.writing.novel.telemetry.EventLog;
import com.writing.novel.spi.EmbeddingProvider;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * p32-02（体验报告 #2/P1）：自动重写必须经版本门。
 * 后处理自动重写期间作者保存了新稿时，自动稿被拒绝落库、作者稿逐字保留、
 * 发出 CHAPTER_AUTHOR_CONFLICT 事件；无冲突时自动稿正常落库。
 */
class AuthorDraftConflictProtectionTest {

    @TempDir
    Path tempDir;

    private static final List<Map<String, Object>> EVENTS = new CopyOnWriteArrayList<>();
    private static final EventLog.Listener LISTENER = record -> {
        if ("CHAPTER_AUTHOR_CONFLICT".equals(record.get("event"))) EVENTS.add(record);
    };

    @BeforeAll
    static void redirectEventLog() {
        new File("./data").mkdirs();
        System.setProperty(EventLog.PATH_PROPERTY, "./data/test-author-conflict-events.jsonl");
        EventLog.subscribe(LISTENER);
    }

    @AfterAll
    static void restoreEventLog() {
        EventLog.unsubscribe(LISTENER);
        System.clearProperty(EventLog.PATH_PROPERTY);
    }

    @BeforeEach
    @AfterEach
    void clearEvents() {
        EVENTS.clear();
    }

    private static LlmGateway silentGateway() {
        return new LlmGateway() {
            @Override
            public LlmResponse complete(LlmRequest req) {
                return new LlmResponse("ok", 1, 1);
            }

            @Override
            public void streamComplete(LlmRequest req, StreamHandler handler) {
            }
        };
    }

    private record Harness(SqliteNovelRepository repo, NovelOrchestrator orchestrator) {}

    private Harness harness() {
        ConnectionProvider cp = new ConnectionProvider(
                tempDir.resolve("conflict-" + UUID.randomUUID() + ".db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        SqliteNovelRepository repo = new SqliteNovelRepository(cp);
        EmbeddingProvider emb = new EmbeddingProvider() {
            @Override
            public float[] embed(String text) {
                return new float[]{1.0f};
            }

            @Override
            public int dimension() {
                return 1;
            }
        };
        EmbeddedVectorStore store = new EmbeddedVectorStore(
                tempDir.resolve("vecs-" + UUID.randomUUID() + ".idx").toString());
        VectorRetrievalService vectorSvc = new VectorRetrievalService(store, emb);
        KnowledgeBaseService kbSvc = new KnowledgeBaseService(repo, vectorSvc);
        SummaryService summarySvc = new SummaryService(repo);
        EntityGraphService entityGraph = new EntityGraphService(repo, null);
        ContextAssembler assembler = new ContextAssembler(repo, kbSvc, summarySvc, vectorSvc,
                entityGraph, new ClimaxDiversityTracker(), null, null, null);
        LlmGateway gateway = silentGateway();
        var promptSvc = TestPromptHelper.create();
        NovelOrchestrator orchestrator = new NovelOrchestrator(repo, new ProjectService(repo),
                new WorldBuilderAgent(gateway, kbSvc, promptSvc),
                new CharacterDesignerAgent(gateway, kbSvc, promptSvc),
                new PlotterAgent(gateway, repo, promptSvc),
                new ChapterWriterAgent(gateway, assembler, repo, vectorSvc, promptSvc, null),
                new ContinuityCheckerAgent(gateway, repo, promptSvc),
                new StyleGuardianAgent(gateway, repo, promptSvc),
                new MemoryManagerAgent(gateway, summarySvc, promptSvc),
                summarySvc, assembler,
                // 参数 12-35（质量评估/实体图/升级器/追踪器/门禁等）：本测试只依赖版本门，全部置空
                null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null,
                OrchestratorPolicies.none());
        return new Harness(repo, orchestrator);
    }

    private Chapter chapterWithContent(SqliteNovelRepository repo, String projectId,
                                       int index, String content) {
        Chapter chapter = new Chapter(projectId, index, "第" + index + "章");
        chapter.setContent(content);
        chapter.setStatus(Chapter.Status.POST_PROCESSING);
        repo.saveChapter(chapter);
        return repo.findChapterByIndex(projectId, index).orElseThrow();
    }

    @Test
    void 自动重写期间作者保存新稿则拒绝覆盖并保留作者稿() {
        Harness h = harness();
        String projectId = h.repo().saveProject(
                new com.writing.novel.domain.Project("冲突保护", "概念", "现实"));
        Chapter autoBase = chapterWithContent(h.repo(), projectId, 1, "机器初稿。".repeat(80));
        int revisionBase = autoBase.getVersion();

        // 模拟后处理读稿→LLM 重写（耗时）期间，作者通过保存链路提交新稿
        Chapter author = h.repo().findChapterByIndex(projectId, 1).orElseThrow();
        ChapterVersion snapshot = new ChapterVersion(UUID.randomUUID().toString(), projectId, 1,
                author.getVersion(), author.getTitle(), author.getContent(),
                "DIRECT_EDIT", java.time.Instant.now());
        author.setContent("作者逐字校订稿。".repeat(80));
        author.setStatus(Chapter.Status.DONE);
        assertTrue(h.repo().saveChapterRevision(snapshot, author, revisionBase));

        // 自动重写尝试落库：应被版本门拒绝
        boolean applied = h.orchestrator().autoRewriteIfCurrent(projectId, 1, revisionBase,
                "第1章", "机器重写稿。".repeat(80), "REWRITE");

        assertFalse(applied, "作者已保存新稿时自动重写不得覆盖");
        Chapter stored = h.repo().findChapterByIndex(projectId, 1).orElseThrow();
        assertEquals("作者逐字校订稿。".repeat(80), stored.getContent(), "作者稿必须逐字保留");
        assertTrue(EVENTS.stream().anyMatch(e -> projectId.equals(e.get("projectId"))
                        && Integer.valueOf(1).equals(e.get("chapterIndex"))),
                "应发出 CHAPTER_AUTHOR_CONFLICT 事件，实际: " + EVENTS);
    }

    @Test
    void 无冲突时自动重写正常落库() {
        Harness h = harness();
        String projectId = h.repo().saveProject(
                new com.writing.novel.domain.Project("正常重写", "概念", "现实"));
        Chapter autoBase = chapterWithContent(h.repo(), projectId, 1, "机器初稿。".repeat(80));

        boolean applied = h.orchestrator().autoRewriteIfCurrent(projectId, 1,
                autoBase.getVersion(), "第1章", "机器重写稿。".repeat(80), "REWRITE");

        assertTrue(applied);
        Chapter stored = h.repo().findChapterByIndex(projectId, 1).orElseThrow();
        assertEquals("机器重写稿。".repeat(80), stored.getContent());
        assertEquals(autoBase.getVersion() + 1, stored.getVersion());
        assertTrue(EVENTS.isEmpty(), "无冲突不应发出事件: " + EVENTS);
    }

    @Test
    void 状态标记写入带版本门拒绝陈旧对象覆盖() {
        Harness h = harness();
        String projectId = h.repo().saveProject(
                new com.writing.novel.domain.Project("状态标记", "概念", "现实"));
        Chapter autoBase = chapterWithContent(h.repo(), projectId, 1, "机器初稿。".repeat(80));

        // 作者经保存链路改了正文（版本+1）
        Chapter author = h.repo().findChapterByIndex(projectId, 1).orElseThrow();
        ChapterVersion snapshot = new ChapterVersion(UUID.randomUUID().toString(), projectId, 1,
                author.getVersion(), author.getTitle(), author.getContent(),
                "DIRECT_EDIT", java.time.Instant.now());
        author.setContent("作者新稿。".repeat(80));
        author.setStatus(Chapter.Status.DONE);
        assertTrue(h.repo().saveChapterRevision(snapshot, author, autoBase.getVersion()));

        // 陈旧内存对象上的状态标记不得整行覆盖作者内容
        boolean updated = h.repo().updateChapterStatusIfVersion(projectId, 1,
                Chapter.Status.NEEDS_MANUAL_REVIEW, autoBase.getVersion());

        assertFalse(updated, "版本已变化时状态标记应失败");
        Chapter stored = h.repo().findChapterByIndex(projectId, 1).orElseThrow();
        assertEquals("作者新稿。".repeat(80), stored.getContent());
        assertEquals(Chapter.Status.DONE, stored.getStatus(), "不应被陈旧状态覆盖");

        // 对当前版本的状态标记正常生效
        assertTrue(h.repo().updateChapterStatusIfVersion(projectId, 1,
                Chapter.Status.NEEDS_MANUAL_REVIEW, stored.getVersion()));
        assertEquals(Chapter.Status.NEEDS_MANUAL_REVIEW,
                h.repo().findChapterByIndex(projectId, 1).orElseThrow().getStatus());
    }
}
