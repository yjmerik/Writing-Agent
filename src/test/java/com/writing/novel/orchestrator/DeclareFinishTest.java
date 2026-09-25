package com.writing.novel.orchestrator;

import com.writing.novel.domain.Checkpoint;
import com.writing.novel.domain.Chapter;
import com.writing.novel.domain.NovelState;
import com.writing.novel.domain.Project;
import com.writing.novel.persistence.sqlite.ConnectionProvider;
import com.writing.novel.persistence.sqlite.SqliteNovelRepository;
import com.writing.novel.persistence.sqlite.SqliteSchemaInitializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * NCS-079（听雪刀 P0）：作者宣告完结——从写作态显式触发行成书门禁。
 * 此前 finishBookWithGate 只能由写作循环的"大纲耗尽"分支触达，
 * PAUSED_AT_CHECKPOINT 等状态下作者没有任何完结出口。
 */
class DeclareFinishTest {

    @TempDir
    Path tempDir;
    private SqliteNovelRepository repo;
    private NovelOrchestrator orch;
    private String pid;

    @BeforeEach
    void setUp() {
        ConnectionProvider cp = new ConnectionProvider(tempDir.resolve("declare-finish.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        repo = new SqliteNovelRepository(cp);
        orch = new NovelOrchestrator(repo, new ProjectService(repo),
                null, null, null,
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null,
                null);
        Project p = new Project("宣告完结书", "构思", "武侠");
        p.setCurrentChapterIndex(2);
        p.setState(NovelState.PAUSED_AT_CHECKPOINT);
        repo.saveProject(p);
        pid = p.getId();
        // 两章已写正文（门禁软性篇幅告警之外无硬依赖）
        for (int i = 1; i <= 2; i++) {
            Chapter c = new Chapter(pid, i, "第" + i + "章");
            c.setContent("正文".repeat(400));
            c.setStatus(Chapter.Status.DONE);
            repo.saveChapter(c);
        }
    }

    @Test
    void 宣告完结关闭待审检查点并到达FINAL() {
        Checkpoint cp1 = new Checkpoint(pid, Checkpoint.Type.AFTER_VOLUME_OUTLINE, "卷3大纲已生成，待审核");
        Checkpoint cp2 = new Checkpoint(pid, Checkpoint.Type.AFTER_CHAPTER, "第2章已完成，待审核");
        repo.saveCheckpoint(cp1);
        repo.saveCheckpoint(cp2);

        boolean passed = orch.declareFinish(pid);

        assertTrue(passed, "干净台账下门禁应通过");
        Project p = repo.findProject(pid).orElseThrow();
        assertEquals(NovelState.FINAL, p.getState());
        assertEquals(Checkpoint.Status.REJECTED, repo.findCheckpoint(cp1.getId()).orElseThrow().getStatus(),
                "宣告完结应一并拒绝关闭待审检查点");
        assertEquals(Checkpoint.Status.REJECTED, repo.findCheckpoint(cp2.getId()).orElseThrow().getStatus());
        assertTrue(repo.findPendingCheckpoints(pid).isEmpty(), "不应残留待审检查点");
    }

    @Test
    void 门禁失败时返回false并进入NEEDS_REVISION() {
        for (int i = 0; i < 5; i++) {
            com.writing.novel.domain.Foreshadowing f =
                    new com.writing.novel.domain.Foreshadowing(pid, "关键伏笔" + i, "埋设", 1);
            f.setMustResolve(true);
            repo.saveForeshadowing(f);
        }
        boolean passed = orch.declareFinish(pid);
        assertFalse(passed);
        assertEquals(NovelState.NEEDS_REVISION, repo.findProject(pid).orElseThrow().getState(),
                "mustResolve>3 门禁应如实 FAIL");
    }

    @Test
    void 无章节时拒绝宣告完结() {
        Project fresh = new Project("新书", "构思", "武侠");
        fresh.setState(NovelState.WRITING);
        repo.saveProject(fresh);
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> orch.declareFinish(fresh.getId()));
        assertTrue(ex.getMessage().contains("不能宣告完结"), ex.getMessage());
    }

    @Test
    void 已完结项目拒绝再次宣告() {
        Project p = repo.findProject(pid).orElseThrow();
        p.setState(NovelState.FINAL);
        repo.saveProject(p);
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> orch.declareFinish(pid));
        assertTrue(ex.getMessage().contains("已完结"), ex.getMessage());
    }
}
