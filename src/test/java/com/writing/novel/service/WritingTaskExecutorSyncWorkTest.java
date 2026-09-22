package com.writing.novel.service;

import com.writing.novel.domain.WritingTask;
import com.writing.novel.orchestrator.NovelOrchestrator;
import com.writing.novel.spi.NovelRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * NCS-074: 匿名同步占坑的可观测与可取消。
 * 2026-09-22 UI 回归 F4：补查/推进等同步操作以匿名 workId 占用项目工作权，期间无进度展示、
 * 不可取消；LLM 挂起时占坑持续到超时（实测 20 分钟）。NCS-072 的任务终止只覆盖任务型占坑。
 * 修复语义：beginWork 登记同步工作（kind/startedAt）供 workspace 观测；cancelSyncWork
 * 仅对匿名占坑生效（任务型占坑走 NCS-072/073 终止路径），立即释放项目工作权。
 * 诚实边界：取消不中断进行中的调用——原操作若最终完成仍按稿件身份落库。
 */
class WritingTaskExecutorSyncWorkTest {

    private NovelRepository repo;
    private WritingTaskExecutor executor;

    @BeforeEach
    void setUp() {
        repo = mock(NovelRepository.class);
        when(repo.findAllProjects()).thenReturn(java.util.List.of());
        executor = new WritingTaskExecutor(mock(NovelOrchestrator.class), repo);
    }

    @Test
    void 同步占坑被登记为可观测的工作信息() {
        executor.beginWork("p1", "recheck-3f2a9f0e-1b2c-3d4e-5f60-7a8b9c0d1e2f");

        WritingTaskExecutor.SyncWorkInfo work = executor.activeSyncWork("p1").orElseThrow();
        assertEquals("recheck", work.kind());
        assertEquals("recheck-3f2a9f0e-1b2c-3d4e-5f60-7a8b9c0d1e2f", work.workId());
        assertNotNull(work.startedAt());

        executor.endWork("p1", "recheck-3f2a9f0e-1b2c-3d4e-5f60-7a8b9c0d1e2f");
        assertTrue(executor.activeSyncWork("p1").isEmpty(), "endWork 后登记必须清除");
    }

    @Test
    void kind保留操作语义前缀() {
        executor.beginWork("p1", "review-decision-3f2a9f0e-1b2c-3d4e-5f60-7a8b9c0d1e2f");
        assertEquals("review-decision", executor.activeSyncWork("p1").orElseThrow().kind());
        executor.endWork("p1", "review-decision-3f2a9f0e-1b2c-3d4e-5f60-7a8b9c0d1e2f");

        executor.beginWork("p1", "chapter-change-3f2a9f0e-1b2c-3d4e-5f60-7a8b9c0d1e2f");
        assertEquals("chapter-change", executor.activeSyncWork("p1").orElseThrow().kind());
        executor.endWork("p1", "chapter-change-3f2a9f0e-1b2c-3d4e-5f60-7a8b9c0d1e2f");
    }

    @Test
    void 取消同步占坑立即释放工作权() {
        executor.beginWork("p1", "recheck-3f2a9f0e-1b2c-3d4e-5f60-7a8b9c0d1e2f");

        assertTrue(executor.cancelSyncWork("p1"), "匿名占坑必须可经同步取消路径释放");
        assertFalse(executor.hasActiveTask("p1"), "取消后必须释放项目工作权");
        assertTrue(executor.activeSyncWork("p1").isEmpty());
        // 释放后新操作可立即占坑（不再 409）
        assertDoesNotThrow(() -> executor.beginWork("p1", "chapter-change-new"));
        executor.endWork("p1", "chapter-change-new");
    }

    @Test
    void 任务型占坑不可经同步取消路径释放() {
        WritingTask task = mock(WritingTask.class);
        when(task.getId()).thenReturn("t-task");
        when(task.getProjectId()).thenReturn("p1");
        when(repo.findWritingTask("t-task")).thenReturn(Optional.of(task));
        executor.beginWork("p1", "t-task");

        assertFalse(executor.cancelSyncWork("p1"), "任务型占坑必须走任务终止路径（NCS-072/073）");
        assertTrue(executor.hasActiveTask("p1"), "任务型占坑不被误释放");
        assertTrue(executor.activeSyncWork("p1").isEmpty(), "任务型占坑不作为同步工作展示");
        executor.endWork("p1", "t-task");
    }

    @Test
    void 非持有者的endWork不清除登记() {
        executor.beginWork("p1", "w-holder");
        executor.endWork("p1", "w-other");
        assertTrue(executor.activeSyncWork("p1").isPresent(), "迟到/错误的释放不得清除持有者登记");
        executor.endWork("p1", "w-holder");
    }

    @Test
    void 无占坑时取消返回false() {
        assertFalse(executor.cancelSyncWork("p-none"));
    }
}
