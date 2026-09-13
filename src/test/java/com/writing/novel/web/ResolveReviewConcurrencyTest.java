package com.writing.novel.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.writing.novel.domain.Chapter;
import com.writing.novel.domain.Project;
import com.writing.novel.domain.ReviewBarrier;
import com.writing.novel.persistence.sqlite.ConnectionProvider;
import com.writing.novel.spi.NovelRepository;
import org.junit.jupiter.api.BeforeEach;
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
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * p36-12：并发放行专项迁移到版本化批准接口（p36-04）。
 * 1) 两窗口并发批准同一身份（不同幂等键）：严格恰好一个 200、一个 409，异常或 500 必须失败；
 *    屏障恰好解决一次（历史留痕）、批准恰好一条；
 * 2) 同幂等键重试：200 且 replayed=true，返回同一 approvalId；
 * 3) A 读 v2 → 正式 PUT 产生 v3 → A 提交旧身份：409 且 v3 仍待审（不使用反射伪造版本）；
 * 4) 原"屏障稿号失配放行被拒"更名为重绑行为描述：正式修订把屏障重绑到新稿号（p36-07 事务内完成）。
 * 旧 resolve-review 无版本放行语义由 p36-06 关闭。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(StubLlmGatewayTestConfig.class)
@TestPropertySource(properties = {
        "persistence.embedding=stub",
        "persistence.sqlite.db-path=./data/test-resolve-cas.db",
        "persistence.vector-store.index-path=./data/test-resolve-cas-vecs.idx"
})
class ResolveReviewConcurrencyTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;
    @Autowired NovelRepository repository;
    @Autowired ConnectionProvider connectionProvider;

    private String pid;

    @BeforeEach
    void setUp() {
        new File("./data").mkdirs();
        Project project = new Project("并发放行", "概念", "悬疑");
        repository.saveProject(project);
        pid = project.getId();
        Chapter ch = new Chapter(pid, 1, "第一章");
        ch.setContent("待复核正文。".repeat(150));
        ch.setStatus(Chapter.Status.NEEDS_MANUAL_REVIEW);
        repository.saveChapter(ch);
        repository.saveReviewBarrier(new ReviewBarrier(pid, 1, ch.getVersion(), "根源矛盾待复核"));
    }

    @Test
    void 并发批准同一身份不同幂等键恰好一个200一个409() throws Exception {
        JsonNode context = readContext();
        String requestA = approvalRequest(context, "race-key-a");
        String requestB = approvalRequest(context, "race-key-b");
        AtomicInteger ok200 = new AtomicInteger();
        AtomicInteger conflict409 = new AtomicInteger();
        AtomicInteger other = new AtomicInteger();

        Runnable fireA = () -> record(approveStatus(requestA), ok200, conflict409, other);
        Runnable fireB = () -> record(approveStatus(requestB), ok200, conflict409, other);
        CompletableFuture.allOf(CompletableFuture.runAsync(fireA),
                CompletableFuture.runAsync(fireB)).join();

        assertEquals(1, ok200.get(), "并发批准必须恰好一方成功");
        assertEquals(1, conflict409.get(), "另一方必须恰好得到 409");
        assertEquals(0, other.get(), "并发中不得出现异常、500 或其他状态");

        assertEquals(Chapter.Status.DONE, repository.findChapterByIndex(pid, 1).orElseThrow().getStatus());
        assertTrue(repository.findActiveReviewBarriers(pid).isEmpty(), "屏障应恰好解决一次");
        assertEquals(1, countRows("SELECT COUNT(*) FROM review_barrier_history WHERE project_id='" + pid + "' AND event='RESOLVED'"),
                "屏障解决留痕恰好一条（按项目隔离）");
        JsonNode after = readContext();
        assertTrue(after.hasNonNull("approval"), "并发后存在且仅存在一条批准可被上下文读到");
    }

    @Test
    void 同一幂等键重试返回原结果且replayed() throws Exception {
        JsonNode context = readContext();
        String request = approvalRequest(context, "retry-key");

        MvcResult first = mvc.perform(post("/api/projects/" + pid + "/chapters/1/review-decisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(false))
                .andReturn();
        String approvalId = om.readTree(first.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("approvalId").asText();

        mvc.perform(post("/api/projects/" + pid + "/chapters/1/review-decisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(true))
                .andExpect(jsonPath("$.approvalId").value(approvalId));

        assertEquals(1, countRows("SELECT COUNT(*) FROM review_barrier_history WHERE project_id='" + pid + "' AND event='RESOLVED'"),
                "重试不重复解决屏障（按项目隔离）");
    }

    @Test
    void 读取v2后正式保存v3旧身份批准被拒且新稿仍待审() throws Exception {
        JsonNode context = readContext();
        int v2 = context.path("identity").path("version").asInt();
        String staleRequest = approvalRequest(context, "stale-key");

        // 正式 PUT 产生 v3（作者动作走真实端点，不伪造版本）
        Chapter current = repository.findChapterByIndex(pid, 1).orElseThrow();
        mvc.perform(put("/api/projects/" + pid + "/chapters/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of(
                                "expectedVersion", v2,
                                "title", "第一章",
                                "content", current.getContent() + "作者修订的新段落，长度超过已完成章节门槛。".repeat(15)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(v2 + 1));

        // A 提交 v2 旧身份 → 409；v3 保持待审
        mvc.perform(post("/api/projects/" + pid + "/chapters/1/review-decisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(staleRequest))
                .andExpect(status().isConflict());

        Chapter after = repository.findChapterByIndex(pid, 1).orElseThrow();
        assertEquals(v2 + 1, after.getVersion(), "章节保持 v3");
        assertEquals(Chapter.Status.NEEDS_MANUAL_REVIEW, after.getStatus(), "v3 仍待审");
        assertEquals(1, repository.findActiveReviewBarriers(pid).size(), "新稿屏障未解除");
        assertEquals(v2 + 1, repository.findActiveReviewBarriers(pid).get(0).getChapterVersion(),
                "屏障绑定新稿号（修订重绑生效）");
    }

    @Test
    void 正式修订将屏障重绑到新稿号消除旧稿并存() throws Exception {
        // 原"屏障稿号与当前章不符时放行被拒并保留屏障"更名：本用例如实描述重绑行为——
        // 正式修订（p36-07）在事务内把该章 ACTIVE 屏障重绑到新稿号，消除"v2 待审/v3 当前"并存；
        // 旧身份放行拒绝已由上方版本化批准用例覆盖，不再使用反射伪造版本。
        Chapter ch = repository.findChapterByIndex(pid, 1).orElseThrow();
        int oldVersion = ch.getVersion();

        mvc.perform(put("/api/projects/" + pid + "/chapters/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of(
                                "expectedVersion", oldVersion,
                                "title", "第一章",
                                "content", ch.getContent() + "再次修订的段落，长度满足门槛要求。".repeat(15)))))
                .andExpect(status().isOk());

        int newVersion = repository.findChapterByIndex(pid, 1).orElseThrow().getVersion();
        assertEquals(oldVersion + 1, newVersion);
        var barriers = repository.findActiveReviewBarriers(pid);
        assertEquals(1, barriers.size(), "修订后屏障保持 ACTIVE");
        assertEquals(newVersion, barriers.get(0).getChapterVersion(), "屏障稿号与最新稿一致");
        assertTrue(barriers.get(0).getReason().contains("内容已修订"), "重绑原因注明内容已修订");
        assertEquals(1, countRows("SELECT COUNT(*) FROM review_barrier_history WHERE project_id='" + pid + "' AND event='REVISED' " +
                "AND old_chapter_version=" + oldVersion + " AND new_chapter_version=" + newVersion),
                "重绑留痕：旧稿号到新稿号（按项目隔离）");
    }

    // --- helpers ---

    private int approveStatus(String request) {
        try {
            return mvc.perform(post("/api/projects/" + pid + "/chapters/1/review-decisions")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(request))
                    .andReturn().getResponse().getStatus();
        } catch (Exception e) {
            return -1;
        }
    }

    private void record(int status, AtomicInteger ok200, AtomicInteger conflict409, AtomicInteger other) {
        if (status == 200) ok200.incrementAndGet();
        else if (status == 409) conflict409.incrementAndGet();
        else other.incrementAndGet();
    }

    private JsonNode readContext() throws Exception {
        MvcResult result = mvc.perform(get("/api/projects/" + pid + "/chapters/1/review-context"))
                .andExpect(status().isOk())
                .andReturn();
        return om.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private String approvalRequest(JsonNode context, String idempotencyKey) throws Exception {
        return om.writeValueAsString(Map.of(
                "identity", Map.of(
                        "projectId", pid,
                        "chapterIndex", 1,
                        "version", context.path("identity").path("version").asInt(),
                        "contentHash", context.path("identity").path("contentHash").asText()),
                "reviewSetHash", context.path("reviewSetHash").asText(),
                "idempotencyKey", idempotencyKey));
    }

    private int countRows(String sql) throws SQLException {
        try (Connection conn = connectionProvider.getConnection(); Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
