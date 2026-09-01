package com.writing.novel.orchestrator;

import com.writing.novel.domain.Checkpoint;
import com.writing.novel.domain.NovelState;
import com.writing.novel.domain.Project;
import com.writing.novel.persistence.sqlite.ConnectionProvider;
import com.writing.novel.persistence.sqlite.SqliteNovelRepository;
import com.writing.novel.persistence.sqlite.SqliteSchemaInitializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** p14-05: approving one checkpoint must not consume leftover PENDING. */
class ApproveSpecificCheckpointTest {

    @TempDir Path tempDir;
    private SqliteNovelRepository repo;
    private NovelOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        ConnectionProvider cp = new ConnectionProvider(tempDir.resolve("a.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        repo = new SqliteNovelRepository(cp);
        orchestrator = new NovelOrchestrator(repo, new ProjectService(repo),
                null, null, null, null, null, null, null,
                null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null);
    }

    @Test
    void approveNewer_leavesOlderPending_andStaysPaused() {
        Project p = new Project("t", "c", "tag");
        p.setState(NovelState.PAUSED_AT_CHECKPOINT);
        repo.saveProject(p);

        Checkpoint older = new Checkpoint(p.getId(), Checkpoint.Type.AFTER_VOLUME_OUTLINE, "卷1大纲");
        Checkpoint newer = new Checkpoint(p.getId(), Checkpoint.Type.AFTER_CHAPTER, "第1章");
        repo.saveCheckpoint(older);
        try { Thread.sleep(5); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        repo.saveCheckpoint(newer);

        orchestrator.approveCheckpoint(p.getId(), newer.getId());

        assertEquals(NovelState.PAUSED_AT_CHECKPOINT, repo.findProject(p.getId()).orElseThrow().getState());
        assertEquals(1, repo.findPendingCheckpoints(p.getId()).size());
        assertEquals(older.getId(), repo.findPendingCheckpoints(p.getId()).get(0).getId());
    }
}
