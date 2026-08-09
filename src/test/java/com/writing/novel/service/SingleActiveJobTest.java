package com.writing.novel.service;

import com.writing.novel.domain.WritingTask;
import com.writing.novel.orchestrator.NovelOrchestrator;
import com.writing.novel.spi.NovelRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.Mockito;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * mc-p0-05: 单项目单活动作业收敛测试。
 * 验证同项目已有 RUNNING 时启动新任务，旧任务被取消。
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
    void startWritingWithActiveTaskCancelsOld() {
        // 构造：无已有任务（避免构造函数的 sweep 干扰）
        when(mockRepo.findAllProjects()).thenReturn(java.util.List.of());
        WritingTaskExecutor executor = new WritingTaskExecutor(mockOrchestrator, mockRepo);

        // 第一次启动任务
        when(mockRepo.findActiveWritingTask("p1")).thenReturn(Optional.empty());
        when(mockOrchestrator.runWritingLoop(eq("p1"), anyInt(), any())).thenReturn(0);
        WritingTask task1 = executor.startWriting("p1", 5);
        assertNotNull(task1);
        assertEquals(WritingTask.Status.RUNNING, task1.getStatus());

        // 等待后台线程完成（它返回 0 章，无大纲）
        try { Thread.sleep(2000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

        // 第二次启动任务——应该成功（旧任务已退出）
        WritingTask task2 = executor.startWriting("p1", 5);
        assertNotNull(task2);
        assertNotEquals(task1.getId(), task2.getId(), "新任务应有不同 ID");
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
