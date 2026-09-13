package com.writing.novel.web;

import com.writing.novel.domain.Checkpoint;
import com.writing.novel.domain.Project;
import com.writing.novel.orchestrator.NovelOrchestrator;
import com.writing.novel.persistence.sqlite.ConnectionProvider;
import com.writing.novel.persistence.sqlite.SqliteNovelRepository;
import com.writing.novel.persistence.sqlite.SqliteSchemaInitializer;
import com.writing.novel.service.CheckpointTargetService;
import com.writing.novel.service.WritingTaskExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** p26-02: controller handles a checkpoint only after revision and state advancement succeed. */
class CheckpointRevisionControllerTest {

    @TempDir Path tempDir;
    private SqliteNovelRepository repository;
    private NovelOrchestrator orchestrator;
    private CheckpointController controller;
    private WritingTaskExecutor writingTaskExecutor;
    private Project project;

    @BeforeEach
    void setUp() {
        ConnectionProvider connectionProvider = new ConnectionProvider(tempDir.resolve("controller.db").toString());
        new SqliteSchemaInitializer(connectionProvider).initialize();
        repository = new SqliteNovelRepository(connectionProvider);
        orchestrator = mock(NovelOrchestrator.class);
        writingTaskExecutor = new WritingTaskExecutor(orchestrator, repository);
        controller = new CheckpointController(repository, orchestrator,
                new CheckpointTargetService(repository), writingTaskExecutor);
        project = new Project("审核书", "旧构思", "悬疑");
        repository.saveProject(project);
    }

    @Test
    void revisionFailureLeavesRejectAndModifyCheckpointsPending() {
        Checkpoint rejected = pending(Checkpoint.Type.AFTER_INITIAL_SETUP);
        when(orchestrator.reviseCheckpoint(eq(project.getId()), any(Checkpoint.class), eq("改写")))
                .thenThrow(new IllegalStateException("候选为空"));

        assertThrows(IllegalStateException.class,
                () -> controller.reject(project.getId(), rejected.getId(), Map.of("instructions", "改写")));
        assertEquals(Checkpoint.Status.PENDING,
                repository.findCheckpoint(rejected.getId()).orElseThrow().getStatus());

        Checkpoint modified = pending(Checkpoint.Type.AFTER_VOLUME_OUTLINE);
        when(orchestrator.reviseCheckpoint(eq(project.getId()), any(Checkpoint.class), eq("改写")))
                .thenThrow(new IllegalStateException("候选非法"));

        assertThrows(IllegalStateException.class,
                () -> controller.modify(project.getId(), modified.getId(), Map.of("instructions", "改写")));
        assertEquals(Checkpoint.Status.PENDING,
                repository.findCheckpoint(modified.getId()).orElseThrow().getStatus());
    }

    @Test
    void advancementFailureLeavesModifyCheckpointPending() {
        Checkpoint checkpoint = pending(Checkpoint.Type.AFTER_VOLUME_OUTLINE);
        var revision = revision();
        when(orchestrator.reviseCheckpoint(eq(project.getId()), any(Checkpoint.class), eq("改写")))
                .thenReturn(revision);
        doThrow(new IllegalStateException("推进失败")).when(orchestrator)
                .resumeAfterCheckpointRevision(eq(project.getId()), any(Checkpoint.class));

        assertThrows(IllegalStateException.class,
                () -> controller.modify(project.getId(), checkpoint.getId(), Map.of("instructions", "改写")));

        assertEquals(Checkpoint.Status.PENDING,
                repository.findCheckpoint(checkpoint.getId()).orElseThrow().getStatus());
    }

    @Test
    void successfulModifyReturnsRevisionAndHandlesCheckpointAfterAdvancement() {
        Checkpoint checkpoint = pending(Checkpoint.Type.AFTER_VOLUME_OUTLINE);
        var revision = revision();
        when(orchestrator.reviseCheckpoint(eq(project.getId()), any(Checkpoint.class), eq("改写")))
                .thenReturn(revision);

        Map<String, Object> response = controller.modify(
                project.getId(), checkpoint.getId(), Map.of("instructions", "改写"));

        verify(orchestrator).resumeAfterCheckpointRevision(eq(project.getId()), any(Checkpoint.class));
        assertSame(revision, response.get("revision"));
        assertEquals(Checkpoint.Status.MODIFIED,
                repository.findCheckpoint(checkpoint.getId()).orElseThrow().getStatus());
    }

    @Test
    void successfulRejectReturnsRevisionAndMarksCheckpointRejected() {
        Checkpoint checkpoint = pending(Checkpoint.Type.AFTER_INITIAL_SETUP);
        var revision = revision();
        when(orchestrator.reviseCheckpoint(eq(project.getId()), any(Checkpoint.class), eq("改写")))
                .thenReturn(revision);

        Map<String, Object> response = controller.reject(
                project.getId(), checkpoint.getId(), Map.of("instructions", "改写"));

        assertSame(revision, response.get("revision"));
        assertEquals(Checkpoint.Status.REJECTED,
                repository.findCheckpoint(checkpoint.getId()).orElseThrow().getStatus());
    }

    @Test
    void continuityRejectIsStatusOnlyAndDoesNotRequestContentRevision() {
        Checkpoint checkpoint = pending(Checkpoint.Type.ON_CONTINUITY_FIX);
        when(orchestrator.reviseCheckpoint(eq(project.getId()),
                argThat(cp -> cp.getType() == Checkpoint.Type.ON_CONTINUITY_FIX), eq("记录意见")))
                .thenThrow(new AssertionError("continuity reject must not revise content"));

        Map<String, Object> response = controller.reject(
                project.getId(), checkpoint.getId(), Map.of("instructions", "记录意见"));

        assertEquals("rejected", response.get("status"));
        assertFalse(response.containsKey("revision"));
        assertEquals(Checkpoint.Status.REJECTED,
                repository.findCheckpoint(checkpoint.getId()).orElseThrow().getStatus());
    }

    @Test
    void continuityModifyRejectedByLegacyGateAndChangesNothing() {
        // p36-06: CONTINUITY 类型不得经 legacy modify 绕过版本化批准——409 语义（IllegalState）且零变化
        Checkpoint checkpoint = pending(Checkpoint.Type.ON_CONTINUITY_FIX);

        assertThrows(IllegalStateException.class, () -> controller.modify(
                project.getId(), checkpoint.getId(), Map.of("instructions", "记录意见")));

        verify(orchestrator, never()).resumeAfterCheckpointRevision(anyString(), any(Checkpoint.class));
        assertEquals(Checkpoint.Status.PENDING,
                repository.findCheckpoint(checkpoint.getId()).orElseThrow().getStatus());
    }

    @Test
    void chapterRevisionCheckpointRejectsWhileAnotherChapterMutationOwnsProject() {
        Checkpoint checkpoint = pending(Checkpoint.Type.AFTER_CHAPTER);
        writingTaskExecutor.beginWork(project.getId(), "active-direct-edit");
        try {
            assertThrows(IllegalStateException.class,
                    () -> controller.modify(project.getId(), checkpoint.getId(),
                            Map.of("instructions", "增加对话")));
        } finally {
            writingTaskExecutor.endWork(project.getId(), "active-direct-edit");
        }

        assertEquals(Checkpoint.Status.PENDING,
                repository.findCheckpoint(checkpoint.getId()).orElseThrow().getStatus());
    }

    private Checkpoint pending(Checkpoint.Type type) {
        Checkpoint checkpoint = new Checkpoint(project.getId(), type, "待审核");
        repository.saveCheckpoint(checkpoint);
        return checkpoint;
    }

    private NovelOrchestrator.CheckpointRevisionResult revision() {
        return new NovelOrchestrator.CheckpointRevisionResult(
                "VOLUME_OUTLINE", "before", "after", 1, 2);
    }
}
