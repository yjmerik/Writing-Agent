package com.writing.novel.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.writing.novel.web.dto.CreateProjectRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.File;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "persistence.embedding=stub",
        "persistence.sqlite.db-path=./data/test-web.db",
        "persistence.vector-store.index-path=./data/test-web-vecs.idx"
})
class ProjectControllerTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;

    @org.junit.jupiter.api.BeforeAll
    static void createDataDir() { new File("./data").mkdirs(); }

    @Test
    void 创建项目并查询() throws Exception {
        CreateProjectRequest req = new CreateProjectRequest("API测试小说", "剑修故事", "玄幻,热血", "SOLO", null);

        String json = mvc.perform(post("/api/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("API测试小说"))
                .andExpect(jsonPath("$.mode").value("SOLO"))
                .andReturn().getResponse().getContentAsString();

        String projectId = om.readTree(json).get("id").asText();

        mvc.perform(get("/api/projects/" + projectId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("API测试小说"));
    }

    @Test
    void 查询项目列表() throws Exception {
        mvc.perform(get("/api/projects"))
                .andExpect(status().isOk());
    }

    @Test
    void 切换模式() throws Exception {
        CreateProjectRequest req = new CreateProjectRequest("t", "c", "tag", "SOLO", null);
        String json = mvc.perform(post("/api/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(req)))
                .andReturn().getResponse().getContentAsString();
        String id = om.readTree(json).get("id").asText();

        mvc.perform(put("/api/projects/" + id + "/mode")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("\"INTERACTIVE\""))
                .andExpect(status().isOk());

        mvc.perform(get("/api/projects/" + id))
                .andExpect(jsonPath("$.mode").value("INTERACTIVE"));
    }
}
