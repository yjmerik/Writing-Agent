package com.writing.novel.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.writing.novel.domain.Chapter;
import com.writing.novel.domain.ReviewBarrier;
import com.writing.novel.service.WritingTaskExecutor;
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
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * p36-12：人工复核流程已迁移到版本化批准接口（p36-04 GET review-context / POST review-decisions）。
 * 保留原业务断言并映射到新契约：
 * 1) 人审中章节批准成功 → 章节 DONE、屏障解除、批准留痕；
 * 2) 写作中（工权占用）批准 → 409 冲突码（NCS-075 细分：匿名占坑=SYNC_WORK_BUSY），章节与屏障不变；
 * 3) 非待审状态（已批准后重复批准）→ 409，不产生第二次批准记录。
 * 旧 resolve-review 无版本放行语义由 p36-06 关闭。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(StubLlmGatewayTestConfig.class)
@TestPropertySource(properties = {
        "persistence.embedding=stub",
        "persistence.sqlite.db-path=./data/test-manual-review-guard.db",
        "persistence.vector-store.index-path=./data/test-manual-review-guard-vecs.idx"
})
class ManualReviewResolutionTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;
    @Autowired NovelRepository repository;
    @Autowired WritingTaskExecutor writingTaskExecutor;

    @BeforeAll
    static void createDataDir() { new File("./data").mkdirs(); }

    @Test
    void 版本化批准成功后章节DONE且屏障解除() throws Exception {
        String id = createProject();
        Chapter chapter = seedReviewChapter(id);
        JsonNode context = readContext(id);
        assertEquals(chapter.getVersion(), context.path("identity").path("version").asInt(),
                "审阅上下文身份必须与当前稿一致");

        mvc.perform(post("/api/projects/" + id + "/chapters/1/review-decisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(approvalRequest(id, context, "mrr-key-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(false))
                .andExpect(jsonPath("$.approvalId").isNotEmpty())
                .andExpect(jsonPath("$.identity.version").value(chapter.getVersion()));

        assertEquals(Chapter.Status.DONE, repository.findChapterByIndex(id, 1).orElseThrow().getStatus(),
                "批准后章节标记 DONE");
        assertTrue(repository.findActiveReviewBarriers(id).isEmpty(), "批准后屏障应解除");
        JsonNode after = readContext(id);
        assertTrue(after.hasNonNull("approval"), "批准记录进入审阅上下文");
    }

    @Test
    void 写作中版本化批准被工权占用拒绝且章节屏障不变() throws Exception {
        String id = createProject();
        seedReviewChapter(id);
        JsonNode context = readContext(id);

        writingTaskExecutor.beginWork(id, "write-chapter");
        try {
            mvc.perform(post("/api/projects/" + id + "/chapters/1/review-decisions")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(approvalRequest(id, context, "mrr-key-busy")))
                    .andExpect(status().isConflict())
                    // NCS-075: 匿名 workId 占坑 → 细分码 SYNC_WORK_BUSY（原通用 WORK_OWNERSHIP_CONFLICT）
                    .andExpect(jsonPath("$.code").value("SYNC_WORK_BUSY"));
        } finally {
            writingTaskExecutor.endWork(id, "write-chapter");
        }

        assertEquals(Chapter.Status.NEEDS_MANUAL_REVIEW,
                repository.findChapterByIndex(id, 1).orElseThrow().getStatus(), "工权冲突不改章节状态");
        assertEquals(1, repository.findActiveReviewBarriers(id).size(), "屏障保持待审");
    }

    @Test
    void 非待审状态重复批准被拒且不产生第二次批准() throws Exception {
        String id = createProject();
        seedReviewChapter(id);
        JsonNode context = readContext(id);

        MvcResult first = mvc.perform(post("/api/projects/" + id + "/chapters/1/review-decisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(approvalRequest(id, context, "mrr-key-first")))
                .andExpect(status().isOk())
                .andReturn();
        String firstApprovalId = om.readTree(
                        first.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("approvalId").asText();

        // 章节 DONE（不再待人审）：新幂等键重复批准同一身份必须 409
        JsonNode fresh = readContext(id);
        mvc.perform(post("/api/projects/" + id + "/chapters/1/review-decisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(approvalRequest(id, fresh, "mrr-key-dup")))
                .andExpect(status().isConflict());

        assertEquals(Chapter.Status.DONE, repository.findChapterByIndex(id, 1).orElseThrow().getStatus());
        JsonNode after = readContext(id);
        assertEquals(firstApprovalId, after.path("approval").path("approvalId").asText(),
                "重复批准不得产生新的批准记录");
    }

    // --- helpers ---

    private String createProject() throws Exception {
        CreateProjectRequest req = new CreateProjectRequest("cfr", "c", "tag", "SOLO", null);
        String json = mvc.perform(post("/api/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(req)))
                .andReturn().getResponse().getContentAsString();
        return om.readTree(json).get("id").asText();
    }

    private Chapter seedReviewChapter(String projectId) {
        Chapter chapter = new Chapter(projectId, 1, "雨夜账");
        chapter.setContent("待作者复核的正文内容，长度满足已完成章节口径。".repeat(20));
        chapter.setStatus(Chapter.Status.NEEDS_MANUAL_REVIEW);
        repository.saveChapter(chapter);
        repository.saveReviewBarrier(new ReviewBarrier(projectId, 1, chapter.getVersion(), "根源矛盾待复核"));
        return chapter;
    }

    private JsonNode readContext(String projectId) throws Exception {
        MvcResult result = mvc.perform(get("/api/projects/" + projectId + "/chapters/1/review-context"))
                .andExpect(status().isOk())
                .andReturn();
        return om.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private String approvalRequest(String projectId, JsonNode context, String idempotencyKey)
            throws Exception {
        return om.writeValueAsString(Map.of(
                "identity", Map.of(
                        "projectId", projectId,
                        "chapterIndex", 1,
                        "version", context.path("identity").path("version").asInt(),
                        "contentHash", context.path("identity").path("contentHash").asText()),
                "reviewSetHash", context.path("reviewSetHash").asText(),
                "idempotencyKey", idempotencyKey));
    }
}
