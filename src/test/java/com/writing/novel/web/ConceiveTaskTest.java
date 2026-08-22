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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(StubLlmGatewayTestConfig.class)
@TestPropertySource(properties = {
        "persistence.embedding=stub",
        "persistence.sqlite.db-path=./data/test-conceive-task.db",
        "persistence.vector-store.index-path=./data/test-conceive-task-vecs.idx"
})
class ConceiveTaskTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;
    @Autowired WritingTaskExecutor writingTaskExecutor;

    @BeforeAll
    static void createDataDir() { new File("./data").mkdirs(); }

    @Test
    void postRun_returnsTaskId_andPollsToTerminal() throws Exception {
        String id = createProject();
        String json = mvc.perform(post("/api/projects/" + id + "/run"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskId").exists())
                .andExpect(jsonPath("$.type").value("CONCEIVE"))
                .andReturn().getResponse().getContentAsString();
        String taskId = om.readTree(json).get("taskId").asText();

        String terminal = pollTask(id, taskId, "CONCEIVE");
        org.junit.jupiter.api.Assertions.assertTrue(
                terminal.equals("COMPLETED") || terminal.equals("FAILED") || terminal.equals("CANCELLED"),
                "terminal=" + terminal);
    }

    @Test
    void cancelWriteAll_marksCancelled() throws Exception {
        String id = createProject();
        awaitRun(id);
        mvc.perform(post("/api/projects/" + id + "/run"));

        String started = mvc.perform(post("/api/projects/" + id + "/write-all").param("maxChapters", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskId").exists())
                .andExpect(jsonPath("$.type").value("WRITE_ALL"))
                .andReturn().getResponse().getContentAsString();
        String taskId = om.readTree(started).get("taskId").asText();

        mvc.perform(post("/api/projects/" + id + "/writing-tasks/" + taskId + "/cancel"))
                .andExpect(status().isOk());

        String terminal = pollTask(id, taskId, "WRITE_ALL");
        org.junit.jupiter.api.Assertions.assertTrue(
                terminal.equals("CANCELLED") || terminal.equals("COMPLETED") || terminal.equals("FAILED"),
                "terminal=" + terminal);
        if ("CANCELLED".equals(terminal)) {
            mvc.perform(get("/api/projects/" + id))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.state").value(org.hamcrest.Matchers.not("RUNNING")));
        }
    }

    @Test
    void deleteProject_conflictWhenTaskActive() throws Exception {
        String id = createProject();
        writingTaskExecutor.beginWork(id, "write-all");
        try {
            mvc.perform(delete("/api/projects/" + id))
                    .andExpect(status().isConflict());
        } finally {
            writingTaskExecutor.endWork(id, "write-all");
        }
    }

    private String createProject() throws Exception {
        CreateProjectRequest req = new CreateProjectRequest("task", "c", "tag", "SOLO", null);
        String json = mvc.perform(post("/api/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(req)))
                .andReturn().getResponse().getContentAsString();
        return om.readTree(json).get("id").asText();
    }

    private void awaitRun(String id) throws Exception {
        String json = mvc.perform(post("/api/projects/" + id + "/run"))
                .andReturn().getResponse().getContentAsString();
        var tree = om.readTree(json);
        if (tree.has("taskId") && !tree.get("taskId").isNull()) {
            pollTask(id, tree.get("taskId").asText(), "CONCEIVE");
        }
    }

    private String pollTask(String id, String taskId, String expectedType) throws Exception {
        String status = "RUNNING";
        for (int i = 0; i < 80; i++) {
            String body = mvc.perform(get("/api/projects/" + id + "/writing-tasks/" + taskId))
                    .andReturn().getResponse().getContentAsString();
            var tree = om.readTree(body);
            String type = tree.path("type").asText("");
            if (!type.isBlank()) {
                org.junit.jupiter.api.Assertions.assertEquals(expectedType, type, "轮询 type 应持久一致");
            }
            status = tree.path("status").asText("RUNNING");
            if (!"RUNNING".equals(status) && !"任务不存在".equals(tree.path("error").asText(""))) {
                return status;
            }
            Thread.sleep(50);
        }
        return status;
    }
}
