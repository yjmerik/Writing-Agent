package com.writing.novel.web;

import com.writing.novel.domain.Project;
import com.writing.novel.persistence.sqlite.ConnectionProvider;
import com.writing.novel.persistence.sqlite.SqliteNovelRepository;
import com.writing.novel.persistence.sqlite.SqliteSchemaInitializer;
import com.writing.novel.persistence.sqlite.AuthorIntentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Path;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * NCS-003（方案 §05/J01-J02）：AuthorIntent 创作约定聚合。
 * 项目创建即有空契约（无正文合法）；PUT 生成新修订并保留历史；字段校验；项目不存在 400。
 */
class AuthorIntentApiTest {

    @TempDir
    Path dir;

    private SqliteNovelRepository repo;
    private MockMvc mvc;
    private String url;

    @BeforeEach
    void setup() {
        var cp = new ConnectionProvider(dir.resolve("intent.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        repo = new SqliteNovelRepository(cp);
        var intentRepo = new AuthorIntentRepository(cp);
        Project project = new Project("约定项目", "构思", "武侠");
        repo.saveProject(project);
        url = "/api/projects/" + project.getId() + "/intent";
        mvc = MockMvcBuilders.standaloneSetup(new AuthorIntentController(repo, intentRepo))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void 新项目读取空契约合法_修订为0() throws Exception {
        mvc.perform(get(url))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projectId").exists())
                .andExpect(jsonPath("$.revision").value(0))
                .andExpect(jsonPath("$.premise").value(""))
                .andExpect(jsonPath("$.languageSamples").value(""));
    }

    @Test
    void 保存生成修订1并回读() throws Exception {
        String body = """
                {"premise":"一个关于焚信与背叛的武侠故事","genreExpectation":"武侠·成年读者·沉静克制",
                 "themes":"信任的代价","pov":"第三人称限制视角（沈昭）",
                 "languageSamples":"江砚把密信凑近烛火，纸角卷起来，像一声没出口的叹息。",
                 "lengthRange":"60-90章","autonomy":"常规措辞可自动处理，人物命运必须问我",
                 "unwantedPatterns":"解释性独白；巧合解围"}
                """;
        mvc.perform(put(url).contentType("application/json").content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(1))
                .andExpect(jsonPath("$.premise").value("一个关于焚信与背叛的武侠故事"))
                .andExpect(jsonPath("$.autonomy").value("常规措辞可自动处理，人物命运必须问我"));
        mvc.perform(get(url))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(1))
                .andExpect(jsonPath("$.themes").value("信任的代价"));
    }

    @Test
    void 再次保存生成修订2_历史保留两版() throws Exception {
        mvc.perform(put(url).contentType("application/json")
                .content("{\"premise\":\"v1 原旨\"}")).andExpect(status().isOk());
        mvc.perform(put(url).contentType("application/json")
                .content("{\"premise\":\"v2 原旨\",\"pov\":\"第一人称\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(2));
        mvc.perform(get(url)).andExpect(jsonPath("$.revision").value(2))
                .andExpect(jsonPath("$.pov").value("第一人称"));
        mvc.perform(get(url + "/history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revisions.length()").value(2))
                .andExpect(jsonPath("$.revisions[0].revision").value(1))
                .andExpect(jsonPath("$.revisions[0].premise").value("v1 原旨"));
    }

    @Test
    void 部分字段更新_未提供字段保持空_不覆盖为null() throws Exception {
        mvc.perform(put(url).contentType("application/json")
                .content("{\"premise\":\"只写原旨\"}")).andExpect(status().isOk());
        mvc.perform(put(url).contentType("application/json")
                .content("{\"themes\":\"补主题\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.premise").value("只写原旨"))
                .andExpect(jsonPath("$.themes").value("补主题"));
    }

    @Test
    void 项目不存在返回400() throws Exception {
        mvc.perform(get("/api/projects/no-such-project/intent"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/projects/no-such-project/intent")
                        .contentType("application/json").content("{\"premise\":\"x\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 超长字段拒绝() throws Exception {
        String huge = "长".repeat(9000);
        mvc.perform(put(url).contentType("application/json")
                        .content("{\"premise\":\"" + huge + "\"}"))
                .andExpect(status().isBadRequest());
    }
}
