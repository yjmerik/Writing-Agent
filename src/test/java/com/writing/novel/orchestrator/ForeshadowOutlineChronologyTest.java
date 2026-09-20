package com.writing.novel.orchestrator;

import com.writing.novel.domain.ChapterSynopsis;
import com.writing.novel.domain.Foreshadowing;
import com.writing.novel.domain.Outline;
import com.writing.novel.domain.Project;
import com.writing.novel.domain.Volume;
import com.writing.novel.persistence.sqlite.ConnectionProvider;
import com.writing.novel.persistence.sqlite.SqliteNovelRepository;
import com.writing.novel.persistence.sqlite.SqliteSchemaInitializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** Regression coverage for outline foreshadowing chronology and deduplication. */
class ForeshadowOutlineChronologyTest {

    @TempDir
    Path tempDir;
    private SqliteNovelRepository repo;
    private NovelOrchestrator orchestrator;
    private String projectId;

    @BeforeEach
    void setUp() {
        ConnectionProvider cp = new ConnectionProvider(tempDir.resolve("foreshadow.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        repo = new SqliteNovelRepository(cp);
        Project project = new Project("伏笔时间线", "测试", "悬疑");
        repo.saveProject(project);
        projectId = project.getId();
        orchestrator = new NovelOrchestrator(repo, new ProjectService(repo),
                null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    @Test
    void 按章节保存来源和预计回收章且回收行不重复建账() throws Exception {
        Outline outline = new Outline(projectId, "悬疑总纲");
        Volume volume = new Volume(projectId, 1, "第一卷", "");
        volume.addSynopsis(new ChapterSynopsis(projectId, 2, "水碗无齿算盘",
                "场景节拍：验井尸。伏笔：埋设-陆三行墓志铭收一文（MEDIUM，预计第5章回收）。"));
        volume.addSynopsis(new ChapterSynopsis(projectId, 5, "假无漏",
                "场景节拍：浮第二尸。伏笔：回收-陆三行墓志铭。"));
        outline.addVolume(volume);
        repo.saveOutline(outline);

        invokeExtract(projectId);

        var foreshadowings = repo.findForeshadowingsByProject(projectId);
        assertEquals(1, foreshadowings.size(), "回收行只能关联已有伏笔，不能再建一条未回收记录");
        Foreshadowing saved = foreshadowings.get(0);
        assertEquals(2, saved.getPlantedAtChapter(), "来源章必须来自章节梗概");
        assertEquals(Integer.valueOf(5), saved.getExpectedResolveChapter(), "预计回收章必须保留");
        assertFalse(saved.isResolved(), "大纲计划的回收不等同于正文已经回收");
    }

    @Test
    void 没有章节来源的总纲伏笔不伪装成第一章已埋设() throws Exception {
        Outline outline = new Outline(projectId, "总纲中提到伏笔：未知线索，但没有章节归属。");
        outline.addVolume(new Volume(projectId, 1, "第一卷", ""));
        repo.saveOutline(outline);

        invokeExtract(projectId);

        assertTrue(repo.findForeshadowingsByProject(projectId).isEmpty(),
                "没有来源章号的规划不能伪装成第1章伏笔");
    }

    private void invokeExtract(String id) throws Exception {
        Method method = NovelOrchestrator.class.getDeclaredMethod(
                "extractForeshadowingsFromOutline", String.class);
        method.setAccessible(true);
        method.invoke(orchestrator, id);
    }
}
