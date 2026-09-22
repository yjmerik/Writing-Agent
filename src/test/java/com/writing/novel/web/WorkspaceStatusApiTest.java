package com.writing.novel.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.writing.novel.domain.Chapter;
import com.writing.novel.domain.ReviewBarrier;
import com.writing.novel.domain.ChapterSynopsis;
import com.writing.novel.domain.Outline;
import com.writing.novel.domain.Project;
import com.writing.novel.domain.Volume;
import com.writing.novel.domain.WritingTask;
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
import org.springframework.test.web.servlet.MvcResult;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * p33-02（设计 7）：统一工作区状态 API——前端按钮可用性的单一来源。
 * GET /api/projects/{id}/workspace 返回 stage/collabState/todoList（章节+稿号+原因）/
 * allowedActions+blockedReason；写作中/待人审/已解决三情形字段正确。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(StubLlmGatewayTestConfig.class)
@TestPropertySource(properties = {
        "persistence.embedding=stub",
        "persistence.sqlite.db-path=./data/test-workspace-status.db",
        "persistence.vector-store.index-path=./data/test-workspace-status-vecs.idx"
})
class WorkspaceStatusApiTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;
    @Autowired NovelRepository repository;
    @Autowired WritingTaskExecutor writingTaskExecutor;

    @BeforeAll
    static void createDataDir() {
        new File("./data").mkdirs();
    }

    private String seedProject(String state) {
        Project project = new Project("状态API", "概念", "悬疑");
        project.setState(com.writing.novel.domain.NovelState.valueOf(state));
        repository.saveProject(project);
        String pid = project.getId();
        Outline outline = new Outline(pid, "总纲");
        Volume v = new Volume(pid, 1, "卷一", "卷摘要");
        for (int i = 1; i <= 3; i++) v.addSynopsis(new ChapterSynopsis(pid, i, "第" + i + "章", "梗概"));
        outline.addVolume(v);
        repository.saveOutline(outline);
        return pid;
    }

    private void writeChapter(String pid, int index, Chapter.Status status) {
        Chapter ch = new Chapter(pid, index, "第" + index + "章");
        ch.setContent("正文内容。".repeat(150));
        ch.setStatus(status);
        repository.saveChapter(ch);
        // p33-01/p36-13: 人审章节屏障立即落盘，批准按稿号解决
        if (status == Chapter.Status.NEEDS_MANUAL_REVIEW) {
            repository.saveReviewBarrier(new ReviewBarrier(pid, index, ch.getVersion(), "根源矛盾待人工复核"));
        }
    }

    @Test
    void 写作中状态与动作() throws Exception {
        String pid = seedProject("WRITING");
        writeChapter(pid, 1, Chapter.Status.DONE);
        repository.findProject(pid).ifPresent(p -> { p.setCurrentChapterIndex(1); repository.saveProject(p); });

        mvc.perform(get("/api/projects/" + pid + "/workspace"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stage").value("WRITING"))
                .andExpect(jsonPath("$.collabState").value("WRITING"))
                .andExpect(jsonPath("$.canContinue").value(true))
                .andExpect(jsonPath("$.primaryAction").value("WRITE_NEXT"))
                .andExpect(jsonPath("$.todoList").isEmpty())
                .andExpect(jsonPath("$.busy").value(false));
    }

    @Test
    void 待人审状态带稿号与阻塞原因() throws Exception {
        String pid = seedProject("WRITING");
        writeChapter(pid, 1, Chapter.Status.DONE);
        writeChapter(pid, 2, Chapter.Status.NEEDS_MANUAL_REVIEW);
        repository.findProject(pid).ifPresent(p -> { p.setCurrentChapterIndex(2); repository.saveProject(p); });

        mvc.perform(get("/api/projects/" + pid + "/workspace"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.collabState").value("AWAITING_REVIEW"))
                .andExpect(jsonPath("$.canContinue").value(false))
                .andExpect(jsonPath("$.blockedReason").value(org.hamcrest.Matchers.containsString("人工复核")))
                .andExpect(jsonPath("$.primaryAction").value("REVIEW_CHAPTER"))
                .andExpect(jsonPath("$.todoList[0].chapterIndex").value(2))
                .andExpect(jsonPath("$.todoList[0].chapterVersion").value(org.hamcrest.Matchers.greaterThan(0)))
                .andExpect(jsonPath("$.todoList[0].kind").value("MANUAL_REVIEW"))
                .andExpect(jsonPath("$.todoList[0].reason").exists());
    }

    @Test
    void 放行后恢复可写() throws Exception {
        String pid = seedProject("WRITING");
        writeChapter(pid, 1, Chapter.Status.DONE);
        writeChapter(pid, 2, Chapter.Status.NEEDS_MANUAL_REVIEW);
        repository.findProject(pid).ifPresent(p -> { p.setCurrentChapterIndex(2); repository.saveProject(p); });

        // p36-13: 放行改走版本化批准——读 review-context 后提交明确身份（新幂等键）
        MvcResult contextResult = mvc.perform(get("/api/projects/" + pid + "/chapters/2/review-context"))
                .andExpect(status().isOk()).andReturn();
        JsonNode context = om.readTree(contextResult.getResponse().getContentAsString(StandardCharsets.UTF_8));
        int version = context.path("identity").path("version").asInt();
        mvc.perform(post("/api/projects/" + pid + "/chapters/2/review-decisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of(
                                "identity", Map.of(
                                        "projectId", pid,
                                        "chapterIndex", 2,
                                        "version", version,
                                        "contentHash", context.path("identity").path("contentHash").asText()),
                                "reviewSetHash", context.path("reviewSetHash").asText(),
                                "idempotencyKey", "workspace-" + UUID.randomUUID()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(false))
                .andExpect(jsonPath("$.identity.version").value(version));

        // 批准记录 identity 与被读章节一致
        JsonNode after = om.readTree(mvc.perform(get("/api/projects/" + pid + "/chapters/2/review-context"))
                .andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8));
        org.junit.jupiter.api.Assertions.assertEquals(version,
                after.path("approval").path("identity").path("version").asInt(),
                "批准记录 identity 必须与被读章节版本一致");

        mvc.perform(get("/api/projects/" + pid + "/workspace"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.collabState").value("WRITING"))
                .andExpect(jsonPath("$.canContinue").value(true))
                .andExpect(jsonPath("$.todoList").isEmpty());
    }

    @Test
    void 同步占坑期间workspace暴露activeWork且取消释放工作权() throws Exception {
        String pid = seedProject("WRITING");
        writeChapter(pid, 1, Chapter.Status.DONE);
        repository.findProject(pid).ifPresent(p -> { p.setCurrentChapterIndex(1); repository.saveProject(p); });

        // NCS-074: 匿名同步占坑（如补查）必须可观测——workspace 暴露 kind/label/已用时
        writingTaskExecutor.beginWork(pid, "recheck-3f2a9f0e-1b2c-3d4e-5f60-7a8b9c0d1e2f");
        mvc.perform(get("/api/projects/" + pid + "/workspace"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.busy").value(true))
                .andExpect(jsonPath("$.activeWork.kind").value("recheck"))
                .andExpect(jsonPath("$.activeWork.label").value("连续性补查"))
                .andExpect(jsonPath("$.activeWork.elapsedSeconds")
                        .value(org.hamcrest.Matchers.greaterThanOrEqualTo(0)));

        // NCS-074: 取消匿名占坑 → 立即释放项目工作权
        mvc.perform(post("/api/projects/" + pid + "/active-work/cancel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.released").value(true));
        mvc.perform(get("/api/projects/" + pid + "/workspace"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.busy").value(false))
                .andExpect(jsonPath("$.activeWork").doesNotExist());
    }

    @Test
    void 任务型占坑不暴露activeWork也不可经同步取消路径释放() throws Exception {
        String pid = seedProject("WRITING");
        writeChapter(pid, 1, Chapter.Status.DONE);
        repository.findProject(pid).ifPresent(p -> { p.setCurrentChapterIndex(1); repository.saveProject(p); });

        WritingTask task = new WritingTask(pid, 1, WritingTask.Type.WRITE_CHAPTER);
        repository.saveWritingTask(task);
        writingTaskExecutor.beginWork(pid, task.getId());
        try {
            mvc.perform(get("/api/projects/" + pid + "/workspace"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.busy").value(true))
                    .andExpect(jsonPath("$.activeWork").doesNotExist());
            mvc.perform(post("/api/projects/" + pid + "/active-work/cancel"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.released").value(false));
            mvc.perform(get("/api/projects/" + pid + "/workspace"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.busy").value(true));
        } finally {
            writingTaskExecutor.endWork(pid, task.getId());
        }
    }
}
