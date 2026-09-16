package com.writing.novel.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.writing.novel.domain.AuthorDirective;
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
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** p38-10: reviewSetHash 纳入适用意见与有效裁决——新增 HARD 后旧上下文提交必须 409。 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(StubLlmGatewayTestConfig.class)
@TestPropertySource(properties = {
        "persistence.embedding=stub",
        "persistence.sqlite.db-path=./data/test-disposition-gate.db",
        "persistence.vector-store.index-path=./data/test-disposition-gate-vecs.idx"
})
class DispositionApprovalGateApiTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;
    @Autowired NovelRepository repository;

    private String createProject(String title) throws Exception {
        CreateProjectRequest req = new CreateProjectRequest(title, "c", "tag", "SOLO", null);
        String json = mvc.perform(post("/api/projects")
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(req)))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return om.readTree(json).get("id").asText();
    }

    private void seedReviewChapter(String pid) {
        var chapter = new com.writing.novel.domain.Chapter(pid, 1, "Ch1");
        chapter.setContent("Content for review test chapter 1 body text. ".repeat(30));
        chapter.setStatus(com.writing.novel.domain.Chapter.Status.NEEDS_MANUAL_REVIEW);
        repository.saveChapter(chapter);
        repository.saveReviewBarrier(new com.writing.novel.domain.ReviewBarrier(
                pid, 1, chapter.getVersion(), "test"));
    }

    @Test
    void 新增HARD要求后旧上下文提交必须409() throws Exception {
        String pid = createProject("gate-hard");
        seedReviewChapter(pid);

        // 读取审阅上下文（无意见时）
        MvcResult ctx1 = mvc.perform(get("/api/projects/{id}/chapters/1/review-context", pid))
                .andExpect(status().isOk()).andReturn();
        var context1 = om.readTree(ctx1.getResponse().getContentAsString(StandardCharsets.UTF_8));
        String reviewSetHash1 = context1.path("reviewSetHash").asText();
        assertTrue(context1.path("directives").isEmpty(), "无意见时 directives 为空");

        // 新增 HARD 要求
        Map<String, Object> body = new HashMap<>();
        body.put("kind", "HARD");
        body.put("content", "每章必须有雨夜对话");
        body.put("fromChapter", 1);
        mvc.perform(post("/api/projects/{id}/directives", pid)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(body)))
                .andExpect(status().isOk());

        // 验证 reviewSetHash 已变化
        MvcResult ctx2 = mvc.perform(get("/api/projects/{id}/chapters/1/review-context", pid))
                .andExpect(status().isOk()).andReturn();
        var context2 = om.readTree(ctx2.getResponse().getContentAsString(StandardCharsets.UTF_8));
        String reviewSetHash2 = context2.path("reviewSetHash").asText();
        org.junit.jupiter.api.Assertions.assertNotEquals(reviewSetHash1, reviewSetHash2,
                "新增 HARD 后 reviewSetHash 必须变化");

        // 用旧的 reviewSetHash 提交批准 → 必须 409
        Map<String, Object> approve = new HashMap<>();
        approve.put("identity", Map.of("projectId", pid, "chapterIndex", 1,
                "version", context1.path("identity").path("version").asInt(),
                "contentHash", context1.path("identity").path("contentHash").asText()));
        approve.put("reviewSetHash", reviewSetHash1);
        approve.put("idempotencyKey", "stale-hard-key");
        mvc.perform(post("/api/projects/{id}/chapters/1/review-decisions", pid)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(approve)))
                .andExpect(status().isConflict());
    }
}
