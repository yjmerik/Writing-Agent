package com.writing.novel.web;

import com.writing.novel.domain.Checkpoint;
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
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.File;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** p26-01: checkpoint action targets must belong to the requested project and remain PENDING. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(StubLlmGatewayTestConfig.class)
@TestPropertySource(properties = {
        "persistence.embedding=stub",
        "persistence.sqlite.db-path=./data/test-checkpoint-target-validation.db",
        "persistence.vector-store.index-path=./data/test-checkpoint-target-validation-vecs.idx"
})
class CheckpointTargetValidationTest {

    @Autowired MockMvc mockMvc;
    @Autowired NovelRepository repository;
    @Autowired ProjectService projectService;

    @BeforeAll
    static void createDataDir() {
        new File("./data").mkdirs();
    }

    @Test
    void actionsConflictWhenCheckpointBelongsToAnotherProject_withoutChangingIt() throws Exception {
        Project owner = projectService.create("owner", "concept", "tag", ProjectMode.INTERACTIVE);
        Project other = projectService.create("other", "concept", "tag", ProjectMode.INTERACTIVE);

        for (String action : List.of("approve", "reject", "modify")) {
            Checkpoint checkpoint = pendingCheckpoint(owner.getId());

            mockMvc.perform(post("/api/projects/{id}/checkpoints/{checkpointId}/{action}",
                            other.getId(), checkpoint.getId(), action)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody(action)))
                    .andExpect(status().isConflict());

            assertThat(repository.findCheckpoint(checkpoint.getId()).orElseThrow().getStatus())
                    .isEqualTo(Checkpoint.Status.PENDING);
        }
    }

    @Test
    void actionsConflictWhenCheckpointWasAlreadyProcessed_withoutChangingIt() throws Exception {
        Project project = projectService.create("project", "concept", "tag", ProjectMode.INTERACTIVE);

        for (String action : List.of("approve", "reject", "modify")) {
            Checkpoint checkpoint = pendingCheckpoint(project.getId());
            checkpoint.approve();
            repository.saveCheckpoint(checkpoint);

            mockMvc.perform(post("/api/projects/{id}/checkpoints/{checkpointId}/{action}",
                            project.getId(), checkpoint.getId(), action)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody(action)))
                    .andExpect(status().isConflict());

            assertThat(repository.findCheckpoint(checkpoint.getId()).orElseThrow().getStatus())
                    .isEqualTo(Checkpoint.Status.APPROVED);
        }
    }

    private Checkpoint pendingCheckpoint(String projectId) {
        Checkpoint checkpoint = new Checkpoint(projectId, Checkpoint.Type.AFTER_VOLUME_OUTLINE, "待审核大纲");
        repository.saveCheckpoint(checkpoint);
        return checkpoint;
    }

    private String requestBody(String action) {
        return "approve".equals(action) ? "{}" : "{\"instructions\":\"改写\"}";
    }
}
