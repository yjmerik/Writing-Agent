package com.writing.novel.orchestrator;

import com.writing.novel.domain.Checkpoint;
import com.writing.novel.domain.NovelState;
import com.writing.novel.domain.Outline;
import com.writing.novel.domain.Project;
import com.writing.novel.domain.ProjectMode;
import com.writing.novel.persistence.sqlite.ConnectionProvider;
import com.writing.novel.persistence.sqlite.SqliteNovelRepository;
import com.writing.novel.persistence.sqlite.SqliteSchemaInitializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class VolumeOutlineCheckpointTest {

    @TempDir Path tempDir;

    @Test
    void 交互模式追加卷后创建AFTER_VOLUME_OUTLINE并暂停() throws Exception {
        var cp = new ConnectionProvider(tempDir.resolve("voc.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        var repo = new SqliteNovelRepository(cp);
        var service = new ProjectService(repo);
        Project p = service.create("t", "c", "tag", ProjectMode.INTERACTIVE);
        p.setState(NovelState.WRITING);
        repo.saveProject(p);
        repo.saveOutline(new Outline(p.getId(), "总纲"));

        NovelOrchestrator orchestrator = new NovelOrchestrator(repo, service,
                null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null,
                null, null, null, null);

        Method m = NovelOrchestrator.class.getDeclaredMethod("parseAndAppendVolume",
                Outline.class, String.class, String.class, int.class, int.class);
        m.setAccessible(true);
        Outline outline = repo.findOutlineByProject(p.getId()).orElseThrow();
        m.invoke(orchestrator, outline, "第1章 新卷开端：开场。\n第2章 推进：冲突。", p.getId(), 1, 1);

        assertEquals(NovelState.PAUSED_AT_CHECKPOINT, repo.findProject(p.getId()).orElseThrow().getState());
        assertTrue(repo.findPendingCheckpoints(p.getId()).stream()
                .anyMatch(c -> c.getType() == Checkpoint.Type.AFTER_VOLUME_OUTLINE));
    }
}
