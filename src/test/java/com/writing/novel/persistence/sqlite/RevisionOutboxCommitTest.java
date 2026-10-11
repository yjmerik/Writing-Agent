package com.writing.novel.persistence.sqlite;

import com.writing.novel.domain.Chapter;
import com.writing.novel.orchestrator.ChapterPostProcessor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * NCS-042: 章正文与派生任务入账同事务——单章与整组任一失败一起回滚。
 */
class RevisionOutboxCommitTest {

    @TempDir
    Path tempDir;
    private SqliteNovelRepository repo;

    private Chapter chapter(String pid, int idx, String content) {
        var c = new Chapter(pid, idx, "第" + idx + "章");
        c.setContent(content);
        return c;
    }

    @BeforeEach
    void setUp() {
        var cp = new ConnectionProvider(tempDir.resolve("roc.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        repo = new SqliteNovelRepository(cp);
        repo.saveProject(new com.writing.novel.domain.Project("t", "c", "tag"));
    }

    @Test
    void 单章事务同写正文与outbox() {
        var c = chapter("p1", 1, "正文。");
        assertTrue(repo.saveChapterEnqueuingDerivatives(c,
                List.of(ChapterPostProcessor.Stage.CONTINUITY, ChapterPostProcessor.Stage.ENTITY_GRAPH), 1));
        // 正文与两行账同时存在
        assertNotNull(repo.findChapterByIndex("p1", 1).orElse(null));
        var ledger = new PostProcessLedgerRepository(new ConnectionProvider(
                tempDir.resolve("roc.db").toString()));
        assertEquals(2, ledger.pendingTasks("p1", 1).size());
    }

    @Test
    void 重复阶段键触发回滚_正文不入库() {
        var c = chapter("p1", 2, "正文。");
        assertThrows(RuntimeException.class, () -> repo.saveChapterEnqueuingDerivatives(c,
                List.of(ChapterPostProcessor.Stage.CONTINUITY, ChapterPostProcessor.Stage.CONTINUITY), 1),
                "同键重复入账应触发唯一约束→整体回滚");
        assertTrue(repo.findChapterByIndex("p1", 2).isEmpty(), "回滚后正文不得残留");
    }

    @Test
    void 整组任一失败一起回滚() {
        // 整组在一个事务里提交：第二章带重复阶段键 → 全组回滚
        var group = List.of(
                com.writing.novel.util.Triple.of(chapter("p1", 3, "A。"),
                        List.of(ChapterPostProcessor.Stage.CONTINUITY), 1),
                com.writing.novel.util.Triple.of(chapter("p1", 4, "B。"),
                        List.of(ChapterPostProcessor.Stage.CONTINUITY,
                                ChapterPostProcessor.Stage.CONTINUITY), 1));
        assertThrows(RuntimeException.class,
                () -> repo.saveChaptersEnqueuingDerivatives(group));
        assertTrue(repo.findChapterByIndex("p1", 3).isEmpty(), "整组回滚：A 不残留");
        assertTrue(repo.findChapterByIndex("p1", 4).isEmpty(), "整组回滚：B 不残留");
    }
}
