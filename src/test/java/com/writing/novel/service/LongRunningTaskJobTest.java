package com.writing.novel.service;

import com.writing.novel.orchestrator.NovelOrchestrator;
import com.writing.novel.spi.NovelRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * NCS-094：同步长事务（修订预览/重检）异步化——HTTP 立即返回 jobId，
 * 工作在工作线程执行并占用项目工作权（与同步入口互斥），轮询取结果。
 */
class LongRunningTaskJobTest {

    @TempDir Path tempDir;
    private WritingTaskExecutor executor;
    private LongTaskService service;

    @BeforeEach
    void setUp() {
        com.writing.novel.persistence.sqlite.ConnectionProvider cp =
                new com.writing.novel.persistence.sqlite.ConnectionProvider(
                        tempDir.resolve("long-task.db").toString());
        new com.writing.novel.persistence.sqlite.SqliteSchemaInitializer(cp).initialize();
        NovelRepository repo = new com.writing.novel.persistence.sqlite.SqliteNovelRepository(cp);
        executor = new WritingTaskExecutor(mock(NovelOrchestrator.class), repo);
        service = new LongTaskService(executor);
    }

    private void awaitTerminal(String pid, String jobId) throws Exception {
        for (int i = 0; i < 100; i++) {
            var state = service.get(pid, jobId);
            if (state.isPresent() && !"RUNNING".equals(state.get().status())) return;
            TimeUnit.MILLISECONDS.sleep(20);
        }
    }

    @Test
    void 成功任务DONE并携带结果() throws Exception {
        String jobId = service.submit("p-1", "recheck", "补查", () -> "报告");
        awaitTerminal("p-1", jobId);
        var state = service.get("p-1", jobId).orElseThrow();
        assertEquals("DONE", state.status());
        assertEquals("报告", state.result());
        assertTrue(state.finishedAt() != null);
    }

    @Test
    void 失败任务FAILED并携带错误() throws Exception {
        String jobId = service.submit("p-1", "agent-revision-preview", "候选生成", () -> {
            throw new IllegalStateException("输出被截断——请缩小修改范围");
        });
        awaitTerminal("p-1", jobId);
        var state = service.get("p-1", jobId).orElseThrow();
        assertEquals("FAILED", state.status());
        assertTrue(state.error().contains("截断"));
    }

    @Test
    void 任务占用项目工作权完成后释放() throws Exception {
        String pid = "p-work";
        CountDownLatch release = new CountDownLatch(1);
        String jobId = service.submit(pid, "recheck", "补查", () -> {
            try {
                assertTrue(release.await(3, TimeUnit.SECONDS));
            } catch (InterruptedException ignored) {
            }
            return "ok";
        });
        // submit 返回前 beginWork 已登记 → 运行中工作权被占用
        assertTrue(executor.activeSyncWork(pid).isPresent(), "运行中占用项目工作权");
        release.countDown();
        awaitTerminal(pid, jobId);
        assertTrue(executor.activeSyncWork(pid).isEmpty(), "任务完成后工作权释放");
        assertEquals("DONE", service.get(pid, jobId).orElseThrow().status());
    }

    @Test
    void 项目隔离与未知任务() {
        assertTrue(service.get("p-x", "不存在").isEmpty());
        String jobId = service.submit("p-a", "recheck", "补查", () -> "r");
        assertTrue(service.get("p-b", jobId).isEmpty(), "项目不匹配不可见");
        assertTrue(service.get("p-a", jobId).isPresent());
    }
}
