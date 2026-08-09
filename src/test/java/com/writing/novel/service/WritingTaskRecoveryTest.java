package com.writing.novel.service;

import com.writing.novel.domain.WritingTask;
import com.writing.novel.spi.NovelRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.Mockito;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * mc-p0-02: 启动孤儿 RUNNING 任务清扫测试。
 * 验证 DB 存在 RUNNING 任务时，WritingTaskExecutor 构造后该任务收敛为 FAILED。
 */
class WritingTaskRecoveryTest {

    private NovelRepository mockRepo;
    private com.writing.novel.orchestrator.NovelOrchestrator mockOrchestrator;

    @BeforeEach
    void setUp() {
        mockRepo = Mockito.mock(NovelRepository.class);
        mockOrchestrator = Mockito.mock(com.writing.novel.orchestrator.NovelOrchestrator.class);
    }

    @Test
    void orphanedRunningTaskSweptToFailedOnStartup() {
        // 构造：项目有一个 RUNNING 任务（模拟进程崩溃后残留）
        com.writing.novel.domain.Project project = new com.writing.novel.domain.Project("test", "concept", "tags");
        WritingTask orphan = new WritingTask("test-project", 10);
        orphan.setStatus(WritingTask.Status.RUNNING);

        when(mockRepo.findAllProjects()).thenReturn(List.of(project));
        when(mockRepo.findActiveWritingTask(project.getId())).thenReturn(Optional.of(orphan));

        // 执行：构造 WritingTaskExecutor（触发清扫）
        new WritingTaskExecutor(mockOrchestrator, mockRepo);

        // 断言：孤儿任务被收敛为 FAILED
        verify(mockRepo).saveWritingTask(argThat(task ->
            task.getStatus() == WritingTask.Status.FAILED &&
            "进程未干净退出".equals(task.getErrorMessage())
        ));
    }

    @Test
    void noOrphanedTasksIsNoop() {
        // 无 RUNNING 任务时，启动不应出错
        when(mockRepo.findAllProjects()).thenReturn(List.of());
        new WritingTaskExecutor(mockOrchestrator, mockRepo);
        verify(mockRepo, never()).saveWritingTask(any());
    }

    @Test
    void completedTaskNotSwept() {
        // COMPLETED 任务不应被清扫
        com.writing.novel.domain.Project project = new com.writing.novel.domain.Project("test", "concept", "tags");
        WritingTask completed = new WritingTask("test-project", 10);
        completed.setStatus(WritingTask.Status.COMPLETED);

        when(mockRepo.findAllProjects()).thenReturn(List.of(project));
        when(mockRepo.findActiveWritingTask(project.getId())).thenReturn(Optional.of(completed));

        new WritingTaskExecutor(mockOrchestrator, mockRepo);

        // 不应修改已完成的任务
        assertEquals(WritingTask.Status.COMPLETED, completed.getStatus());
    }
}
