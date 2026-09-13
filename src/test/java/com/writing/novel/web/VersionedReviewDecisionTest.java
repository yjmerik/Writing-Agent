package com.writing.novel.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.writing.novel.domain.Chapter;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * p36-04：携带版本的审阅上下文与批准接口契约。
 * 旧稿提交必须 409 且新稿仍待审；缺版本 400；跨项目拒绝；同请求重试幂等。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(StubLlmGatewayTestConfig.class)
@TestPropertySource(properties = {
        "persistence.embedding=stub",
        "persistence.sqlite.db-path=./data/test-versioned-review-decision.db",
        "persistence.vector-store.index-path=./data/test-versioned-review-decision-vecs.idx"
})
class VersionedReviewDecisionTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;
    @Autowired NovelRepository repository;

    @BeforeAll
    static void createDataDir() { new File("./data").mkdirs(); }

    @Test
    void 旧稿提交被拒绝且新稿仍待审() throws Exception {
        String projectId = createProject();
        seedChapter(projectId);
        JsonNode ctx = readContext(projectId);
        int v2 = ctx.path("identity").path("version").asInt();
        String v2Hash = ctx.path("identity").path("contentHash").asText();
        String reviewSetHash = ctx.path("reviewSetHash").asText();

        // 正式 PUT 产生 v3（屏障重绑到 v3）
        mvc.perform(put("/api/projects/" + projectId + "/chapters/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of(
                                "expectedVersion", v2,
                                "title", "第一章",
                                "content", revisedContent()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(v2 + 1));

        // 用 v2 身份提交批准：必须 409，v3 保持待审
        String staleRequest = om.writeValueAsString(Map.of(
                "identity", Map.of("projectId", projectId, "chapterIndex", 1,
                        "version", v2, "contentHash", v2Hash),
                "reviewSetHash", reviewSetHash,
                "idempotencyKey", "stale-key-1",
                "reason", "旧窗口误批"));
        mvc.perform(post("/api/projects/" + projectId + "/chapters/1/review-decisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(staleRequest))
                .andExpect(status().isConflict());

        assertEquals(Chapter.Status.NEEDS_MANUAL_REVIEW,
                repository.findChapterByIndex(projectId, 1).orElseThrow().getStatus(),
                "v3 必须仍待审");
        assertFalse(repository.findActiveReviewBarriers(projectId).isEmpty(),
                "屏障必须仍然阻塞");
    }

    @Test
    void 缺版本或缺指纹或缺幂等键一律400() throws Exception {
        String projectId = createProject();
        seedChapter(projectId);
        JsonNode ctx = readContext(projectId);
        String contentHash = ctx.path("identity").path("contentHash").asText();
        String reviewSetHash = ctx.path("reviewSetHash").asText();

        // 缺 version
        mvc.perform(post("/api/projects/" + projectId + "/chapters/1/review-decisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of(
                                "identity", Map.of("projectId", projectId, "chapterIndex", 1,
                                        "contentHash", contentHash),
                                "reviewSetHash", reviewSetHash,
                                "idempotencyKey", "missing-version"))))
                .andExpect(status().isBadRequest());
        // 缺 contentHash
        mvc.perform(post("/api/projects/" + projectId + "/chapters/1/review-decisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of(
                                "identity", Map.of("projectId", projectId, "chapterIndex", 1, "version", 2),
                                "reviewSetHash", reviewSetHash,
                                "idempotencyKey", "missing-hash"))))
                .andExpect(status().isBadRequest());
        // 缺 reviewSetHash
        mvc.perform(post("/api/projects/" + projectId + "/chapters/1/review-decisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of(
                                "identity", Map.of("projectId", projectId, "chapterIndex", 1,
                                        "version", 2, "contentHash", contentHash),
                                "idempotencyKey", "missing-rs"))))
                .andExpect(status().isBadRequest());
        // 服务端不得补填：请求体不产生批准
        assertEquals(0, approvalCount(projectId));
    }

    @Test
    void 其他项目不能批准() throws Exception {
        String projectId = createProject();
        seedChapter(projectId);
        JsonNode ctx = readContext(projectId);
        int version = ctx.path("identity").path("version").asInt();
        String contentHash = ctx.path("identity").path("contentHash").asText();
        String reviewSetHash = ctx.path("reviewSetHash").asText();

        String otherProject = createProject();

        // A 的身份提交到 B 的路径：identity 所有者与路径不符 → 400
        String crossRequest = om.writeValueAsString(Map.of(
                "identity", Map.of("projectId", projectId, "chapterIndex", 1,
                        "version", version, "contentHash", contentHash),
                "reviewSetHash", reviewSetHash,
                "idempotencyKey", "cross-key-1"));
        mvc.perform(post("/api/projects/" + otherProject + "/chapters/1/review-decisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(crossRequest))
                .andExpect(status().isBadRequest());
        // B 项目无章节可批准；A 的章节未产生任何批准
        assertEquals(0, approvalCount(projectId));
    }

    @Test
    void 相同请求重试幂等返回同一批准() throws Exception {
        String projectId = createProject();
        seedChapter(projectId);
        JsonNode ctx = readContext(projectId);
        int version = ctx.path("identity").path("version").asInt();
        String contentHash = ctx.path("identity").path("contentHash").asText();
        String reviewSetHash = ctx.path("reviewSetHash").asText();

        String request = om.writeValueAsString(Map.of(
                "identity", Map.of("projectId", projectId, "chapterIndex", 1,
                        "version", version, "contentHash", contentHash),
                "reviewSetHash", reviewSetHash,
                "idempotencyKey", "retry-key-1",
                "reason", "作者确认"));

        MvcResult first = mvc.perform(post("/api/projects/" + projectId + "/chapters/1/review-decisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(false))
                .andReturn();
        String firstApprovalId = om.readTree(first.getResponse().getContentAsString())
                .path("approvalId").asText();

        // 断线重试：同一请求体原样重发
        mvc.perform(post("/api/projects/" + projectId + "/chapters/1/review-decisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(true))
                .andExpect(jsonPath("$.approvalId").value(firstApprovalId));

        assertEquals(Chapter.Status.DONE,
                repository.findChapterByIndex(projectId, 1).orElseThrow().getStatus());
        assertTrue(repository.findActiveReviewBarriers(projectId).isEmpty(), "批准后屏障解决");
    }

    // --- helpers ---

    private String createProject() throws Exception {
        CreateProjectRequest req = new CreateProjectRequest("vrd", "c", "tag", "SOLO", null);
        String json = mvc.perform(post("/api/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(req)))
                .andReturn().getResponse().getContentAsString();
        return om.readTree(json).get("id").asText();
    }

    /** v2 待审章节 + 匹配稿号屏障（p36-03 契约的生产路径 fixture）。 */
    private void seedChapter(String projectId) {
        Chapter chapter = new Chapter(projectId, 1, "第一章");
        chapter.setContent("待作者复核的初始正文。");
        chapter.setStatus(Chapter.Status.NEEDS_MANUAL_REVIEW);
        repository.saveChapter(chapter);
        repository.saveReviewBarrier(
                new ReviewBarrier(projectId, 1, chapter.getVersion(), "根源矛盾待复核"));
    }

    private JsonNode readContext(String projectId) throws Exception {
        MvcResult result = mvc.perform(get("/api/projects/" + projectId + "/chapters/1/review-context"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.identity.version").isNumber())
                .andExpect(jsonPath("$.reviewSetHash").isNotEmpty())
                .andReturn();
        return om.readTree(result.getResponse().getContentAsString());
    }

    private String revisedContent() {
        // 直接编辑已完成章节（NEEDS_MANUAL_REVIEW 亦按完成口径校验）正文 ≥500 字
        return "作者修订后的正式正文，长度必须超过已完成章节的五百字门槛，"
                + "否则 DIRECT_EDIT 保存会被拒绝。".repeat(25);
    }

    private int approvalCount(String projectId) throws Exception {
        MvcResult result = mvc.perform(get("/api/projects/" + projectId + "/chapters/1/review-context"))
                .andExpect(status().isOk())
                .andReturn();
        return result.getResponse().getContentAsString().contains("\"approval\":null") ? 0 : 1;
    }
}
