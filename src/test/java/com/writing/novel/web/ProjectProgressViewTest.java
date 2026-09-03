package com.writing.novel.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.writing.novel.domain.Checkpoint;
import com.writing.novel.domain.NovelState;
import com.writing.novel.domain.Project;
import com.writing.novel.domain.ProjectMode;
import com.writing.novel.orchestrator.ProjectService;
import com.writing.novel.spi.NovelRepository;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** p26-17: progress and paused stage are server facts, not frontend guesses. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(StubLlmGatewayTestConfig.class)
@TestPropertySource(properties = {
        "persistence.embedding=stub",
        "persistence.sqlite.db-path=./data/test-project-progress-view.db",
        "persistence.vector-store.index-path=./data/test-project-progress-view-vecs.idx"
})
class ProjectProgressViewTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired NovelRepository repository;
    @Autowired ProjectService projectService;

    @BeforeAll
    static void createDataDir() {
        new File("./data").mkdirs();
    }

    @Test
    void statusMapsPendingCheckpointToTruthfulPauseStageAndReason() throws Exception {
        Map<Checkpoint.Type, String> expectedStages = Map.of(
                Checkpoint.Type.AFTER_INITIAL_SETUP, "PLANNING",
                Checkpoint.Type.AFTER_VOLUME_OUTLINE, "PLANNING",
                Checkpoint.Type.AFTER_CHAPTER, "WRITING"
        );

        for (var expected : expectedStages.entrySet()) {
            Project project = projectService.create(
                    "pause-" + expected.getKey(), "concept", "tag", ProjectMode.INTERACTIVE);
            project.setState(NovelState.PAUSED_AT_CHECKPOINT);
            repository.saveProject(project);
            String reason = "等待审核：" + expected.getKey();
            repository.saveCheckpoint(new Checkpoint(project.getId(), expected.getKey(), reason));

            mockMvc.perform(get("/api/projects/{id}/status", project.getId()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.pauseStage").value(expected.getValue()))
                    .andExpect(jsonPath("$.pauseReason").value(reason));

            JsonNode item = findListItem(project.getId());
            assertThat(item.path("pauseStage").asText()).isEqualTo(expected.getValue());
            assertThat(item.path("pauseReason").asText()).isEqualTo(reason);
        }
    }

    @Test
    void statusUsesNullPauseFieldsWhenThereIsNoPendingCheckpoint() throws Exception {
        Project project = projectService.create("no-checkpoint", "concept", "tag", ProjectMode.SOLO);

        mockMvc.perform(get("/api/projects/{id}/status", project.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pauseStage").value(nullValue()))
                .andExpect(jsonPath("$.pauseReason").value(nullValue()));
    }

    @Test
    void statusAndListExposeRealChapterProgress() throws Exception {
        Project project = projectService.create(
                "twelve-chapter-project", "concept", "tag", ProjectMode.SOLO, null, 12);
        project.setState(NovelState.WRITING);
        project.setCurrentChapterIndex(3);
        repository.saveProject(project);

        mockMvc.perform(get("/api/projects/{id}/status", project.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentChapter").value(3))
                .andExpect(jsonPath("$.targetChapters").value(12))
                .andExpect(jsonPath("$.progressPercent").value(25));

        JsonNode item = findListItem(project.getId());
        assertThat(item.path("currentChapterIndex").asInt()).isEqualTo(3);
        assertThat(item.path("targetChapters").asInt()).isEqualTo(12);
        assertThat(item.path("progressPercent").asInt()).isEqualTo(25);

        project.setCurrentChapterIndex(15);
        repository.saveProject(project);
        mockMvc.perform(get("/api/projects/{id}/status", project.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.progressPercent").value(100));
    }

    @Test
    void missingPreferencesUseWritingPreferencesDomainDefault() throws Exception {
        Project project = projectService.create("default-length-project", "concept", "tag", ProjectMode.SOLO);
        project.setState(NovelState.WRITING);
        project.setCurrentChapterIndex(3);
        repository.saveProject(project);
        assertThat(repository.findWritingPreferences(project.getId())).isEmpty();

        JsonNode item = findListItem(project.getId());
        assertThat(item.path("targetChapters").asInt()).isEqualTo(24);
        assertThat(item.path("progressPercent").asInt()).isEqualTo(12);
    }

    private JsonNode findListItem(String projectId) throws Exception {
        String json = mockMvc.perform(get("/api/projects"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        for (JsonNode item : objectMapper.readTree(json)) {
            if (projectId.equals(item.path("id").asText())) return item;
        }
        throw new AssertionError("项目列表缺少项目: " + projectId);
    }
}
