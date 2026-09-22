package com.writing.novel.service;

import com.writing.novel.domain.WritingTask;
import com.writing.novel.orchestrator.NovelOrchestrator;
import com.writing.novel.spi.NovelRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * NCS-075: worker 收尾窗口批准冲突的分类语义。
 * 2026-09-22 UI 回归 F5：每章审阅下检查点出现时 worker 仍在收尾质量检查，此时批准命中
 * beginWork 冲突，409 文案「已有进行中的写作任务，请先暂停或取消」误导（无事可暂停/取消），
 * 前端又把它当版本冲突（「请确认新稿后再批准」）。实测等待后重试即成功。
 * 修复语义：beginWork 冲突按持有者分类——
 * - 持有者是 RUNNING 写作任务 → code=WORKER_FINALIZING，文案指引稍候重试；
 * - 持有者是匿名同步操作 → code=SYNC_WORK_BUSY，文案指引等待或取消该操作；
 * - 其余（PAUSED 任务等）→ 无专用码，保持既有「暂停或取消」指引。
 */
class WritingTaskExecutorBeginWorkConflictTest {

    private NovelRepository repo;
    private WritingTaskExecutor executor;

    @BeforeEach
    void setUp() {
        repo = mock(NovelRepository.class);
        when(repo.findAllProjects()).thenReturn(java.util.List.of());
        executor = new WritingTaskExecutor(mock(NovelOrchestrator.class), repo);
    }

    @Test
    void 收尾中的RUNNING任务占坑时批准冲突返回WORKER_FINALIZING() {
        WritingTask running = mock(WritingTask.class);
        when(running.getId()).thenReturn("t-running");
        when(running.getProjectId()).thenReturn("p1");
        when(running.getStatus()).thenReturn(WritingTask.Status.RUNNING);
        when(repo.findWritingTask("t-running")).thenReturn(Optional.of(running));
        executor.beginWork("p1", "t-running");

        WorkConflictException conflict = assertThrows(WorkConflictException.class,
                () -> executor.beginWork("p1", "review-decision-uuid"));
        assertEquals("WORKER_FINALIZING", conflict.code(),
                "RUNNING 任务持有坑位时，批准冲突必须携带 WORKER_FINALIZING 码");
        assertTrue(conflict.getMessage().contains("收尾"),
                "文案应说明 Agent 正在收尾: " + conflict.getMessage());
        assertTrue(conflict.getMessage().contains("稍候"),
                "文案应指引稍候重试: " + conflict.getMessage());
    }

    @Test
    void 匿名同步操作占坑时冲突返回SYNC_WORK_BUSY() {
        executor.beginWork("p1", "recheck-uuid");

        WorkConflictException conflict = assertThrows(WorkConflictException.class,
                () -> executor.beginWork("p1", "review-decision-uuid"));
        assertEquals("SYNC_WORK_BUSY", conflict.code(),
                "匿名同步操作持有坑位时，冲突必须携带 SYNC_WORK_BUSY 码");
        assertTrue(conflict.getMessage().contains("同步"),
                "文案应说明是同步内容处理: " + conflict.getMessage());
    }

    @Test
    void 非运行任务占坑时保持既有通用指引() {
        WritingTask paused = mock(WritingTask.class);
        when(paused.getId()).thenReturn("t-paused");
        when(paused.getProjectId()).thenReturn("p1");
        when(paused.getStatus()).thenReturn(WritingTask.Status.PAUSED);
        when(repo.findWritingTask("t-paused")).thenReturn(Optional.of(paused));
        executor.beginWork("p1", "t-paused");

        WorkConflictException conflict = assertThrows(WorkConflictException.class,
                () -> executor.beginWork("p1", "review-decision-uuid"));
        assertNull(conflict.code(), "PAUSED 任务占坑走既有终止指引，不带专用码");
        assertTrue(conflict.getMessage().contains("暂停") || conflict.getMessage().contains("取消"),
                "既有文案应指引暂停或取消: " + conflict.getMessage());
    }

    @Test
    void 无冲突时不产生异常语义() {
        assertDoesNotThrow(() -> executor.beginWork("p2", "w1"));
        executor.endWork("p2", "w1");
    }
}
