package com.writing.novel.web;

import com.writing.novel.agent.QualityEvaluationAgent;
import com.writing.novel.domain.Checkpoint;
import com.writing.novel.domain.NovelState;
import com.writing.novel.domain.Outline;
import com.writing.novel.domain.Project;
import com.writing.novel.domain.WritingTask;
import com.writing.novel.orchestrator.NovelOrchestrator;
import com.writing.novel.orchestrator.ProjectService;
import com.writing.novel.service.CanonPatchService;
import com.writing.novel.service.WritingTaskExecutor;
import com.writing.novel.spi.NovelRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * p20-06: 所有写作入口共享项目工作权（ProjectWorkCoordinator）。
 *
 * /run、/resume、/write-chapter、/write-all 四个变更型入口在同一项目已有 live worker 时
 * 必须一律冲突拒绝（IllegalStateException → 409），不得并发写出第二章。
 */
class WritingEntryOwnershipTest {

    private NovelRepository mockRepo;
    private NovelOrchestrator mockOrch;
    private WritingTaskExecutor executor;
    private OrchestratorController orchestratorController;
    private ContentController contentController;

    @BeforeEach
    void setUp() {
        mockRepo = mock(NovelRepository.class);
        mockOrch = mock(NovelOrchestrator.class);
        when(mockRepo.findAllProjects()).thenReturn(java.util.List.of());
        executor = new WritingTaskExecutor(mockOrch, mockRepo);
        orchestratorController = new OrchestratorController(mockOrch,
                new ProjectService(mockRepo), mock(QualityEvaluationAgent.class), executor, mockRepo);
        contentController = new ContentController(mockRepo, mockOrch,
                mock(CanonPatchService.class), executor);
        when(mockRepo.findPendingCheckpoints("p1")).thenReturn(java.util.List.of());
    }

    private void holdOwnership(NovelState state, boolean hasOutline) {
        executor.beginWork("p1", "live-writer");
        Project writing = new Project("测试", "概念", "玄幻");
        writing.setState(state);
        when(mockRepo.findProject("p1")).thenReturn(Optional.of(writing));
        when(mockRepo.findOutlineByProject(writing.getId())).thenReturn(
                hasOutline ? Optional.of(new Outline(writing.getId(), "总纲")) : Optional.empty());
        when(mockRepo.findOutlineByProject("p1")).thenReturn(
                hasOutline ? Optional.of(new Outline("p1", "总纲")) : Optional.empty());
    }

    @Test
    void run入口在有活动worker时冲突拒绝() {
        holdOwnership(NovelState.PLANNING, true);
        assertThrows(IllegalStateException.class, () -> orchestratorController.run("p1"),
                "/run 必须共享协调器：活动期冲突拒绝");
        assertTrue(executor.hasActiveTask("p1"));
    }

    @Test
    void resume入口在有活动worker时冲突拒绝() {
        holdOwnership(NovelState.WRITING, true);
        assertThrows(IllegalStateException.class, () -> orchestratorController.resume("p1"),
                "/resume 必须共享协调器：活动期冲突拒绝");
        assertTrue(executor.hasActiveTask("p1"));
    }

    @Test
    void writeAll入口在有活动worker时冲突拒绝() {
        holdOwnership(NovelState.WRITING, true);
        assertThrows(IllegalStateException.class, () -> orchestratorController.writeAll("p1", 5, null),
                "/write-all 活动期冲突拒绝");
        assertTrue(executor.hasActiveTask("p1"));
    }

    @Test
    void writeChapter入口在有活动worker时冲突拒绝() {
        holdOwnership(NovelState.WRITING, true);
        assertThrows(IllegalStateException.class, () -> contentController.writeChapter("p1", null),
                "/write-chapter 活动期冲突拒绝");
        assertTrue(executor.hasActiveTask("p1"));
    }

    @Test
    void owner终态后合法入口恢复正常且不泄漏工作权() {
        Project writing = new Project("测试", "概念", "玄幻");
        writing.setState(NovelState.WRITING);
        when(mockRepo.findProject("p1")).thenReturn(Optional.of(writing));
        when(mockRepo.findOutlineByProject(writing.getId()))
                .thenReturn(Optional.of(new Outline(writing.getId(), "总纲")));
        doAnswer(inv -> null).when(mockOrch).step("p1");

        assertThrows(IllegalStateException.class, () -> orchestratorController.run("p1"),
                "WRITING 状态不得用 run 隐式写章");
        assertFalse(executor.hasActiveTask("p1"));

        orchestratorController.resume("p1");
        assertFalse(executor.hasActiveTask("p1"), "resume 完成后必须释放工作权");

        // 释放后其他入口可正常获得工作权
        assertDoesNotThrow(() -> orchestratorController.writeAll("p1", 5, null));
    }

    @Test
    void 无效动作在任务或orchestrator副作用前被拒绝() {
        Project project = new Project("测试", "概念", "玄幻");
        project.setState(NovelState.CONCEIVING);
        when(mockRepo.findProject("p1")).thenReturn(Optional.of(project));

        assertThrows(IllegalStateException.class,
                () -> orchestratorController.writeAll("p1", 5, null));
        assertThrows(IllegalStateException.class,
                () -> contentController.writeChapter("p1", null));
        assertThrows(IllegalStateException.class,
                () -> orchestratorController.finalAudit("p1"));

        project.setState(NovelState.WRITING);
        assertThrows(IllegalStateException.class,
                () -> orchestratorController.run("p1"));

        verify(mockRepo, never()).saveWritingTask(any());
        verifyNoInteractions(mockOrch);
    }

    @Test
    void 首次门禁后暂停抢先时不创建任务或进入orchestrator() throws Exception {
        Project project = new Project("并发门禁", "概念", "玄幻");
        project.setState(NovelState.WRITING);
        AtomicBoolean paused = new AtomicBoolean(false);
        when(mockRepo.findProject("p1")).thenReturn(Optional.of(project));
        when(mockRepo.findOutlineByProject("p1"))
                .thenReturn(Optional.of(new Outline("p1", "总纲")));
        when(mockRepo.findPendingCheckpoints("p1")).thenAnswer(invocation -> paused.get()
                ? java.util.List.of(new Checkpoint("p1", Checkpoint.Type.ON_CONTINUITY_FIX, "用户暂停"))
                : java.util.List.of());
        BlockingWriteChapterExecutor blockingExecutor =
                new BlockingWriteChapterExecutor(mockOrch, mockRepo);
        ContentController controller = new ContentController(mockRepo, mockOrch,
                mock(CanonPatchService.class), blockingExecutor);

        CompletableFuture<WritingTask> request = CompletableFuture.supplyAsync(() -> {
            controller.writeChapter("p1", null);
            return null;
        });
        assertTrue(blockingExecutor.awaitStart(), "请求应已通过控制器首次门禁");
        project.setState(NovelState.PAUSED_AT_CHECKPOINT);
        paused.set(true);
        blockingExecutor.allowStart();

        CompletionException rejected = assertThrows(CompletionException.class, request::join);
        assertInstanceOf(IllegalStateException.class, rejected.getCause());
        verify(mockRepo, never()).saveWritingTask(any());
        verifyNoInteractions(mockOrch);
        assertFalse(blockingExecutor.hasActiveTask("p1"));
    }

    private static final class BlockingWriteChapterExecutor extends WritingTaskExecutor {
        private final CountDownLatch startEntered = new CountDownLatch(1);
        private final CountDownLatch continueStart = new CountDownLatch(1);

        private BlockingWriteChapterExecutor(NovelOrchestrator orchestrator,
                                             NovelRepository repository) {
            super(orchestrator, repository);
        }

        @Override
        public WritingTask startWriteChapter(String projectId, Runnable actionGate) {
            startEntered.countDown();
            try {
                if (!continueStart.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("等待并发状态变更超时");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("等待并发状态变更被中断", e);
            }
            return super.startWriteChapter(projectId, actionGate);
        }

        private boolean awaitStart() throws InterruptedException {
            return startEntered.await(5, TimeUnit.SECONDS);
        }

        private void allowStart() {
            continueStart.countDown();
        }
    }
}
