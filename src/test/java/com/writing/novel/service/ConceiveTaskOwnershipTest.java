package com.writing.novel.service;

import com.writing.novel.domain.WritingTask;
import com.writing.novel.orchestrator.NovelOrchestrator;
import com.writing.novel.spi.NovelRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * p15-05: 单项目构思任务所有权竞态回归测试。
 * 原任务仍在运行时重复 startConceiving 不得启动替代任务：
 * - 重复调用返回原任务，orchestrator.step 只执行一次
 * - 原任务完成时只移除属于自己的 activeTasks 映射
 */
class ConceiveTaskOwnershipTest {

    private NovelRepository mockRepo;
    private NovelOrchestrator mockOrchestrator;
    private WritingTaskExecutor executor;

    @BeforeEach
    void setUp() {
        mockRepo = mock(NovelRepository.class);
        mockOrchestrator = mock(NovelOrchestrator.class);
        when(mockRepo.findAllProjects()).thenReturn(java.util.List.of());
        executor = new WritingTaskExecutor(mockOrchestrator, mockRepo);
    }

    @Test
    void duplicateStartConceivingReturnsOriginalAndStepsOnce() throws Exception {
        CountDownLatch stepEntered = new CountDownLatch(1);
        CountDownLatch releaseStep = new CountDownLatch(1);
        doAnswer(inv -> {
            stepEntered.countDown();
            releaseStep.await(10, TimeUnit.SECONDS);
            return null;
        }).when(mockOrchestrator).step("p1");

        WritingTask task1 = executor.startConceiving("p1");
        assertTrue(stepEntered.await(5, TimeUnit.SECONDS), "原任务应已进入 orchestrator.step");

        // 原任务仍在运行时重复启动——必须返回原任务而不是替换
        when(mockRepo.findWritingTask(task1.getId())).thenReturn(Optional.of(task1));
        WritingTask task2 = executor.startConceiving("p1");
        assertSame(task1, task2, "重复 startConceiving 应返回原任务而非启动替代任务");
        verify(mockOrchestrator, times(1)).step("p1");

        // 放行原任务完成，其 finally 应清除自己的映射
        releaseStep.countDown();
        awaitNoActiveTask();
    }

    @Test
    void oldConceiveWorkerReleasesOnlyItsOwnTokenAndRejectsOverlappingWorkers() throws Exception {
        CountDownLatch stepEntered = new CountDownLatch(1);
        CountDownLatch releaseStep = new CountDownLatch(1);
        doAnswer(inv -> {
            stepEntered.countDown();
            releaseStep.await(10, TimeUnit.SECONDS);
            return null;
        }).when(mockOrchestrator).step("p1");

        executor.startConceiving("p1");
        assertTrue(stepEntered.await(5, TimeUnit.SECONDS), "原任务应已进入 orchestrator.step");

        // p20-05: 原任务运行期间，竞争 worker（beginWork 模拟）必须被冲突拒绝——
        // 不存在强制接管，旧/新 worker 重叠在源头即被排除
        assertThrows(IllegalStateException.class, () -> executor.beginWork("p1", "newer-owner"),
                "已有 live conceive worker 时竞争工作注册必须冲突拒绝");
        assertTrue(executor.hasActiveTask("p1"), "原 owner 应保持");

        // 原任务完成后条件释放自己的 token
        releaseStep.countDown();
        awaitNoActiveTask();
    }

    @Test
    void nonTaskOwnerRejectsConceiveWithoutCreatingTaskOrReplacingOwner() {
        executor.beginWork("p1", "outline-edit-x");
        try {
            assertThrows(IllegalStateException.class, () -> executor.startConceiving("p1"));

            verify(mockRepo, never()).saveWritingTask(any());
            verifyNoInteractions(mockOrchestrator);
            assertTrue(executor.hasActiveTask("p1"), "冲突后原 outline owner 必须仍然持有工作权");
            assertThrows(IllegalStateException.class,
                    () -> executor.beginWork("p1", "replacement-owner"));
        } finally {
            executor.endWork("p1", "outline-edit-x");
        }
    }

    private void awaitNoActiveTask() throws InterruptedException {
        for (int i = 0; i < 100 && executor.hasActiveTask("p1"); i++) {
            Thread.sleep(50);
        }
        assertTrue(!executor.hasActiveTask("p1"), "项目映射应在原任务退出后被清除");
    }
}
