package com.writing.novel.orchestrator;

import com.writing.novel.domain.Chapter;
import com.writing.novel.domain.ContinuityIssue;
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
 * B1: 章节写入事务测试——中途异常自动回滚，不留半成品。
 */
class ChapterTransactionTest {

    @TempDir
    Path tempDir;
    private SqliteNovelRepository repo;

    @BeforeEach
    void setUp() {
        ConnectionProvider cp = new ConnectionProvider(tempDir.resolve("tx.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        repo = new SqliteNovelRepository(cp);
    }

    @Test
    void 事务成功时全部提交() {
        var p = new Project("事务测", "故事", "悬疑");
        repo.saveProject(p);
        var chapter = new Chapter(p.getId(), 1, "第1章");
        chapter.setContent("正文内容".repeat(500));
        var issue = new ContinuityIssue(p.getId(), 1,
                ContinuityIssue.Type.TIMELINE, ContinuityIssue.Severity.LOW, "测试问题");

        repo.runInTransaction(conn -> {
            repo.saveChapter(conn, chapter);
            repo.saveContinuityIssue(conn, issue);
            return null;
        });

        // 两笔都应存在
        assertTrue(repo.findChapterByIndex(p.getId(), 1).isPresent(), "章节应已提交");
        assertEquals(1, repo.findUnresolvedIssues(p.getId()).size(), "问题应已提交");
    }

    @Test
    void 事务中途异常时全部回滚() {
        var p = new Project("回滚测", "故事", "悬疑");
        repo.saveProject(p);
        var chapter = new Chapter(p.getId(), 1, "第1章");
        chapter.setContent("正文内容".repeat(500));

        // 第一笔成功，第二笔抛异常 → 整体回滚
        assertThrows(RuntimeException.class, () ->
            repo.runInTransaction(conn -> {
                repo.saveChapter(conn, chapter);
                throw new RuntimeException("模拟中途失败");
            })
        );

        // 章节不应存在（已回滚）
        assertFalse(repo.findChapterByIndex(p.getId(), 1).isPresent(),
                "事务回滚后章节不应存在");
    }

    @Test
    void 事务回滚后连接恢复正常() {
        var p = new Project("恢复测", "故事", "悬疑");
        repo.saveProject(p);

        // 先一个失败的事务
        assertThrows(RuntimeException.class, () ->
            repo.runInTransaction(conn -> { throw new RuntimeException("失败"); })
        );

        // 再一个正常事务——应成功（连接池/autoCommit 恢复正常）
        var chapter = new Chapter(p.getId(), 1, "第1章");
        chapter.setContent("恢复后的正文".repeat(500));
        repo.runInTransaction(conn -> repo.saveChapter(conn, chapter));

        assertTrue(repo.findChapterByIndex(p.getId(), 1).isPresent(), "回滚后应能正常写入");
    }
}
