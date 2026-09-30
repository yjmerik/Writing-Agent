package com.writing.novel.web;

import com.writing.novel.domain.Chapter;
import com.writing.novel.domain.ChapterSynopsis;
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

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * NCS-150（旅程三 O-1，用户决策：大纲自由规划，不限制目标章数）：
 * 大纲已规划时进度分母=规划章数——自由规划不再呈现"18/24 欠账"错觉；
 * 已写章数超过规划（终局回收卷扩写）时回退配置目标。
 */
class OutlinePlannedProgressTest {

    @TempDir Path dir;
    SqliteNovelRepository repo;
    Project project;

    @BeforeEach
    void setUp() {
        var cp = new ConnectionProvider(dir.resolve("pp.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        repo = new SqliteNovelRepository(cp);
        project = new Project("书", "构思", "修真");
        project.setState(NovelState.WRITING);
        repo.saveProject(project);
    }

    private void saveOutlineWithPlanned(int planned) {
        Outline outline = new Outline(project.getId(), "凡人问剑");
        Volume v1 = new Volume(project.getId(), 1, "卷一", "");
        for (int i = 1; i <= planned; i++) {
            v1.addSynopsis(new ChapterSynopsis(project.getId(), i, "章" + i, "梗概"));
        }
        outline.getVolumes().add(v1);
        repo.saveOutline(outline);
    }

    private void saveCurrentChapter(int index) {
        Chapter c = new Chapter(project.getId(), index, "第" + index + "章");
        c.setContent("正文。".repeat(50));
        repo.saveChapter(c);
        project.setCurrentChapterIndex(index);
        repo.saveProject(project);
    }

    @Test
    void 大纲已规划时分母用规划章数() {
        saveOutlineWithPlanned(18);
        saveCurrentChapter(6);
        var progress = ProjectProgressSupport.forProject(project, repo);
        assertEquals(18, progress.targetChapters(), "进度分母必须是规划章数而非配置目标");
        assertEquals(33, progress.progressPercent(), "6/18 ≈ 33%");
    }

    @Test
    void 已写超过规划时回退配置目标() {
        saveOutlineWithPlanned(18);
        saveCurrentChapter(20); // 终局回收卷扩写超过规划
        var progress = ProjectProgressSupport.forProject(project, repo);
        assertEquals(24, progress.targetChapters(), "超出规划后回退配置目标");
    }

    @Test
    void 无大纲时用配置目标() {
        saveCurrentChapter(3);
        var progress = ProjectProgressSupport.forProject(project, repo);
        assertEquals(24, progress.targetChapters());
    }
}
