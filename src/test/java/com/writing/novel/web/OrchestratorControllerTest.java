package com.writing.novel.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.writing.novel.domain.Checkpoint;
import com.writing.novel.domain.NovelState;
import com.writing.novel.domain.Project;
import com.writing.novel.service.WritingTaskExecutor;
import com.writing.novel.spi.NovelRepository;
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
    @Autowired NovelRepository repository;
    @Autowired WritingTaskExecutor writingTaskExecutor;

    @org.junit.jupiter.api.BeforeAll
    static void createDataDir() { new File("./data").mkdirs(); }

    private String createProject() throws Exception {
        CreateProjectRequest req = new CreateProjectRequest("t", "c", "tag", "SOLO", null);
        String json = mvc.perform(post("/api/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(req)))
                .andReturn().getResponse().getContentAsString();
        return om.readTree(json).get("id").asText();
    }

    @Test
    void 运行构思阶段并查询状态() throws Exception {
        String id = createProject();
        RunTaskSupport.runAndAwait(mvc, om, id);
        mvc.perform(get("/api/projects/" + id + "/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("PLANNING"))
                .andExpect(jsonPath("$.allowedActions[0]").value("ADVANCE"))
                .andExpect(jsonPath("$.blockedReasons.WRITE_CHAPTER").isNotEmpty());
    }

    @Test
    void writing状态的run被拒绝且不会隐式写章() throws Exception {
        String id = createProject();
        RunTaskSupport.runAndAwait(mvc, om, id); // CONCEIVING -> PLANNING，生成大纲
        mvc.perform(post("/api/projects/" + id + "/run"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("WRITING"));
        int tasksBefore = repository.findWritingTasksByProject(id).size();

        mvc.perform(post("/api/projects/" + id + "/run"))
                .andExpect(status().isConflict());

        Project after = repository.findProject(id).orElseThrow();
        org.junit.jupiter.api.Assertions.assertEquals(NovelState.WRITING, after.getState());
        org.junit.jupiter.api.Assertions.assertEquals(0, after.getCurrentChapterIndex());
        org.junit.jupiter.api.Assertions.assertTrue(repository.findChaptersByProject(id).isEmpty());
        org.junit.jupiter.api.Assertions.assertEquals(tasksBefore,
                repository.findWritingTasksByProject(id).size(), "被拒绝时不得创建任务");
        org.junit.jupiter.api.Assertions.assertFalse(writingTaskExecutor.hasActiveTask(id));
    }

    @Test
    void status根据服务端大纲与待审项计算写作动作() throws Exception {
        String id = createProject();
        RunTaskSupport.runAndAwait(mvc, om, id);
        mvc.perform(post("/api/projects/" + id + "/run"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("WRITING"));

        mvc.perform(get("/api/projects/" + id + "/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowedActions.length()").value(2))
                .andExpect(jsonPath("$.allowedActions[0]").value("WRITE_CHAPTER"))
                .andExpect(jsonPath("$.allowedActions[1]").value("WRITE_ALL"));

        repository.saveCheckpoint(new Checkpoint(id, Checkpoint.Type.AFTER_CHAPTER, "待审核"));
        mvc.perform(get("/api/projects/" + id + "/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowedActions.length()").value(0))
                .andExpect(jsonPath("$.blockedReasons.WRITE_CHAPTER")
                        .value("有待审核项，请先完成审核"));
    }

    @Test
    void 构思态writeAll被拒绝且不创建写作任务() throws Exception {
        String id = createProject();

        mvc.perform(post("/api/projects/" + id + "/write-all"))
                .andExpect(status().isConflict());

        org.junit.jupiter.api.Assertions.assertTrue(repository.findWritingTasksByProject(id).isEmpty());
        org.junit.jupiter.api.Assertions.assertFalse(writingTaskExecutor.hasActiveTask(id));
    }

    @Test
    void 构思态finalAudit被拒绝且项目无副作用() throws Exception {
        String id = createProject();

        mvc.perform(post("/api/projects/" + id + "/final-audit"))
                .andExpect(status().isConflict());

        Project after = repository.findProject(id).orElseThrow();
        org.junit.jupiter.api.Assertions.assertEquals(NovelState.CONCEIVING, after.getState());
        org.junit.jupiter.api.Assertions.assertTrue(repository.findWritingTasksByProject(id).isEmpty());
    }
}
