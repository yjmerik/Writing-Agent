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
        "persistence.sqlite.db-path=./data/test-web2.db",
        "persistence.vector-store.index-path=./data/test-web2-vecs.idx"
})
class OrchestratorControllerTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;

    @org.junit.jupiter.api.BeforeAll
    static void createDataDir() { new File("./data").mkdirs(); }

    private String createProject() throws Exception {
        CreateProjectRequest req = new CreateProjectRequest("t", "c", "tag", "SOLO");
        String json = mvc.perform(post("/api/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(req)))
                .andReturn().getResponse().getContentAsString();
        return om.readTree(json).get("id").asText();
    }

    @Test
    void 运行构思阶段并查询状态() throws Exception {
        String id = createProject();
        mvc.perform(post("/api/projects/" + id + "/run"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/projects/" + id + "/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("PLANNING"));
    }
}
