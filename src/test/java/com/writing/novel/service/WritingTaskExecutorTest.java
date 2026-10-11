package com.writing.novel.service;

import com.writing.novel.domain.NovelState;
import com.writing.novel.domain.Project;
import com.writing.novel.domain.WritingTask;
import com.writing.novel.orchestrator.NovelOrchestrator;
import com.writing.novel.persistence.sqlite.ConnectionProvider;
import com.writing.novel.persistence.sqlite.SqliteNovelRepository;
import com.writing.novel.persistence.sqlite.SqliteSchemaInitializer;
import com.writing.novel.spi.NovelRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * NCS-166（走查 incident A）：工作线程静默死亡的可观测与自愈——
 * 线程体此前只 catch Exception，Error 穿透 → 线程死亡、任务永远 RUNNING、
 * 工作权泄漏（NCS-141 接管仅在有人再请求时触发，用户视角=无限转圈）。
 * 修复语义：线程出口 Throwable 兜底——无论何种退出都必须落任务终态
 * （FAILED）+ 释放工作权 + 发 TASK_THREAD_DIED 事件。
 */
class WritingTaskExecutorTest {

    /** NCS-166: 不用 @TempDir——worker 线程的 SQLite 连接池可能晚于测试释放文件锁，
     *  JUnit 的强制清理会与全量合跑产生删除竞态；改用 target/ 下独立目录 + 尽力清理 */
    private Path tempDir;
    private NovelRepository repo;
    private NovelOrchestrator orchestrator;
    private WritingTaskExecutor executor;
    private String pid;

    /** 测试专用 Error（JVM 不会特殊处理，等价于真实 OOM/LinkageError 的穿透路径） */
    static final class SimulatedThreadDeath extends Error {}

    @BeforeEach
    void setUp() throws Exception {
        tempDir = java.nio.file.Files.createTempDirectory(
                Path.of("target"), "wte-");
        var cp = new ConnectionProvider(tempDir.resolve("wte.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        repo = new SqliteNovelRepository(cp);
        orchestrator = mock(NovelOrchestrator.class);
        executor = new WritingTaskExecutor(orchestrator, repo);

        Project p = new Project("线程死亡项目", "构思", "悬疑");
        repo.saveProject(p);
        pid = p.getId();
    }

    @AfterEach
    void tearDown() throws Exception {
        executor.shutdown();
        // 尽力清理（失败不误报——文件锁可能由已退出的 worker 线程延迟释放）
        try (var walk = java.nio.file.Files.walk(tempDir)) {
            walk.sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> { try { java.nio.file.Files.deleteIfExists(p); } catch (Exception ignored) {} });
        } catch (Exception ignored) {}
    }

    private boolean taskTerminal(String taskId) {
        Optional<WritingTask> t = repo.findWritingTasksByProject(pid).stream()
                .filter(x -> x.getId().equals(taskId)).findFirst();
        return t.isPresent() && t.get().getStatus() != WritingTask.Status.RUNNING;
    }

    @Test
    void 构思线程抛Error时任务落FAILED且工作权释放() throws Exception {
        org.mockito.Mockito.doThrow(new SimulatedThreadDeath()).when(orchestrator).step(pid);

        WritingTask task = executor.startConceiving(pid);
        String taskId = task.getId();

        for (int i = 0; i < 100 && !taskTerminal(taskId); i++) {
            TimeUnit.MILLISECONDS.sleep(50);
        }
        assertTrue(taskTerminal(taskId), "线程死亡后任务必须落终态（不得永久 RUNNING）");
        WritingTask after = repo.findWritingTasksByProject(pid).stream()
                .filter(x -> x.getId().equals(taskId)).findFirst().orElseThrow();
        assertEquals(WritingTask.Status.FAILED, after.getStatus(),
                "Error 穿透路径必须记 FAILED: " + after.getStatus());
        assertNotEquals(null, after.getErrorMessage());

        // 自愈：工作权已释放——新任务可立即启动（NCS-141 接管不再依赖后续请求兜底）
        org.mockito.Mockito.doAnswer(inv -> null).when(orchestrator).step(pid);
        WritingTask second = executor.startConceiving(pid);
        assertEquals(WritingTask.Status.RUNNING, second.getStatus(), "新任务应能立即获得工作权");
    }

    @Test
    void 正常完成路径不受Throwable兜底影响() throws Exception {
        org.mockito.Mockito.doAnswer(inv -> {
            Project p = repo.findProject(pid).orElseThrow();
            p.setState(NovelState.PAUSED_AT_CHECKPOINT);
            repo.saveProject(p);
            return null;
        }).when(orchestrator).step(pid);

        WritingTask task = executor.startConceiving(pid);
        for (int i = 0; i < 100 && !taskTerminal(task.getId()); i++) {
            TimeUnit.MILLISECONDS.sleep(50);
        }
        assertEquals(WritingTask.Status.COMPLETED, task.getStatus());
    }

    /** NCS-171: 协作任务（批准后自动恢复路径）的生命周期日志必须在 app.log 可见 */
    @org.junit.jupiter.api.Test
    void 协作任务生命周期日志_启动与终态可见() throws Exception {
        var logger = (ch.qos.logback.classic.Logger)
                org.slf4j.LoggerFactory.getLogger(WritingTaskExecutor.class);
        var listAppender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        listAppender.start();
        logger.addAppender(listAppender);
        try {
            // WRITING 态 + runWritingLoop 返回 1 → 协作循环自然结束 → PAUSED（创作已停下）
            Project p = repo.findProject(pid).orElseThrow();
            p.setState(NovelState.WRITING);
            repo.saveProject(p);
            org.mockito.Mockito.when(orchestrator.runWritingLoop(
                    org.mockito.ArgumentMatchers.eq(pid), org.mockito.ArgumentMatchers.anyInt(),
                    org.mockito.ArgumentMatchers.any(java.util.function.BooleanSupplier.class),
                    org.mockito.ArgumentMatchers.any()))
                    .thenReturn(1);

            WritingTask task = executor.startCollaborative(pid, null);
            for (int i = 0; i < 100 && !taskTerminal(task.getId()); i++) {
                TimeUnit.MILLISECONDS.sleep(50);
            }
            assertTrue(taskTerminal(task.getId()), "协作任务应落终态");

            var messages = listAppender.list.stream()
                    .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                    .toList();
            assertTrue(messages.stream().anyMatch(m -> m.contains("写作任务开始(协作)")),
                    "协作任务启动必须落日志（18095 验收轮缺失项）: " + messages);
            assertTrue(messages.stream().anyMatch(m -> m.contains("写作任务终态(协作)")),
                    "协作任务终态必须落日志: " + messages);
        } finally {
            logger.detachAppender(listAppender);
        }
    }
}
