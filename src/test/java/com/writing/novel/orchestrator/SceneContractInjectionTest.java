package com.writing.novel.orchestrator;

import com.writing.novel.domain.*;
import com.writing.novel.persistence.sqlite.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * NCS-018: 场景合同条件注入——有合同追加【场景合同】段，无合同注入不变（指纹稳定）。
 */
class SceneContractInjectionTest {

    @TempDir
    Path tempDir;

    private SqliteNovelRepository repo;
    private String projectId;
    private ChapterSynopsis synopsis;
    private ChapterProduction production;

    @BeforeEach
    void setUp() {
        var cp = new ConnectionProvider(tempDir.resolve("inject.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        repo = new SqliteNovelRepository(cp);
        Project project = new Project("注入项目", "构思", "武侠");
        repo.saveProject(project);
        projectId = project.getId();
        Outline outline = new Outline(projectId, "总纲");
        Volume v = new Volume(projectId, 1, "第一卷", "卷摘要");
        synopsis = new ChapterSynopsis(projectId, 8, "第八章", "渡口焚信");
        synopsis.setWritingNotes("保持克制");
        v.getSynopses().add(synopsis);
        outline.getVolumes().add(v);
        repo.saveOutline(outline);
        production = new ChapterProduction(null, repo, null, null, null, null, null,
                null, null, null, null, null, null);
    }

    @Test
    void 无合同注入不变() {
        String plain = production.prepare(projectId, 8, synopsis, null);
        assertFalse(plain.contains("【场景合同"), "无合同的梗概注入不变（指纹稳定）");
        assertTrue(plain.contains("保持克制"));
    }

    @Test
    void 有合同追加场景合同段() {
        synopsis.setSceneContract("{\"purpose\":\"让焚信成为信任裂口\"}");
        String with = production.prepare(projectId, 8, synopsis, null);
        assertTrue(with.contains("【场景合同（本章必须成立的因果约束）】"));
        assertTrue(with.contains("让焚信成为信任裂口"));
        assertTrue(with.endsWith("让焚信成为信任裂口\"}"), "合同 JSON 原文进入确定性输入通道（写作注记）");
    }
}
