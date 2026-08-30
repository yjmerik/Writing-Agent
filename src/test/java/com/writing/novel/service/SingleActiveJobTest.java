package com.writing.novel.service;

import com.writing.novel.domain.WritingTask;
import com.writing.novel.orchestrator.NovelOrchestrator;
import com.writing.novel.spi.NovelRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.Mockito;

import java.util.Optional;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * mc-p0-05 + p20-05: 单项目单活动作业收敛测试（owner-token 语义）。
 * p20-05 起：项目已有 live worker 时重复启动返回冲突（409 语义），
 * 不再取消旧任务/等待 30s/强制接管；owner 到达终态后才可启动新任务。
 */
class SingleActiveJobTest {

    private NovelRepository mockRepo;
    private NovelOrchestrator mockOrchestrator;

    @BeforeEach
    void setUp() {
        mockRepo = mock(NovelRepository.class);
        mockOrchestrator = mock(NovelOrchestrator.class);
    }

    @Test
    void startWritingWhileActiveRejectedInsteadOfTakeover() throws Exception {
        when(mockRepo.findAllProjects()).thenReturn(java.util.List.of());
        WritingTaskExecutor executor = new WritingTaskExecutor(mockOrchestrator, mockRepo);

        // 用闩锁让第一个任务的写作循环保持活动（不 sleep 编排并发）
        CountDownLatch loopEntered = new CountDownLatch(1);
        CountDownLatch releaseLoop = new CountDownLatch(1);
        when(mockOrchestrator.runWritingLoop(eq("p1"), anyInt(), any(), any())).thenAnswer(inv -> {
            loopEntered.countDown();
            releaseLoop.await(10, java.util.concurrent.TimeUnit.SECONDS);
            return 0;
        });

        WritingTask task1 = executor.startWriting("p1", 5);
        assertNotNull(task1);
        assertEquals(WritingTask.Status.RUNNING, task1.getStatus());
        assertTrue(loopEntered.await(5, java.util.concurrent.TimeUnit.SECONDS), "任务1应已进入写作循环");

        // 活动期重复启动 → 冲突拒绝，原 owner 保持
        assertThrows(IllegalStateException.class, () -> executor.startWriting("p1", 5),
                "活动期重复 startWriting 必须冲突拒绝，而非取消旧任务");
        assertTrue(executor.hasActiveTask("p1"), "原 owner 应保持");

        // 放行任务1到终态 → 工作权释放 → 新任务可启动
        releaseLoop.countDown();
        long deadline = System.currentTimeMillis() + 5000;
        while (executor.hasActiveTask("p1") && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertFalse(executor.hasActiveTask("p1"), "任务1终态后应释放工作权");

        when(mockOrchestrator.runWritingLoop(eq("p1"), anyInt(), any(), any())).thenReturn(0);
        WritingTask task2 = executor.startWriting("p1", 5);
        assertNotNull(task2);
        assertNotEquals(task1.getId(), task2.getId(), "终态后新任务应有不同 ID");
    }

    @Test
    void cancelOnlySetsMemoryFlagNotDb() {
        when(mockRepo.findAllProjects()).thenReturn(java.util.List.of());
        WritingTaskExecutor executor = new WritingTaskExecutor(mockOrchestrator, mockRepo);

        WritingTask task = new WritingTask("p1", 10);
        // cancel 只设内存标志
        executor.cancel(task.getId());
        assertTrue(executor.isCancelled(task.getId()));

        // DB 不应被 cancel 直接修改（循环退出后才写）
        verify(mockRepo, never()).saveWritingTask(argThat(t ->
            t.getStatus() == WritingTask.Status.CANCELLED));
    }
}
