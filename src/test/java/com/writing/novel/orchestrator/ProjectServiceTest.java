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

    /**
     * NCS-188（bug 复现）：preset 创建项目时 genre 已被直接设置，导致
     * NovelOrchestrator 仅在 genre==null 推断分支应用的题材默认 maxBeatsPerChapter
     * 从未生效——历史权谋项目应得 3 而不是通用默认 4。
     */
    @Test
    void 历史预设创建项目应用题材节拍上限() {
        var presets = new com.writing.novel.service.PresetRegistry();
        var withRules = new com.writing.novel.orchestrator.ProjectService(
                repo, presets, new com.writing.novel.service.GenreRuleProvider());
        Project p = withRules.create("权谋", "庙堂之争", "历史,权谋", ProjectMode.SOLO, "historical");

        assertEquals(com.writing.novel.domain.GenreProfile.HISTORICAL, p.getGenre());
        assertEquals(3, repo.findWritingPreferences(p.getId()).orElseThrow().getMaxBeatsPerChapter(),
                "HISTORICAL 题材默认 maxBeats 应为 3（信息密度高的题材）");
    }
}
