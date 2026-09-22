package com.writing.novel.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.writing.novel.domain.Chapter;
import com.writing.novel.domain.ChapterVersion;
import com.writing.novel.domain.Project;
import com.writing.novel.service.WritingTaskExecutor;
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
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * p32-12（UI 验证发现）：409 必须区分「所有权占用」与「版本冲突」。
 * 复现：Agent 活动期 restore/编辑被所有权门拒绝（行为正确），但响应与版本冲突
 * 同形，前端统一显示「章节已被更新，请重新确认」诱导无效重试。
 * 修复后：所有权占用返回冲突码，前端提示等待或接手（NCS-075 细分：匿名占坑=SYNC_WORK_BUSY）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(StubLlmGatewayTestConfig.class)
@TestPropertySource(properties = {
        "persistence.embedding=stub",
        "persistence.sqlite.db-path=./data/test-ownership-message.db",
        "persistence.vector-store.index-path=./data/test-ownership-message-vecs.idx"
})
class RestoreOwnershipMessageTest {

    private static final String LONG_BODY = "这是一段满足人工章节保存门槛的正文。".repeat(40);

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired NovelRepository repository;
    @Autowired WritingTaskExecutor writingTaskExecutor;

    @BeforeAll
    static void createDataDir() {
        new File("./data").mkdirs();
    }

    private String seededProjectWithHistory() {
        Project project = new Project("所有权文案", "概念", "现实");
        repository.saveProject(project);
        Chapter chapter = new Chapter(project.getId(), 1, "第一章");
        chapter.setContent(LONG_BODY);
        repository.saveChapter(chapter);
        repository.saveChapterVersion(new ChapterVersion(UUID.randomUUID().toString(),
                project.getId(), 1, chapter.getVersion() - 1, "第一章", LONG_BODY,
                "DIRECT_EDIT", Instant.now()));
        return project.getId();
    }

    @Test
    void 所有权占用期的restore返回冲突码与等待指引() throws Exception {
        String id = seededProjectWithHistory();
        int current = repository.findChapterByIndex(id, 1).orElseThrow().getVersion();
        writingTaskExecutor.beginWork(id, "held-owner");
        try {
            mvc.perform(post("/api/projects/{id}/chapters/{index}/restore/{version}", id, 1, current - 1)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("expectedVersion", current))))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("SYNC_WORK_BUSY"))
                    // NCS-075: 文案区分「同步处理中」与写作任务，指引等待或取消
                    .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("同步内容处理")));
        } finally {
            writingTaskExecutor.endWork(id, "held-owner");
        }
    }

    @Test
    void 所有权占用期的编辑保存同样返回冲突码() throws Exception {
        String id = seededProjectWithHistory();
        int current = repository.findChapterByIndex(id, 1).orElseThrow().getVersion();
        writingTaskExecutor.beginWork(id, "held-owner-2");
        try {
            mvc.perform(put("/api/projects/{id}/chapters/{index}", id, 1)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "expectedVersion", current,
                                    "title", "第一章",
                                    "content", LONG_BODY + "改动。"))))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("SYNC_WORK_BUSY"));
        } finally {
            writingTaskExecutor.endWork(id, "held-owner-2");
        }
    }

    @Test
    void 陈旧版本的409不携带所有权冲突码() throws Exception {
        String id = seededProjectWithHistory();
        int current = repository.findChapterByIndex(id, 1).orElseThrow().getVersion();
        // 无占用，但 expectedVersion 陈旧 → 版本冲突（无 code 字段）
        mvc.perform(post("/api/projects/{id}/chapters/{index}/restore/{version}", id, 1, current - 1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("expectedVersion", current + 5))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").doesNotExist())
                .andExpect(jsonPath("$.error").value("章节版本已变化"));
    }
}
