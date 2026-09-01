package com.writing.novel.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.writing.novel.domain.Entity;
import com.writing.novel.domain.EntityType;
import com.writing.novel.spi.NovelRepository;
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

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(StubLlmGatewayTestConfig.class)
@TestPropertySource(properties = {
        "persistence.embedding=stub",
        "persistence.sqlite.db-path=./data/test-hitl-expand.db",
        "persistence.vector-store.index-path=./data/test-hitl-expand-vecs.idx"
})
class HitlExpandAgentsTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;
    @Autowired NovelRepository repository;

    @BeforeAll
    static void createDataDir() { new File("./data").mkdirs(); }

    @Test
    void expandCharacters_doesNotOverwriteExistingAbility() throws Exception {
        String id = createProject();
        String createdJson = mvc.perform(post("/api/projects/" + id + "/characters")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"柳氏\",\"role\":\"配角\",\"ability\":\"识契\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        var created = om.readTree(createdJson);
        String keptId = created.get("id").asText();
        String keptAbility = created.path("ability").asText();

        mvc.perform(post("/api/projects/" + id + "/expand/characters")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"instruction\":\"补充一名识局的师爷\"}"))
                .andExpect(status().isOk());

        String chars = mvc.perform(get("/api/projects/" + id + "/characters"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(greaterThanOrEqualTo(2)))
                .andReturn().getResponse().getContentAsString();
        var arr = om.readTree(chars);
        boolean kept = false;
        for (var n : arr) {
            if (keptId.equals(n.path("id").asText())) {
                kept = keptAbility.equals(n.path("ability").asText());
            }
        }
        org.junit.jupiter.api.Assertions.assertTrue(kept, "existing character ability must not change");
    }

    @Test
    void promoteEntity_createsCharacterCard() throws Exception {
        String id = createProject();
        Entity e = new Entity(id, "赵三", EntityType.CHARACTER);
        e.seenAt(1);
        repository.saveEntity(e);

        mvc.perform(post("/api/projects/" + id + "/characters/promote/" + e.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("赵三"));

        mvc.perform(get("/api/projects/" + id + "/characters"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].name", hasItem("赵三")));
    }

    private String createProject() throws Exception {
        CreateProjectRequest req = new CreateProjectRequest("exp", "c", "tag", "SOLO", null);
        String json = mvc.perform(post("/api/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(req)))
                .andReturn().getResponse().getContentAsString();
        return om.readTree(json).get("id").asText();
    }
}
