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
        "persistence.sqlite.db-path=./data/test-web3.db",
        "persistence.vector-store.index-path=./data/test-web3-vecs.idx"
})
class ContentControllerTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;

    @org.junit.jupiter.api.BeforeAll
    static void createDataDir() { new File("./data").mkdirs(); }

    @Test
    void 构思后查询世界观和人物() throws Exception {
        String id = createAndConceive();
        mvc.perform(get("/api/projects/" + id + "/worldbuilding"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").exists());
        mvc.perform(get("/api/projects/" + id + "/characters"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").exists());
    }

    @Test
    void 构思后查询大纲() throws Exception {
        String id = createAndConceive();
        mvc.perform(get("/api/projects/" + id + "/outline"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.premise").exists());
    }

    @Test
    void 写一章后查询章节() throws Exception {
        String id = createAndConceive();
        mvc.perform(post("/api/projects/" + id + "/run"));  // PLANNING → WRITING
        mvc.perform(post("/api/projects/" + id + "/write-chapter"));

        mvc.perform(get("/api/projects/" + id + "/chapters"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].title").exists());
    }

    private String createAndConceive() throws Exception {
        CreateProjectRequest req = new CreateProjectRequest("t", "c", "tag", "SOLO", null);
        String json = mvc.perform(post("/api/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(req)))
                .andReturn().getResponse().getContentAsString();
        String id = om.readTree(json).get("id").asText();
        mvc.perform(post("/api/projects/" + id + "/run"));
        return id;
    }
}
