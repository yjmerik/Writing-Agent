package com.writing.novel.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.writing.novel.domain.Chapter;
import com.writing.novel.domain.ContinuityIssue;
import com.writing.novel.domain.ReviewBarrier;
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
import org.springframework.test.web.servlet.MvcResult;

import java.io.File;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * NCS-081（听雪刀 P1-2）：批准满足性门禁失败的 409 必须带结构化 code=APPROVAL_BLOCKED。
 * 此前 GlobalExceptionHandler 对 IllegalStateException 只回 {error}（无 code），
 * 前端落进"其余 409"分支仅 3 秒 toast——作者无从得知"为什么批不了、差什么"。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(StubLlmGatewayTestConfig.class)
@TestPropertySource(properties = {
        "persistence.embedding=stub",
        "persistence.sqlite.db-path=./data/test-approve-blocked.db",
        "persistence.vector-store.index-path=./data/test-approve-blocked-vecs.idx"
})
class ReviewDecisionApproveBlockedTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;
    @Autowired NovelRepository repository;

    @BeforeAll
    static void createDataDir() { new File("./data").mkdirs(); }

    @Test
    void 未解决HIGH的批准409带结构化code与原因() throws Exception {
        String projectId = createProject();
        seedChapterWithHighIssue(projectId);
        JsonNode ctx = readContext(projectId);
        int version = ctx.path("identity").path("version").asInt();
        String contentHash = ctx.path("identity").path("contentHash").asText();
        String reviewSetHash = ctx.path("reviewSetHash").asText();

        mvc.perform(post("/api/projects/" + projectId + "/chapters/1/review-decisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of(
                                "identity", Map.of("projectId", projectId, "chapterIndex", 1,
                                        "version", version, "contentHash", contentHash),
                                "reviewSetHash", reviewSetHash,
                                "idempotencyKey", "blocked-key-1",
                                "reason", "作者确认"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("APPROVAL_BLOCKED"))
                .andExpect(jsonPath("$.error").value(
                        org.hamcrest.Matchers.containsString("未解决")));

        assertEqualsManualReview(projectId);
    }

    private void assertEqualsManualReview(String projectId) throws Exception {
        MvcResult result = mvc.perform(get("/api/projects/" + projectId + "/chapters/1"))
                .andExpect(status().isOk())
                .andReturn();
        // 章节仍待审（批准未生效）
        org.junit.jupiter.api.Assertions.assertEquals(
                Chapter.Status.NEEDS_MANUAL_REVIEW.name(),
                om.readTree(result.getResponse().getContentAsString()).path("status").asText());
    }

    // --- helpers ---

    private String createProject() throws Exception {
        CreateProjectRequest req = new CreateProjectRequest("ab", "c", "tag", "SOLO", null);
        String json = mvc.perform(post("/api/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(req)))
                .andReturn().getResponse().getContentAsString();
        return om.readTree(json).get("id").asText();
    }

    private void seedChapterWithHighIssue(String projectId) {
        Chapter chapter = new Chapter(projectId, 1, "第一章");
        chapter.setContent("待作者复核的初始正文。".repeat(30));
        chapter.setStatus(Chapter.Status.NEEDS_MANUAL_REVIEW);
        repository.saveChapter(chapter);
        repository.saveReviewBarrier(
                new ReviewBarrier(projectId, 1, chapter.getVersion(), "高优先级矛盾待复核"));
        // 未解决 HIGH 问题（无豁免裁决）→ 批准门禁 4.5(b) 阻塞
        repository.saveContinuityIssue(new ContinuityIssue(
                projectId, 1, ContinuityIssue.Type.TIMELINE,
                ContinuityIssue.Severity.HIGH, "第1章存在未解决的 HIGH 级连续性问题"));
    }

    private JsonNode readContext(String projectId) throws Exception {
        MvcResult result = mvc.perform(get("/api/projects/" + projectId + "/chapters/1/review-context"))
                .andExpect(status().isOk())
                .andReturn();
        return om.readTree(result.getResponse().getContentAsString());
    }
}
