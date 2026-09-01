package com.writing.novel.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.writing.novel.domain.NovelState;
import com.writing.novel.domain.Project;
import com.writing.novel.domain.ProjectMode;
import com.writing.novel.domain.WritingPreferences;
import com.writing.novel.orchestrator.NovelOrchestrator;
import com.writing.novel.orchestrator.ProjectService;
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

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(StubLlmGatewayTestConfig.class)
@TestPropertySource(properties = {
        "persistence.embedding=stub",
        "persistence.sqlite.db-path=./data/test-checkpoint-span.db",
        "persistence.vector-store.index-path=./data/test-checkpoint-span-vecs.idx"
})
class CheckpointSpanTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;
    @Autowired NovelOrchestrator orchestrator;
    @Autowired ProjectService projectService;
    @Autowired NovelRepository repository;

    @BeforeAll
    static void createDataDir() { new File("./data").mkdirs(); }

    @Test
    void putPreferences_updatesSpanWithoutChat() throws Exception {
        String id = createProject();
        mvc.perform(put("/api/projects/" + id + "/preferences")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dialogueDensity\":0.5,\"checkpointSpan\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dialogueDensity").value(0.5))
                .andExpect(jsonPath("$.checkpointSpan").value(0));

        mvc.perform(get("/api/projects/" + id + "/preferences"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.checkpointSpan").value(0));
    }

    @Test
    void spanZero_writeDoesNotAutoPause() {
        Project project = projectService.create("span", "c", "tag", ProjectMode.INTERACTIVE);
        WritingPreferences prefs = repository.findWritingPreferences(project.getId())
                .orElseGet(() -> new WritingPreferences(project.getId()));
        prefs.setCheckpointSpan(0);
        repository.saveWritingPreferences(prefs);

        orchestrator.runConceiving(project.getId());
        orchestrator.step(project.getId());
        orchestrator.step(project.getId());
        orchestrator.runWritingOneChapter(project.getId());

        assertNotEquals(NovelState.PAUSED_AT_CHECKPOINT,
                repository.findProject(project.getId()).orElseThrow().getState());
    }

    private String createProject() throws Exception {
        CreateProjectRequest req = new CreateProjectRequest("span", "c", "tag", "SOLO", null);
        String json = mvc.perform(post("/api/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(req)))
                .andReturn().getResponse().getContentAsString();
        return om.readTree(json).get("id").asText();
    }
}
