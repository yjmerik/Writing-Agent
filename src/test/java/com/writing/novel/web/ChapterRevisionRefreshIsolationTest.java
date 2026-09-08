package com.writing.novel.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.writing.novel.domain.Chapter;
import com.writing.novel.domain.Project;
import com.writing.novel.domain.Summary;
import com.writing.novel.llm.LlmGateway;
import com.writing.novel.llm.LlmRequest;
import com.writing.novel.llm.LlmResponse;
import com.writing.novel.llm.LlmTaskType;
import com.writing.novel.llm.client.StreamHandler;
import com.writing.novel.spi.NovelRepository;
import com.writing.novel.telemetry.EventLog;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * p32-01（体验报告 #2/P1）：正文保存与派生刷新解耦。
 * 派生刷新（摘要重算等）失败不得伪装成"保存失败"：正文已落库时端点仍 2xx，
 * 响应带 derivedRefreshPending=true，并发出 CHAPTER_REFRESH_FAILED 事件。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ChapterRevisionRefreshIsolationTest.ThrowingSummaryGatewayConfig.class)
@TestPropertySource(properties = {
        "persistence.embedding=stub",
        "persistence.sqlite.db-path=./data/test-chapter-refresh-isolation.db",
        "persistence.vector-store.index-path=./data/test-chapter-refresh-isolation-vecs.idx"
})
class ChapterRevisionRefreshIsolationTest {

    private static final String LONG_BODY = "这是一段满足人工章节保存门槛的正文。".repeat(40);

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired NovelRepository repository;

    private static final List<Map<String, Object>> EVENTS = new CopyOnWriteArrayList<>();
    private static final EventLog.Listener LISTENER = record -> {
        if ("CHAPTER_REFRESH_FAILED".equals(record.get("event"))) EVENTS.add(record);
    };

    /** MEMORY_SUMMARY 必失败的网关：其余任务沿用桩行为，模拟派生刷新链路抛异常。 */
    @TestConfiguration
    static class ThrowingSummaryGatewayConfig {
        @org.springframework.context.annotation.Primary
        @org.springframework.context.annotation.Bean
        LlmGateway throwingSummaryGateway() {
            return new LlmGateway() {
                @Override
                public LlmResponse complete(LlmRequest req) {
                    if (req.getTaskType() == LlmTaskType.MEMORY_SUMMARY) {
                        throw new IllegalStateException("simulated summary failure");
                    }
                    return new LlmResponse(
                            "这是桩 LLM 生成的正文内容，足够长用于测试章节写入和存储。".repeat(80), 50, 100);
                }

                @Override
                public void streamComplete(LlmRequest req, StreamHandler handler) {
                }
            };
        }
    }

    @BeforeAll
    static void redirectEventLog() {
        new File("./data").mkdirs();
        System.setProperty(EventLog.PATH_PROPERTY, "./data/test-chapter-refresh-isolation-events.jsonl");
        EventLog.subscribe(LISTENER);
    }

    @AfterAll
    static void restoreEventLog() {
        EventLog.unsubscribe(LISTENER);
        System.clearProperty(EventLog.PATH_PROPERTY);
    }

    @BeforeEach
    @AfterEach
    void clearEvents() {
        EVENTS.clear();
    }

    @Test
    void 派生刷新失败时正文保存仍成功且异步失败被记录为事件() throws Exception {
        String projectId = projectWithChapter("刷新失败仍保存");
        int currentVersion = repository.findChapterByIndex(projectId, 1).orElseThrow().getVersion();

        // p32-13: 派生刷新已异步化——响应不再等待/感知后台失败，
        // 保存成功语义不变，失败经 CHAPTER_REFRESH_FAILED(async=true) 事件可观测。
        MvcResult result = mvc.perform(put("/api/projects/{id}/chapters/{index}", projectId, 1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "expectedVersion", currentVersion,
                                "title", "第一章",
                                "content", LONG_BODY + "作者补记：九月五日。"))))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = objectMapper.readTree(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertEquals(currentVersion + 1, body.path("version").asInt(), "版本号正常推进");

        Chapter saved = repository.findChapterByIndex(projectId, 1).orElseThrow();
        assertEquals(LONG_BODY + "作者补记：九月五日。", saved.getContent(), "作者正文必须逐字保留");

        long deadline = System.currentTimeMillis() + 15_000;
        boolean failed = false;
        while (System.currentTimeMillis() < deadline) {
            failed = EVENTS.stream().anyMatch(e -> projectId.equals(e.get("projectId")));
            if (failed) break;
            Thread.sleep(200);
        }
        assertTrue(failed, "后台派生刷新失败应发出 CHAPTER_REFRESH_FAILED 事件，实际: " + EVENTS);
    }

    @Test
    void 派生刷新成功时不产生失败事件() throws Exception {
        String projectId = chapterOnly("刷新正常");
        int currentVersion = repository.findChapterByIndex(projectId, 1).orElseThrow().getVersion();
        // 无覆盖该章的摘要行 → 刷新链路无事可做，正常返回
        mvc.perform(put("/api/projects/{id}/chapters/{index}", projectId, 1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "expectedVersion", currentVersion,
                                "title", "第一章",
                                "content", LONG_BODY + "正常保存。"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.derivedRefreshPending").value(false));
        Thread.sleep(1500); // 给潜在异步失败留窗口
        assertTrue(EVENTS.stream().noneMatch(e -> projectId.equals(e.get("projectId"))),
                "刷新成功不应发出失败事件: " + EVENTS);
    }

    private String projectWithChapter(String title) {
        String projectId = chapterOnly(title);
        repository.saveSummary(new Summary(projectId, 1, 1, "旧摘要"));
        return projectId;
    }

    private String chapterOnly(String title) {
        Project project = new Project(title, "概念", "现实");
        repository.saveProject(project);
        Chapter chapter = new Chapter(project.getId(), 1, "第一章");
        chapter.setContent(LONG_BODY);
        chapter.setStatus(Chapter.Status.DONE);
        repository.saveChapter(chapter);
        return project.getId();
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }
}
