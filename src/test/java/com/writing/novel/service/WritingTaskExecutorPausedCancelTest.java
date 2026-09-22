package com.writing.novel.service;

import com.writing.novel.domain.WritingTask;
import com.writing.novel.orchestrator.NovelOrchestrator;
import com.writing.novel.spi.NovelRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * NCS-072: 暂停任务终止语义。
 * 2026-09-22 UI 验证 F1：cancel(taskId) 只设置取消标志（"循环将在当前章完成后退出"），
 * 对 PAUSED 任务——没有活跃 worker 在检查该标志——既不终态转移也不释放项目工作权，
 * 占坑死锁（作者无法拒绝重写、无法启动新任务，且界面无出路）。
 * 修复语义：cancel 对 PAUSED 任务执行 PAUSED→CANCELLED 终态转移 + 释放工作权
 * （经 ProjectWorkCoordinator 条件释放，残存 worker 由写租约 epoch 防护）；
 * RUNNING 任务保持既有取消标志语义（worker 自行退出，不抢释放）。
 */
class WritingTaskExecutorPausedCancelTest {

    private NovelRepository repo;
    private WritingTaskExecutor executor;

    @BeforeEach
    void setUp() {
        repo = mock(NovelRepository.class);
        when(repo.findAllProjects()).thenReturn(java.util.List.of());
        executor = new WritingTaskExecutor(mock(NovelOrchestrator.class), repo);
    }

    @Test
    void 取消PAUSED任务终态转移为CANCELLED并释放工作权() {
        WritingTask task = mock(WritingTask.class);
        when(task.getId()).thenReturn("t-paused");
        when(task.getProjectId()).thenReturn("p1");
        when(task.getStatus()).thenReturn(WritingTask.Status.PAUSED);
        when(repo.findWritingTask("t-paused")).thenReturn(Optional.of(task));
        // 占坑：模拟暂停任务仍持有项目工作权
        executor.beginWork("p1", "t-paused");
        assertTrue(executor.hasActiveTask("p1"), "占坑后应有活动任务");

        executor.cancel("t-paused");

        verify(task).setStatus(WritingTask.Status.CANCELLED);
        verify(repo).saveWritingTask(task);
        assertFalse(executor.hasActiveTask("p1"), "取消后必须释放项目工作权");
        // 终止后新任务可立即占坑（拒绝重写/继续协作不再 409）
        assertDoesNotThrow(() -> executor.beginWork("p1", "t-new"));
        executor.endWork("p1", "t-new");
    }

    @Test
    void 取消RUNNING任务保持取消标志语义不抢释放() {
        WritingTask task = mock(WritingTask.class);
        when(task.getId()).thenReturn("t-running");
        when(task.getProjectId()).thenReturn("p1");
        when(task.getStatus()).thenReturn(WritingTask.Status.RUNNING);
        when(repo.findWritingTask("t-running")).thenReturn(Optional.of(task));
        executor.beginWork("p1", "t-running");

        executor.cancel("t-running");

        verify(task, never()).setStatus(any());
        verify(repo, never()).saveWritingTask(any());
        assertTrue(executor.hasActiveTask("p1"), "RUNNING 任务的坑位由 worker 退出释放");
        executor.endWork("p1", "t-running");
    }

    @Test
    void 取消未知任务不抛异常() {
        when(repo.findWritingTask("t-missing")).thenReturn(Optional.empty());
        assertDoesNotThrow(() -> executor.cancel("t-missing"));
    }
}
