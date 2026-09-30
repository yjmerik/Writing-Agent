package com.writing.novel.orchestrator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.writing.novel.domain.Chapter;
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

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * NCS-151（旅程三 O-5，用户决策：卷边界应有前台呈现）：
 * 卷末章的检查点 payload 携带 volumeSummary，非卷末章不带。
 */
class VolumeBoundarySummaryTest {

    @TempDir Path dir;
    SqliteNovelRepository repo;
    Project project;
    Outline outline;

    @BeforeEach
    void setUp() {
        var cp = new ConnectionProvider(dir.resolve("vb.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        repo = new SqliteNovelRepository(cp);
        project = new Project("书", "构思", "修真");
        repo.saveProject(project);

        outline = new Outline(project.getId(), "凡人问剑天道");
        Volume v1 = new Volume(project.getId(), 1, "无名更漏", "卷一梗概");
        for (int i = 1; i <= 3; i++) {
            v1.addSynopsis(new ChapterSynopsis(project.getId(), i, "章" + i, "梗概" + i));
        }
        Volume v2 = new Volume(project.getId(), 2, "问剑会", "卷二梗概");
        v2.addSynopsis(new ChapterSynopsis(project.getId(), 4, "卷二章", "卷二唯一章"));
        outline.getVolumes().add(v1);
        outline.getVolumes().add(v2);
        repo.saveOutline(outline);

        for (int i = 1; i <= 3; i++) {
            Chapter c = new Chapter(project.getId(), i, "第" + i + "章");
            c.setContent("卷一内容。".repeat(100)); // 500 字
            repo.saveChapter(c);
        }

        repo.saveForeshadowing(new Foreshadowing(project.getId(), "卷一埋设", "上下文", 2));
        Foreshadowing resolved = new Foreshadowing(project.getId(), "卷一回收", "上下文", 1);
        resolved.resolve(3, "测试回收");
        repo.saveForeshadowing(resolved);
    }

    @Test
    void 卷末章payload携带卷小结() throws Exception {
        var root = new ObjectMapper().createObjectNode();
        VolumeBoundarySupport.appendSummaryIfVolumeEnd(repo, outline, 3, root);
        assertTrue(root.has("volumeSummary"), "卷末章必须携带 volumeSummary");
        var vs = root.get("volumeSummary");
        assertEquals(1, vs.get("volumeIndex").asInt());
        assertEquals("无名更漏", vs.get("volumeTitle").asText());
        assertEquals(1, vs.get("firstChapter").asInt());
        assertEquals(3, vs.get("lastChapter").asInt());
        assertTrue(vs.get("totalWords").asLong() > 0, "累计字数必须统计");
        assertEquals(2, vs.get("foreshadowPlanted").asLong(), "卷内埋设伏笔数");
        assertEquals(1, vs.get("foreshadowResolved").asLong(), "卷内回收伏笔数");
    }

    @Test
    void 非卷末章不带卷小结() {
        var root = new ObjectMapper().createObjectNode();
        VolumeBoundarySupport.appendSummaryIfVolumeEnd(repo, outline, 2, root);
        assertFalse(root.has("volumeSummary"), "非卷末章不得携带 volumeSummary");
    }

    @Test
    void 卷二章末同样识别() throws Exception {
        var root = new ObjectMapper().createObjectNode();
        VolumeBoundarySupport.appendSummaryIfVolumeEnd(repo, outline, 4, root);
        assertTrue(root.has("volumeSummary"));
        assertEquals(2, root.get("volumeSummary").get("volumeIndex").asInt());
        assertEquals("问剑会", root.get("volumeSummary").get("volumeTitle").asText());
    }
}
