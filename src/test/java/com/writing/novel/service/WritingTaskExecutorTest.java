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

    @TempDir Path tempDir;
    private NovelRepository repo;
    private NovelOrchestrator orchestrator;
    private WritingTaskExecutor executor;
    private String pid;

    /** 测试专用 Error（JVM 不会特殊处理，等价于真实 OOM/LinkageError 的穿透路径） */
    static final class SimulatedThreadDeath extends Error {}

    @BeforeEach
    void setUp() {
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
    void tearDown() {
        executor.shutdown();
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
}
