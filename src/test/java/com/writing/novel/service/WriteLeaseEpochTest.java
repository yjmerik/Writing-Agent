package com.writing.novel.service;

import com.writing.novel.agent.ChapterWriterAgent;
import com.writing.novel.domain.Chapter;
import com.writing.novel.domain.Project;
import com.writing.novel.domain.WritingTask;
import com.writing.novel.llm.LlmGateway;
import com.writing.novel.llm.LlmRequest;
import com.writing.novel.llm.LlmResponse;
import com.writing.novel.memory.ContextAssembler;
import com.writing.novel.memory.WritingContext;
import com.writing.novel.persistence.sqlite.ConnectionProvider;
import com.writing.novel.persistence.sqlite.SqliteNovelRepository;
import com.writing.novel.persistence.sqlite.SqliteSchemaInitializer;
import com.writing.novel.persistence.sqlite.WriteLeaseRepository;
import com.writing.novel.telemetry.EventLog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * NCS-001（方案 §14/R08）：持久写入门禁与 epoch 租约。
 * 停止/取消任务递增持久化 epoch；晚到的 Agent 正文写入被拒且留事件、主稿不变；
 * 作者直接保存不受门禁阻碍；重启（新仓储实例）后 epoch 语义保持。
 */
class WriteLeaseEpochTest {

    @TempDir
    Path tempDir;

    private SqliteNovelRepository repo;
    private WriteLeaseRepository leaseRepo;
    private WriteLeaseService lease;
    private String projectId;
    private EventLog.Listener listener;
    private final List<String> events = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        var cp = new ConnectionProvider(tempDir.resolve("lease.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        repo = new SqliteNovelRepository(cp);
        leaseRepo = new WriteLeaseRepository(cp);
        lease = new WriteLeaseService(leaseRepo);
        Project project = new Project("租约项目", "构思", "武侠");
        repo.saveProject(project);
        projectId = project.getId();
        listener = e -> events.add(e.get("event").toString());
        EventLog.subscribe(listener);
    }

    @AfterEach
    void tearDown() {
        EventLog.unsubscribe(listener);
    }

    private boolean saw(String event) {
        return events.stream().anyMatch(event::equals);
    }

    /** RUNNING 任务模拟：任务捕获当前 epoch 后落库（与 WritingTaskExecutor 启动路径一致） */
    private WritingTask runningTask() {
        WritingTask task = new WritingTask(projectId, 3, WritingTask.Type.WRITE_ALL);
        task.setLeaseEpoch(lease.currentEpoch(projectId));
        repo.saveWritingTask(task);
        return task;
    }

    private ChapterWriterAgent writerWithLease() {
        LlmGateway gateway = new LlmGateway() {
            @Override public LlmResponse complete(LlmRequest req) {
                return new LlmResponse("晚到的模型正文，本不应再进入主稿。".repeat(200), 100, 500);
            }
            @Override public void streamComplete(LlmRequest req, com.writing.novel.llm.client.StreamHandler h) {}
        };
        ContextAssembler assembler = mock(ContextAssembler.class);
        when(assembler.assemble(any(), anyInt(), any(), any(), any(), anyInt()))
                .thenReturn(new WritingContext());
        var promptSvc = com.writing.novel.agent.TestPromptHelper.create();
        return new ChapterWriterAgent(gateway, assembler, repo, null, promptSvc, null, null, lease);
    }

    @Test
    void epoch持久化且单调递增_新实例重启语义保持() {
        assertEquals(0, lease.currentEpoch(projectId), "无租约行时 epoch 为 0");
        assertEquals(1, lease.bump(projectId, "cancel:test"));
        assertEquals(2, lease.bump(projectId, "stop-collaboration"));
        WriteLeaseRepository restarted = new WriteLeaseRepository(
                new ConnectionProvider(tempDir.resolve("lease.db").toString()));
        assertEquals(2, new WriteLeaseService(restarted).currentEpoch(projectId),
                "重启（新实例）后 epoch 不回退");
        assertTrue(saw("WRITE_LEASE_EPOCH_BUMPED"), "bump 必须留事件");
    }

    @Test
    void 取消任务递增epoch_晚到正文写入被拒_主稿不变() {
        WritingTaskExecutor executor = new WritingTaskExecutor(null, repo, null, lease);
        WritingTask task = runningTask();
        assertTrue(executor.cancel(task.getId()), "取消信号应被接受");
        assertTrue(lease.currentEpoch(projectId) >= 1, "取消必须递增持久 epoch");
        assertTrue(saw("WRITE_LEASE_EPOCH_BUMPED"));
        executor.shutdown();

        ChapterWriterAgent writer = writerWithLease();
        assertThrows(WriteLeaseStaleException.class,
                () -> writer.write(projectId, 1, "第一章", "主角入门", null),
                "epoch 失效后晚到的正文写入必须被拒");
        assertTrue(repo.findChapterByIndex(projectId, 1).isEmpty(), "拒绝时零写入");
        assertTrue(saw("LATE_WRITE_REJECTED"), "拒绝必须留事件");
    }

    @Test
    void epoch一致时正常写作通过() {
        runningTask();
        Chapter chapter = writerWithLease().write(projectId, 1, "第一章", "主角入门", null);
        assertTrue(chapter.getWordCount() > 0, "正常路径不受门禁影响");
        assertTrue(repo.findChapterByIndex(projectId, 1).isPresent());
    }

    @Test
    void 晚到纠偏重写返回null_保留原稿() {
        Chapter c1 = new Chapter(projectId, 1, "第一章");
        c1.setContent("作者已确认的原稿正文，必须保留。".repeat(80));
        repo.saveChapter(c1);
        runningTask();
        lease.bump(projectId, "cancel:test");
        String result = writerWithLease().rewriteForCorrection(
                projectId, 1, "第一章", "主角入门", null, "修正矛盾");
        assertNull(result, "epoch 失效后的纠偏重写不产生新内容");
        assertEquals("作者已确认的原稿正文，必须保留。".repeat(80),
                repo.findChapterByIndex(projectId, 1).orElseThrow().getContent(),
                "原稿不被晚到重写覆盖");
    }

    @Test
    void 作者直接保存不受租约门禁影响() {
        Chapter c1 = new Chapter(projectId, 1, "第一章");
        c1.setContent("初始正文，长度满足门槛要求。".repeat(40));
        repo.saveChapter(c1);
        runningTask();
        lease.bump(projectId, "stop-collaboration");
        ChapterRevisionService service = new ChapterRevisionService(repo);
        Chapter edited = service.edit(projectId, 1,
                repo.findChapterByIndex(projectId, 1).orElseThrow().getVersion(),
                "第一章", "作者接手后的编辑内容，必须可以保存。".repeat(60), "DIRECT_EDIT");
        assertTrue(edited.getContent().contains("作者接手后的编辑内容"),
                "作者直存是新的写入权威，不被旧 epoch 阻断");
    }

    @Test
    void 停止协作递增epoch() {
        WritingTaskExecutor executor = new WritingTaskExecutor(null, repo, null, lease);
        WritingTask task = runningTask();
        executor.beginWork(projectId, task.getId()); // 模拟该任务持项目工权
        try {
            executor.stopCollaborative(projectId);
            assertEquals(1, lease.currentEpoch(projectId), "停止协作（接手）必须递增 epoch 恰好一次");
            assertTrue(repo.findWritingTask(task.getId()).orElseThrow().isStopRequested());
        } finally {
            executor.endWork(projectId, task.getId());
            executor.shutdown();
        }
    }
}
