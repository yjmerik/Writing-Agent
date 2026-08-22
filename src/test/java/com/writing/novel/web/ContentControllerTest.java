package com.writing.novel.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.writing.novel.domain.WritingTask;
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
        "persistence.sqlite.db-path=./data/test-web3.db",
        "persistence.vector-store.index-path=./data/test-web3-vecs.idx"
})
class ContentControllerTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;
    @Autowired NovelRepository repository;
    @Autowired WritingTaskExecutor writingTaskExecutor;

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

    @Test
    void 写一章返回ChapterJSON并持久化WRITE_CHAPTER终态() throws Exception {
        String id = createAndConceive();
        mvc.perform(post("/api/projects/" + id + "/run"));  // PLANNING → WRITING
        mvc.perform(post("/api/projects/" + id + "/write-chapter"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chapterIndex").exists())
                .andExpect(jsonPath("$.title").exists())
                .andExpect(jsonPath("$.taskId").doesNotExist());

        WritingTask task = findWriteChapterTask(id);
        org.junit.jupiter.api.Assertions.assertNotNull(task, "write-chapter 应持久化 WRITE_CHAPTER 任务");
        org.junit.jupiter.api.Assertions.assertEquals(WritingTask.Type.WRITE_CHAPTER, task.getType());
        org.junit.jupiter.api.Assertions.assertEquals(WritingTask.Status.COMPLETED, task.getStatus());
        mvc.perform(get("/api/projects/" + id + "/writing-tasks/" + task.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("WRITE_CHAPTER"))
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    void 写一章失败标记FAILED并清理活动槽位() throws Exception {
        String id = createProject();  // 未构思 → 无大纲，写章抛 IllegalStateException
        mvc.perform(post("/api/projects/" + id + "/write-chapter"))
                .andExpect(status().isConflict());

        WritingTask task = findWriteChapterTask(id);
        org.junit.jupiter.api.Assertions.assertNotNull(task, "失败也应持久化 WRITE_CHAPTER 任务");
        org.junit.jupiter.api.Assertions.assertEquals(WritingTask.Type.WRITE_CHAPTER, task.getType());
        org.junit.jupiter.api.Assertions.assertEquals(WritingTask.Status.FAILED, task.getStatus());
        org.junit.jupiter.api.Assertions.assertFalse(task.getErrorMessage().isBlank(), "FAILED 任务应带有界错误消息");
        org.junit.jupiter.api.Assertions.assertFalse(writingTaskExecutor.hasActiveTask(id), "失败后应条件清理活动槽位");
    }

    private WritingTask findWriteChapterTask(String id) {
        return repository.findWritingTasksByProject(id).stream()
                .filter(t -> t.getType() == WritingTask.Type.WRITE_CHAPTER)
                .findFirst().orElse(null);
    }

    private String createProject() throws Exception {
        CreateProjectRequest req = new CreateProjectRequest("t", "c", "tag", "SOLO", null);
        String json = mvc.perform(post("/api/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(req)))
                .andReturn().getResponse().getContentAsString();
        return om.readTree(json).get("id").asText();
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
