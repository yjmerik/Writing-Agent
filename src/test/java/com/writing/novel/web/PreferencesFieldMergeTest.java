package com.writing.novel.web;

import com.writing.novel.spi.NovelRepository;
import com.writing.novel.persistence.sqlite.ConnectionProvider;
import com.writing.novel.persistence.sqlite.SqliteNovelRepository;
import com.writing.novel.persistence.sqlite.SqliteSchemaInitializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Path;

import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * NCS-147（旅程三 F-5 复盘）：偏好字段级合并回归——
 * 前端开关逐字段 PUT（部分字段），后端 read-modify-write 必须保留未提供字段；
 * 旅程实测单字段 PUT enableKnowledgeQuotes 直调成功，产品缺陷在前端静默吞错（另行修复）。
 */
class PreferencesFieldMergeTest {

    @TempDir Path tempDir;
    MockMvc mvc;
    String pid;

    @BeforeEach
    void setUp() {
        var cp = new ConnectionProvider(tempDir.resolve("prefs.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        NovelRepository repo = new SqliteNovelRepository(cp);
        var project = new com.writing.novel.domain.Project("书", "构思", "修真");
        repo.saveProject(project);
        pid = project.getId();
        mvc = MockMvcBuilders.standaloneSetup(new PreferencesController(repo, mock(
                com.writing.novel.agent.WritingPreferencesAgent.class)))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    void 单字段PUT不清空其它字段_旅程场景回归() throws Exception {
        // 旅程序列：开关 PUT enableKnowledgeQuotes=true 成功；随后另一控件 PUT dialogueDensity
        mvc.perform(put("/api/projects/" + pid + "/preferences")
                        .contentType("application/json")
                        .content("{\"enableKnowledgeQuotes\": true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enableKnowledgeQuotes").value(true));
        mvc.perform(put("/api/projects/" + pid + "/preferences")
                        .contentType("application/json")
                        .content("{\"dialogueDensity\": 0.6}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dialogueDensity").value(0.6))
                .andExpect(jsonPath("$.enableKnowledgeQuotes").value(true))
                .andExpect(jsonPath("$.enableCharacterVoice").value(true))
                .andExpect(jsonPath("$.targetChapters").value(24));
        mvc.perform(get("/api/projects/" + pid + "/preferences"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enableKnowledgeQuotes").value(true))
                .andExpect(jsonPath("$.dialogueDensity").value(0.6));
    }

    @Test
    void 首次PUT创建默认偏好并应用提供字段() throws Exception {
        mvc.perform(put("/api/projects/" + pid + "/preferences")
                        .contentType("application/json")
                        .content("{\"enableClimaxRotation\": false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enableClimaxRotation").value(false))
                .andExpect(jsonPath("$.targetChapters").value(24));
    }
}
