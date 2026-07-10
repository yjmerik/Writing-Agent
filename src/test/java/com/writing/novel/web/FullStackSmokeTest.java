package com.writing.novel.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.writing.novel.web.dto.CreateProjectRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.File;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Import(StubLlmGatewayTestConfig.class)
@TestPropertySource(properties = {
        "persistence.embedding=stub",
        "persistence.sqlite.db-path=./data/test-full.db",
        "persistence.vector-store.index-path=./data/test-full-vecs.idx"
})
class FullStackSmokeTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;

    @org.junit.jupiter.api.BeforeAll
    static void createDataDir() { new File("./data").mkdirs(); }

    @Test
    void 全链路_创建_构思_规划_写章节_查询() throws Exception {
        // 1. 创建项目
        CreateProjectRequest req = new CreateProjectRequest("全链路测试", "剑修复仇玄幻故事", "玄幻,热血", "SOLO");
        String json = mvc.perform(post("/api/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String id = om.readTree(json).get("id").asText();

        // 2. 运行构思（CONCEIVING → PLANNING）
        mvc.perform(post("/api/projects/" + id + "/run"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/projects/" + id + "/status"))
                .andExpect(jsonPath("$.state").value("PLANNING"));

        // 3. 推进到写作（PLANNING → WRITING）
        mvc.perform(post("/api/projects/" + id + "/run"));
        mvc.perform(get("/api/projects/" + id + "/status"))
                .andExpect(jsonPath("$.state").value("WRITING"));

        // 4. 写第一章
        mvc.perform(post("/api/projects/" + id + "/write-chapter"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chapterIndex").value(1));

        // 5. 查询世界观、人物、大纲、章节
        mvc.perform(get("/api/projects/" + id + "/worldbuilding"))
                .andExpect(jsonPath("$[0]").exists());
        mvc.perform(get("/api/projects/" + id + "/characters"))
                .andExpect(jsonPath("$[0]").exists());
        mvc.perform(get("/api/projects/" + id + "/outline"))
                .andExpect(jsonPath("$.premise").exists());
        mvc.perform(get("/api/projects/" + id + "/chapters"))
                .andExpect(jsonPath("$[0].chapterIndex").value(1));
    }
}
