package com.writing.novel.service;

import com.writing.novel.domain.*;
import com.writing.novel.orchestrator.ChapterProduction;
import com.writing.novel.persistence.sqlite.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * NCS-022（方案 §13/F17）：作者审美档案。
 * 认可片段（+理由）/减少表达/偏好维度版本化存储；条件注入：有档案条目才追加
 * 【作者审美档案】段（无档案注入不变——指纹稳定）；API 读写。
 */
class AestheticProfileTest {

    @TempDir
    Path tempDir;

    private SqliteNovelRepository repo;
    private AestheticProfileRepository profileRepo;
    private AestheticProfileService service;
    private String projectId;

    @BeforeEach
    void setUp() {
        var cp = new ConnectionProvider(tempDir.resolve("aesthetic.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        repo = new SqliteNovelRepository(cp);
        profileRepo = new AestheticProfileRepository(cp);
        service = new AestheticProfileService(profileRepo);
        Project project = new Project("审美项目", "构思", "武侠");
        repo.saveProject(project);
        projectId = project.getId();
        Outline outline = new Outline(projectId, "总纲");
        Volume v = new Volume(projectId, 1, "第一卷", "卷摘要");
        v.getSynopses().add(new ChapterSynopsis(projectId, 8, "第八章", "渡口焚信"));
        outline.getVolumes().add(v);
        repo.saveOutline(outline);
    }

    @Test
    void 空档案合法_保存生成修订_字段完整() throws Exception {
        // 初始空档案（修订 0）合法
        assertEquals(0, service.current(projectId).getRevision());

        String body = """
                {"likedSnippets":[{"chapterRef":"第 8 章","excerpt":"江砚把密信凑近烛火，纸角卷起来","reason":"动作代替了对痛苦的直接描写"}],
                 "dislikedPatterns":"解释性独白；巧合解围",
                 "narrativeDistance":"贴近但不钻进人物内心说破",
                 "languageDensity":"中等，留白多于铺陈",
                 "humorStyle":"冷幽默，不刻意",
                 "imageryPreference":"渡口水汽、火光、旧纸的质感"}
                """;
        MockMvc mvc = MockMvcBuilders.standaloneSetup(
                        new com.writing.novel.web.AestheticProfileController(repo, service))
                .setControllerAdvice(new com.writing.novel.web.GlobalExceptionHandler())
                .build();
        mvc.perform(put("/api/projects/" + projectId + "/aesthetic-profile")
                        .contentType("application/json").content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(1))
                .andExpect(jsonPath("$.likedSnippets[0].reason").value("动作代替了对痛苦的直接描写"));
        // 再次保存 → 修订 2
        mvc.perform(put("/api/projects/" + projectId + "/aesthetic-profile")
                        .contentType("application/json").content(body))
                .andExpect(jsonPath("$.revision").value(2));
        // GET 读回
        mvc.perform(get("/api/projects/" + projectId + "/aesthetic-profile"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dislikedPatterns").value("解释性独白；巧合解围"))
                .andExpect(jsonPath("$.imageryPreference").value("渡口水汽、火光、旧纸的质感"));
    }


    @Test
    void 项目不存在400() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(
                        new com.writing.novel.web.AestheticProfileController(repo, service))
                .setControllerAdvice(new com.writing.novel.web.GlobalExceptionHandler())
                .build();
        mvc.perform(get("/api/projects/no-such/aesthetic-profile"))
                .andExpect(status().isBadRequest());
    }
}
