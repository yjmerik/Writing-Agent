package com.writing.novel.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.writing.novel.domain.Checkpoint;
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

        // p21-04: /chapters 改为分页元数据——正文单章经 /chapters/{index} 懒加载
        mvc.perform(get("/api/projects/" + id + "/chapters"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chapters[0].title").exists())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.chapters[0].content").value(""));
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
    void 非写作态写一章被拒绝且不创建任务() throws Exception {
        String id = createProject();
        mvc.perform(post("/api/projects/" + id + "/write-chapter"))
                .andExpect(status().isConflict());

        WritingTask task = findWriteChapterTask(id);
        org.junit.jupiter.api.Assertions.assertNull(task, "门禁拒绝必须发生在 WRITE_CHAPTER 任务创建前");
        org.junit.jupiter.api.Assertions.assertTrue(repository.findChaptersByProject(id).isEmpty());
        org.junit.jupiter.api.Assertions.assertFalse(writingTaskExecutor.hasActiveTask(id));
    }

    @Test
    void 写一章遇活动任务时409且不替换owner() throws Exception {
        String id = createAndConceive();
        mvc.perform(post("/api/projects/" + id + "/run"));  // PLANNING → WRITING
        writingTaskExecutor.beginWork(id, "write-all");
        try {
            mvc.perform(post("/api/projects/" + id + "/write-chapter"))
                    .andExpect(status().isConflict());
            org.junit.jupiter.api.Assertions.assertTrue(writingTaskExecutor.hasActiveTask(id), "原 owner 应保留");
            org.junit.jupiter.api.Assertions.assertNull(findWriteChapterTask(id), "被拒绝的写章不应持久化任务");
        } finally {
            writingTaskExecutor.endWork(id, "write-all");
        }
    }

    @Test
    void writing状态存在待审核项时写一章被拒绝且无副作用() throws Exception {
        String id = createAndConceive();
        mvc.perform(post("/api/projects/" + id + "/run")).andExpect(status().isOk());
        repository.saveCheckpoint(new Checkpoint(id, Checkpoint.Type.AFTER_CHAPTER, "待审核"));
        int tasksBefore = repository.findWritingTasksByProject(id).size();

        mvc.perform(post("/api/projects/" + id + "/write-chapter"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("有待审核项，请先完成审核"));

        org.junit.jupiter.api.Assertions.assertEquals(tasksBefore,
                repository.findWritingTasksByProject(id).size());
        org.junit.jupiter.api.Assertions.assertTrue(repository.findChaptersByProject(id).isEmpty());
        org.junit.jupiter.api.Assertions.assertFalse(writingTaskExecutor.hasActiveTask(id));
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
        RunTaskSupport.runAndAwait(mvc, om, id);
        return id;
    }
}
