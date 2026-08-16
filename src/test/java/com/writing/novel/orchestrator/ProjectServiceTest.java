package com.writing.novel.orchestrator;

import com.writing.novel.domain.Project;
import com.writing.novel.domain.ProjectMode;
import com.writing.novel.domain.NovelState;
import com.writing.novel.persistence.sqlite.ConnectionProvider;
import com.writing.novel.persistence.sqlite.SqliteNovelRepository;
import com.writing.novel.persistence.sqlite.SqliteSchemaInitializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ProjectServiceTest {

    @TempDir Path tempDir;
    private ProjectService service;
    private SqliteNovelRepository repo;

    @BeforeEach
    void setUp() {
        ConnectionProvider cp = new ConnectionProvider(tempDir.resolve("p.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        repo = new SqliteNovelRepository(cp);
        service = new ProjectService(repo);
    }

    @Test
    void 创建项目默认Solo模式构思状态() {
        Project p = service.create("我的小说", "一个剑修故事", "玄幻,热血");

        assertNotNull(p.getId());
        assertEquals("我的小说", p.getTitle());
        assertEquals(ProjectMode.SOLO, p.getMode());
        assertEquals(NovelState.CONCEIVING, p.getState());
    }

    @Test
    void 创建项目可指定交互模式() {
        Project p = service.create("t", "c", "tag", ProjectMode.INTERACTIVE);
        assertEquals(ProjectMode.INTERACTIVE, p.getMode());
    }

    @Test
    void 切换运行模式() {
        Project p = service.create("t", "c", "tag");
        service.switchMode(p.getId(), ProjectMode.INTERACTIVE);

        Project found = service.find(p.getId()).orElseThrow();
        assertEquals(ProjectMode.INTERACTIVE, found.getMode());
    }

    @Test
    void 创建时可指定篇幅写入偏好() {
        Project p = service.create("t", "c", "tag", ProjectMode.SOLO, null, 400);
        assertEquals(400, repo.findWritingPreferences(p.getId()).orElseThrow().getTargetChapters());
        Project shortP = service.create("s", "c", "tag");
        assertTrue(repo.findWritingPreferences(shortP.getId()).isEmpty()
                || repo.findWritingPreferences(shortP.getId()).orElseThrow().getTargetChapters() == 24);
    }
}
