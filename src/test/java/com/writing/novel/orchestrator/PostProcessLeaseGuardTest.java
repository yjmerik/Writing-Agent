package com.writing.novel.orchestrator;

import com.writing.novel.domain.Chapter;
import com.writing.novel.domain.Project;
import com.writing.novel.persistence.sqlite.ConnectionProvider;
import com.writing.novel.persistence.sqlite.SqliteNovelRepository;
import com.writing.novel.persistence.sqlite.SqliteSchemaInitializer;
import com.writing.novel.persistence.sqlite.WriteLeaseRepository;
import com.writing.novel.service.WriteLeaseService;
import com.writing.novel.service.WriteLeaseStaleException;
import com.writing.novel.domain.WritingTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

/**
 * NCS-001：后处理入口同样受 epoch 租约门禁——
 * 取消/接手后旧任务的后处理（投影/派生写入）不得继续落库。
 */
class PostProcessLeaseGuardTest {

    @TempDir
    Path tempDir;

    private SqliteNovelRepository repo;
    private WriteLeaseService lease;
    private String projectId;

    @BeforeEach
    void setUp() {
        var cp = new ConnectionProvider(tempDir.resolve("post-lease.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        repo = new SqliteNovelRepository(cp);
        lease = new WriteLeaseService(new WriteLeaseRepository(cp));
        Project project = new Project("后处理租约", "构思", "武侠");
        repo.saveProject(project);
        projectId = project.getId();
    }

    @Test
    void epoch失效后后处理被拒() {
        WritingTask task = new WritingTask(projectId, 3, WritingTask.Type.WRITE_ALL);
        task.setLeaseEpoch(lease.currentEpoch(projectId));
        repo.saveWritingTask(task);
        lease.bump(projectId, "cancel:test");

        NovelOrchestrator orch = mock(NovelOrchestrator.class);
        doReturn(lease).when(orch).writeLeaseRef();
        var cp = new ConnectionProvider(tempDir.resolve("post-lease.db").toString());
        var ledger = new com.writing.novel.persistence.sqlite.PostProcessLedgerRepository(cp);
        ChapterPostProcessor processor = new ChapterPostProcessor(
                orch, repo, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, ledger);

        Chapter chapter = new Chapter(projectId, 1, "第一章");
        assertThrows(WriteLeaseStaleException.class,
                () -> processor.process(projectId, chapter, 1, null, null),
                "epoch 失效后后处理入口必须被拒");
        assertThrows(WriteLeaseStaleException.class,
                () -> processor.resume(projectId, chapter, 1, null, null),
                "续跑路径同样被拒");
    }
}
