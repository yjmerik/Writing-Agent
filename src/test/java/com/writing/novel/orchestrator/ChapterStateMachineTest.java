package com.writing.novel.orchestrator;

import com.writing.novel.domain.Chapter;
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
 * p10-w1-06: 章节状态机——Chapter.status 字段持久化往返 + 默认值兼容。
 * 状态机：DRAFTING → POST_PROCESSING → DONE；后处理失败停 POST_PROCESSING（可观测/可重跑）。
 */
class ChapterStateMachineTest {

    @TempDir
    Path tempDir;
    private SqliteNovelRepository repo;
    private String pid;

    @BeforeEach
    void setUp() {
        ConnectionProvider cp = new ConnectionProvider(tempDir.resolve("sm.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        repo = new SqliteNovelRepository(cp);
        Project p = new Project("状态机书", "测试", "玄幻");
        repo.saveProject(p);
        pid = p.getId();
    }

    @Test
    void 新建章节默认DONE状态_兼容旧库语义() {
        Chapter c = new Chapter(pid, 1, "第一章");
        assertEquals(Chapter.Status.DONE, c.getStatus(), "新建章节默认 DONE（兼容旧库）");
    }

    @Test
    void status字段持久化往返() {
        Chapter c = new Chapter(pid, 1, "第一章");
        c.setContent("正文内容".repeat(100));
        c.setStatus(Chapter.Status.POST_PROCESSING);
        repo.saveChapter(c);

        Chapter loaded = repo.findChapterByIndex(pid, 1).orElseThrow();
        assertEquals(Chapter.Status.POST_PROCESSING, loaded.getStatus(),
                "POST_PROCESSING 状态应往返持久化");

        loaded.setStatus(Chapter.Status.DONE);
        repo.saveChapter(loaded);
        Chapter reloaded = repo.findChapterByIndex(pid, 1).orElseThrow();
        assertEquals(Chapter.Status.DONE, reloaded.getStatus(), "DONE 状态应往返持久化");
    }

    @Test
    void NEEDS_MANUAL_REVIEW状态可标记_闭环修复用() {
        Chapter c = new Chapter(pid, 1, "第一章");
        c.setContent("正文".repeat(100));
        c.setStatus(Chapter.Status.NEEDS_MANUAL_REVIEW);
        repo.saveChapter(c);
        assertEquals(Chapter.Status.NEEDS_MANUAL_REVIEW,
                repo.findChapterByIndex(pid, 1).orElseThrow().getStatus());
    }
}
