package com.writing.novel.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.writing.novel.web.dto.CreateProjectRequest;
import org.junit.jupiter.api.BeforeAll;
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
        "persistence.sqlite.db-path=./data/test-canon-api.db",
        "persistence.vector-store.index-path=./data/test-canon-api-vecs.idx"
})
class CanonWriteApiTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;

    @BeforeAll
    static void createDataDir() { new File("./data").mkdirs(); }

    @Test
    void put人物与世界观与facts走补丁() throws Exception {
        String id = createAndConceive();

        String chars = mvc.perform(get("/api/projects/" + id + "/characters"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String charId = om.readTree(chars).get(0).get("id").asText();

        mvc.perform(put("/api/projects/" + id + "/characters/" + charId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ability\":\"金丹期\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ability").value("金丹期"));

        mvc.perform(get("/api/projects/" + id + "/facts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].assertion").value(org.hamcrest.Matchers.containsString("【HUMAN_PATCH】")));

        String world = mvc.perform(get("/api/projects/" + id + "/worldbuilding"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String entryId = om.readTree(world).get(0).get("id").asText();
        mvc.perform(put("/api/projects/" + id + "/worldbuilding/" + entryId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"灵气\",\"content\":\"新规则\",\"type\":\"POWER_SYSTEM\"}"))
                .andExpect(status().isOk());

        mvc.perform(post("/api/projects/" + id + "/facts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"factType\":\"PLOT_COMMITMENT\",\"subject\":\"承诺\",\"assertion\":\"主角不弃剑\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assertion").value(org.hamcrest.Matchers.containsString("主角不弃剑")));
    }

    private String createAndConceive() throws Exception {
        CreateProjectRequest req = new CreateProjectRequest("canon", "c", "tag", "SOLO", null);
        String json = mvc.perform(post("/api/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(req)))
                .andReturn().getResponse().getContentAsString();
        String id = om.readTree(json).get("id").asText();
        RunTaskSupport.runAndAwait(mvc, om, id);
        return id;
    }
}
