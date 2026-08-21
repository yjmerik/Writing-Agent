package com.writing.novel.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.writing.novel.service.WritingTaskExecutor;
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

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(StubLlmGatewayTestConfig.class)
@TestPropertySource(properties = {
        "persistence.embedding=stub",
        "persistence.sqlite.db-path=./data/test-hitl-create.db",
        "persistence.vector-store.index-path=./data/test-hitl-create-vecs.idx"
})
class HitlCreateWorldCharacterTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;
    @Autowired WritingTaskExecutor writingTaskExecutor;

    @BeforeAll
    static void createDataDir() { new File("./data").mkdirs(); }

    @Test
    void postWorldbuilding_addsEntryAndHumanPatchFact() throws Exception {
        String id = createProject();
        mvc.perform(post("/api/projects/" + id + "/worldbuilding")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"FACTION\",\"name\":\"承平司\",\"content\":\"阴阳账目交割衙门\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("承平司"));

        mvc.perform(get("/api/projects/" + id + "/worldbuilding"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.name=='承平司')]").exists());

        mvc.perform(get("/api/projects/" + id + "/facts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].assertion", org.hamcrest.Matchers.hasItem(containsString("【HUMAN_PATCH】"))))
                .andExpect(jsonPath("$[*].assertion", org.hamcrest.Matchers.hasItem(containsString("承平司"))));
    }

    @Test
    void postCharacter_addsCardAndCanon() throws Exception {
        String id = createProject();
        mvc.perform(post("/api/projects/" + id + "/characters")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"柳氏\",\"role\":\"配角\",\"ability\":\"识契\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("柳氏"));

        mvc.perform(get("/api/projects/" + id + "/characters"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.name=='柳氏')].ability").value(org.hamcrest.Matchers.hasItem("识契")));
    }

    @Test
    void postWorld_conflictWhenWriteTaskActive() throws Exception {
        String id = createProject();
        writingTaskExecutor.beginWork(id, "write-chapter");
        try {
            mvc.perform(post("/api/projects/" + id + "/worldbuilding")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"type\":\"RULE\",\"name\":\"x\",\"content\":\"y\"}"))
                    .andExpect(status().isConflict());
        } finally {
            writingTaskExecutor.endWork(id, "write-chapter");
        }
    }

    private String createProject() throws Exception {
        CreateProjectRequest req = new CreateProjectRequest("hitl", "c", "tag", "SOLO", null);
        String json = mvc.perform(post("/api/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(req)))
                .andReturn().getResponse().getContentAsString();
        return om.readTree(json).get("id").asText();
    }
}
