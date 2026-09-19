package com.writing.novel.orchestrator;

import com.writing.novel.domain.*;
import com.writing.novel.persistence.sqlite.*;
import com.writing.novel.service.AestheticProfileService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * NCS-022: 审美档案条件注入——有档案追加【作者审美档案】段，空档案注入不变（指纹稳定）。
 */
class AestheticInjectionTest {

    @TempDir
    Path tempDir;

    private SqliteNovelRepository repo;
    private AestheticProfileService service;
    private ChapterProduction production;
    private String projectId;

    @BeforeEach
    void setUp() {
        var cp = new ConnectionProvider(tempDir.resolve("inject.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        repo = new SqliteNovelRepository(cp);
        service = new AestheticProfileService(new AestheticProfileRepository(cp));
        Project project = new Project("注入项目", "构思", "武侠");
        repo.saveProject(project);
        projectId = project.getId();
        Outline outline = new Outline(projectId, "总纲");
        Volume v = new Volume(projectId, 1, "第一卷", "卷摘要");
        v.getSynopses().add(new ChapterSynopsis(projectId, 8, "第八章", "渡口焚信"));
        outline.getVolumes().add(v);
        repo.saveOutline(outline);
        production = new ChapterProduction(null, repo, null, null, null, null, null,
                null, null, null, null, null, null);
    }

    @Test
    void 空档案注入不变() {
        production.setAestheticInjection(service::buildInjection);
        String plain = production.prepare(projectId, 8,
                repo.findOutlineByProject(projectId).orElseThrow().getVolumes().get(0).getSynopses().get(0), null);
        assertFalse(plain.contains("【作者审美档案"), "空档案注入不变（指纹稳定）");
    }

    @Test
    void 有档案追加审美段() {
        var profile = new AestheticProfile();
        profile.setProjectId(projectId);
        profile.setLikedSnippets(List.of(new AestheticProfile.LikedSnippet("第 8 章",
                "纸角卷起来", "动作代替直接描写")));
        profile.setDislikedPatterns("解释性独白");
        service.save(profile);
        production.setAestheticInjection(service::buildInjection);
        String with = production.prepare(projectId, 8,
                repo.findOutlineByProject(projectId).orElseThrow().getVolumes().get(0).getSynopses().get(0), null);
        assertTrue(with.contains("【作者审美档案"));
        assertTrue(with.contains("动作代替直接描写"));
        assertTrue(with.contains("解释性独白"));
    }
}
