package com.writing.novel.service;

import com.writing.novel.domain.*;
import com.writing.novel.orchestrator.NovelOrchestrator;
import com.writing.novel.persistence.sqlite.*;
import com.writing.novel.spi.NovelRepository;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class CollaborativeRunTest {
    @TempDir Path dir;
    NovelRepository repo;
    NovelOrchestrator orchestrator;
    WritingTaskExecutor executor;
    Project project;

    @BeforeEach void setup() {
        ConnectionProvider cp = new ConnectionProvider(dir.resolve("tasks.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        repo = new SqliteNovelRepository(cp);
        orchestrator = mock(NovelOrchestrator.class);
        executor = new WritingTaskExecutor(orchestrator, repo);
        project = new Project("协作", "构思", "悬疑");
        repo.saveProject(project);
    }
    @AfterEach void close() { executor.shutdown(); }
    void state(NovelState value) { Project p=repo.findProject(project.getId()).orElseThrow();p.setState(value);repo.saveProject(p); }
    WritingTask awaitDone(WritingTask task) throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(executor.hasActiveTask(project.getId()) && System.nanoTime()<end) Thread.sleep(10);
        assertFalse(executor.hasActiveTask(project.getId()));
        return repo.findWritingTask(task.getId()).orElseThrow();
    }
    @Test void oneStartAdvancesFrameworkAndWholeBook() throws Exception {
        doAnswer(call -> {state(repo.findProject(project.getId()).orElseThrow().getState()==NovelState.CONCEIVING?NovelState.PLANNING:NovelState.WRITING);return null;}).when(orchestrator).step(project.getId());
        when(orchestrator.runWritingLoop(eq(project.getId()),eq(Integer.MAX_VALUE),any(BooleanSupplier.class),eq("保留人物动机"))).thenAnswer(call->{state(NovelState.FINAL);return 3;});
        WritingTask done=awaitDone(executor.startCollaborative(project.getId(),"保留人物动机"));
        assertEquals(WritingTask.Status.COMPLETED,done.getStatus());
        assertEquals(3,done.getChaptersWritten());
        verify(orchestrator,times(2)).step(project.getId());
    }
    @Test void takeoverWaitsForSafeBoundaryAndKeepsStage() throws Exception {
        CountDownLatch entered=new CountDownLatch(1), release=new CountDownLatch(1);
        doAnswer(call->{entered.countDown();assertTrue(release.await(3,TimeUnit.SECONDS));state(NovelState.PLANNING);return null;}).when(orchestrator).step(project.getId());
        WritingTask task=executor.startCollaborative(project.getId(),"指令");
        try {
            assertTrue(entered.await(2,TimeUnit.SECONDS));
            assertEquals(task.getId(),executor.startCollaborative(project.getId(),null).getId());
            executor.stopCollaborative(project.getId());
            assertTrue(repo.findWritingTask(task.getId()).orElseThrow().isStopRequested());
            assertTrue(executor.hasActiveTask(project.getId()));
            assertThrows(IllegalStateException.class,()->executor.beginWork(project.getId(),"human"));
            assertEquals(NovelState.CONCEIVING,repo.findProject(project.getId()).orElseThrow().getState());
        } finally {release.countDown();}
        assertEquals(WritingTask.Status.PAUSED,awaitDone(task).getStatus());
        assertEquals(NovelState.PLANNING,repo.findProject(project.getId()).orElseThrow().getState());
        assertTrue(repo.findPendingCheckpoints(project.getId()).isEmpty());
    }
    @Test void pendingReviewCannotBeSilentlyApproved() {
        state(NovelState.PAUSED_AT_CHECKPOINT);
        Checkpoint cp=new Checkpoint(project.getId(),Checkpoint.Type.AFTER_INITIAL_SETUP,"审核");repo.saveCheckpoint(cp);
        assertThrows(IllegalStateException.class,()->executor.startCollaborative(project.getId(),null));
        assertEquals(Checkpoint.Status.PENDING,repo.findCheckpoint(cp.getId()).orElseThrow().getStatus());
        assertTrue(repo.findWritingTasksByProject(project.getId()).isEmpty());
    }
    @Test void stalledStageFailsInsteadOfSpinning() throws Exception {
        WritingTask task=awaitDone(executor.startCollaborative(project.getId(),null));
        assertEquals(WritingTask.Status.FAILED,task.getStatus());
        verify(orchestrator,times(1)).step(project.getId());
    }
    @Test void manualReviewAndFinalDoNotStartNewWork() {
        state(NovelState.WRITING);
        Chapter ch=new Chapter(project.getId(),0,"待人审");ch.setStatus(Chapter.Status.NEEDS_MANUAL_REVIEW);repo.saveChapter(ch);
        assertThrows(IllegalStateException.class,()->executor.startCollaborative(project.getId(),null));
        state(NovelState.FINAL);
        assertThrows(IllegalStateException.class,()->executor.startCollaborative(project.getId(),null));
        verifyNoInteractions(orchestrator);
    }
    @Test void restartExposesInterruptedTaskAndRetainsInstruction() {
        WritingTask orphan=new WritingTask(project.getId(),24,WritingTask.Type.AUTO_RUN);orphan.setInstruction("保留作者定稿");repo.saveWritingTask(orphan);
        executor.shutdown();executor=new WritingTaskExecutor(orchestrator,repo);
        WritingTask recovered=executor.latestCollaborativeTask(project.getId());
        assertEquals(WritingTask.Status.PAUSED,recovered.getStatus());
        assertEquals("保留作者定稿",recovered.getInstruction());
        assertFalse(executor.hasActiveTask(project.getId()));
        verifyNoInteractions(orchestrator);
    }
    @Test void gracefulShutdownKeepsActiveCollaborationPausedForAuthorRecovery() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        doAnswer(call -> {
            entered.countDown();
            new CountDownLatch(1).await(); // Shutdown interrupts the in-flight model step.
            return null;
        }).when(orchestrator).step(project.getId());
        WritingTask task = executor.startCollaborative(project.getId(), "重启后仍保留作者要求");
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        executor.shutdown();
        WritingTask interrupted = awaitDone(task);
        assertEquals(WritingTask.Status.PAUSED, interrupted.getStatus());
        assertEquals("重启后仍保留作者要求", interrupted.getInstruction());
        assertEquals(NovelState.CONCEIVING, repo.findProject(project.getId()).orElseThrow().getState());
        verify(orchestrator, times(1)).step(project.getId());
    }
    @Test void shutdownDoesNotStartAnotherStepWhenAgentConsumesInterruption() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        doAnswer(call -> {
            entered.countDown();
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException ignored) {
                state(NovelState.PLANNING); // Some agents finish their step after a cancelled call.
            }
            return null;
        }).doAnswer(call -> { state(NovelState.FINAL); return null; })
                .when(orchestrator).step(project.getId());
        WritingTask task = executor.startCollaborative(project.getId(), "保留完成的框架");
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        executor.shutdown();
        assertEquals(WritingTask.Status.PAUSED, awaitDone(task).getStatus());
        assertEquals(NovelState.PLANNING, repo.findProject(project.getId()).orElseThrow().getState());
        verify(orchestrator, times(1)).step(project.getId());
    }
    @Test void qualityStopIsPausedAndNotBookCompletion() throws Exception {
        state(NovelState.WRITING);
        when(orchestrator.runWritingLoop(anyString(),anyInt(),any(BooleanSupplier.class),isNull())).thenReturn(0);
        assertEquals(WritingTask.Status.PAUSED,awaitDone(executor.startCollaborative(project.getId(),null)).getStatus());
    }
    @Test void budgetBlocksBeforeConceivingWithoutCallingAgents() {
        var budget=new com.writing.novel.telemetry.CostBudgetMonitor();budget.add(project.getId(),2);
        var prefs=new WritingPreferences(project.getId());prefs.setCostBudgetYuan(1);repo.saveWritingPreferences(prefs);
        executor.shutdown();executor=new WritingTaskExecutor(orchestrator,repo,budget);
        assertThrows(IllegalStateException.class,()->executor.startCollaborative(project.getId(),null));
        verifyNoInteractions(orchestrator);
    }
    @Test void resumedRunUsesSavedInstructionAndFreshStage() throws Exception {
        var old=new WritingTask(project.getId(),24,WritingTask.Type.AUTO_RUN);old.setInstruction("保留编辑内容");old.setStatus(WritingTask.Status.PAUSED);repo.saveWritingTask(old);
        state(NovelState.WRITING);
        when(orchestrator.runWritingLoop(eq(project.getId()),anyInt(),any(BooleanSupplier.class),eq("保留编辑内容"))).thenAnswer(call->{state(NovelState.FINAL);return 1;});
        assertEquals(WritingTask.Status.COMPLETED,awaitDone(executor.startCollaborative(project.getId(),null)).getStatus());
        verify(orchestrator).runWritingLoop(eq(project.getId()),anyInt(),any(BooleanSupplier.class),eq("保留编辑内容"));
    }

}
