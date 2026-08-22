package com.writing.novel.service;

import com.writing.novel.domain.WritingTask;
import com.writing.novel.orchestrator.NovelOrchestrator;
import com.writing.novel.spi.NovelRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * p15-05: 单章任务单活动作业回归测试。
 * - 项目已有活动任务时 startWriteChapter 必须拒绝而非覆盖 owner
 * - finishWriteChapter 即使终态落库抛异常也必须条件清理自己的活动映射
 */
class WriteChapterTaskOwnershipTest {

    private NovelRepository mockRepo;
    private WritingTaskExecutor executor;

    @BeforeEach
    void setUp() {
        mockRepo = mock(NovelRepository.class);
        NovelOrchestrator mockOrchestrator = mock(NovelOrchestrator.class);
        when(mockRepo.findAllProjects()).thenReturn(java.util.List.of());
        executor = new WritingTaskExecutor(mockOrchestrator, mockRepo);
    }

    @Test
    void startWriteChapterRejectedWhenProjectAlreadyActive() {
        executor.beginWork("p1", "write-all");
        assertThrows(IllegalStateException.class, () -> executor.startWriteChapter("p1"));
        assertTrue(executor.hasActiveTask("p1"), "原 owner 应保留");
    }

    @Test
    void finishWriteChapterReleasesMappingEvenWhenPersistenceThrows() {
        WritingTask task = executor.startWriteChapter("p1");
        doThrow(new RuntimeException("db down")).when(mockRepo).saveWritingTask(any(WritingTask.class));
        assertThrows(RuntimeException.class,
                () -> executor.finishWriteChapter("p1", task, WritingTask.Status.COMPLETED, null));
        assertFalse(executor.hasActiveTask("p1"), "终态落库失败也应条件清理自己的活动映射");
    }
}
