package com.writing.novel.orchestrator;

import com.writing.novel.domain.ContinuityIssue;
import com.writing.novel.domain.Foreshadowing;
import com.writing.novel.domain.NovelState;
import com.writing.novel.domain.Outline;
import com.writing.novel.domain.Project;
import com.writing.novel.domain.Volume;
import com.writing.novel.persistence.sqlite.ConnectionProvider;
import com.writing.novel.persistence.sqlite.SqliteNovelRepository;
import com.writing.novel.persistence.sqlite.SqliteSchemaInitializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * p21-02: FINAL/NEEDS_REVISION 服务端命令门禁。
 *
 * - FINAL 拒绝 run/resume/write-one/write-all，且不改变项目状态
 * - NEEDS_REVISION 拒绝写新章，但允许人工修订 + 显式成书门禁重试（重试通过 → FINAL）
 */
class TerminalStateCommandGateTest {

    @TempDir Path tempDir;
    private SqliteNovelRepository repo;
    private NovelOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        ConnectionProvider cp = new ConnectionProvider(tempDir.resolve("terminal.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        repo = new SqliteNovelRepository(cp);
        orchestrator = new NovelOrchestrator(repo, new ProjectService(repo),
                null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    private Project seedAndFinish(int mustResolveCount) {
        Project p = new Project("终态" + System.nanoTime(), "测试", "权谋");
        repo.saveProject(p);
        Outline outline = new Outline(p.getId(), "总纲");
        Volume v = new Volume(p.getId(), 1, "第一卷", "卷摘要");
        v.getSynopses().add(new com.writing.novel.domain.ChapterSynopsis(p.getId(), 1, "第1章", "开局"));
        outline.getVolumes().add(v);
        repo.saveOutline(outline);
        for (int i = 0; i < mustResolveCount; i++) {
            Foreshadowing f = new Foreshadowing(p.getId(), "伏笔" + i, "ctx", 1);
            f.setMustResolve(true);
            repo.saveForeshadowing(f);
        }
        p.setCurrentChapterIndex(1);
        p.setState(NovelState.VOLUME_DONE);
        repo.saveProject(p);
        orchestrator.step(p.getId()); // → FINAL（≤3）或 NEEDS_REVISION（>3）
        return repo.findProject(p.getId()).orElseThrow();
    }

    @Test
    void FINAL拒绝所有写作命令且状态不变() {
        String pid = seedAndFinish(2).getId(); // 门禁通过 → FINAL
        assertEquals(NovelState.FINAL, repo.findProject(pid).orElseThrow().getState());

        assertThrows(IllegalStateException.class, () -> orchestrator.runWritingOneChapter(pid),
                "FINAL 拒绝写单章");
        assertThrows(IllegalStateException.class, () -> orchestrator.runWritingLoop(pid, 5),
                "FINAL 拒绝连续写作");
        assertThrows(IllegalStateException.class, () -> orchestrator.step(pid),
                "FINAL 拒绝 run/resume step 推进");

        Project after = repo.findProject(pid).orElseThrow();
        assertEquals(NovelState.FINAL, after.getState(), "被拒绝的命令不得改变项目状态");
        assertEquals(1, after.getCurrentChapterIndex(), "被拒绝的命令不得推进章节索引");
    }

    @Test
    void NEEDS_REVISION拒绝写新章但允许修订与显式重审() {
        String pid = seedAndFinish(4).getId(); // 4 条 mustResolve → NEEDS_REVISION
        assertEquals(NovelState.NEEDS_REVISION, repo.findProject(pid).orElseThrow().getState());

        assertThrows(IllegalStateException.class, () -> orchestrator.runWritingOneChapter(pid),
                "NEEDS_REVISION 拒绝写新章");

        // 重审仍不通过（伏笔未修订）
        assertFalse(orchestrator.retryFinalAudit(pid), "未修订时重审应不通过");
        assertEquals(NovelState.NEEDS_REVISION, repo.findProject(pid).orElseThrow().getState(),
                "失败重审保持 NEEDS_REVISION");

        // 人工修订：回收 mustResolve 伏笔（内容编辑不经过写作守卫）
        for (Foreshadowing f : repo.findUnresolvedForeshadowings(pid)) {
            f.resolve(1);
            repo.saveForeshadowing(f);
        }

        // 显式重审通过 → FINAL
        assertTrue(orchestrator.retryFinalAudit(pid), "修订后重审应通过");
        assertEquals(NovelState.FINAL, repo.findProject(pid).orElseThrow().getState());
    }

    @Test
    void 重审仅NEEDS_REVISION可触发() {
        String pid = seedAndFinish(2).getId(); // FINAL
        assertThrows(IllegalStateException.class, () -> orchestrator.retryFinalAudit(pid),
                "FINAL 状态重审无意义，应拒绝");
    }
}
