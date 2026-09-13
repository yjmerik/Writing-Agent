package com.writing.novel.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.writing.novel.domain.Chapter;
import com.writing.novel.domain.ChapterSynopsis;
import com.writing.novel.domain.ChapterVersion;
import com.writing.novel.domain.Checkpoint;
import com.writing.novel.domain.Outline;
import com.writing.novel.domain.Project;
import com.writing.novel.domain.ReviewBarrier;
import com.writing.novel.domain.Volume;
import com.writing.novel.memory.MemoryRevision;
import com.writing.novel.persistence.sqlite.ConnectionProvider;
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
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(StubLlmGatewayTestConfig.class)
@TestPropertySource(properties = {
        "persistence.embedding=stub",
        "persistence.sqlite.db-path=./data/test-chapter-direct-edit.db",
        "persistence.vector-store.index-path=./data/test-chapter-direct-edit-vecs.idx"
})
class ChapterDirectEditTest {

    private static final String LONG_BODY = "这是一段满足人工章节保存门槛的正文。".repeat(40);

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired NovelRepository repository;
    @Autowired WritingTaskExecutor writingTaskExecutor;
    @Autowired MemoryRevision memoryRevision;
    @Autowired ConnectionProvider connectionProvider;

    @BeforeAll
    static void createDataDir() {
        new File("./data").mkdirs();
    }

    @Test
    void 新增草稿使用下一全局章号且短正文保持草稿长正文可完成() throws Exception {
        String projectId = project("新增草稿");
        chapter(projectId, 1, "第一章", LONG_BODY);
        chapter(projectId, 3, "第三章", LONG_BODY);

        mvc.perform(post("/api/projects/{id}/chapters", projectId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("title", "第四章", "content", "短草稿"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chapterIndex").value(4))
                .andExpect(jsonPath("$.title").value("第四章"))
                .andExpect(jsonPath("$.content").value("短草稿"))
                .andExpect(jsonPath("$.status").value("DRAFTING"));

        mvc.perform(post("/api/projects/{id}/chapters", projectId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("title", "第五章", "content", LONG_BODY))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chapterIndex").value(5))
                .andExpect(jsonPath("$.status").value("DONE"));

        mvc.perform(post("/api/projects/{id}/chapters", projectId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("title", "   ", "content", "草稿"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 空白新草稿可在后续保存后精确回退为空白草稿() throws Exception {
        String projectId = project("空白草稿回退");
        MvcResult createdResult = mvc.perform(post("/api/projects/{id}/chapters", projectId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("title", "空白草稿"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value(""))
                .andExpect(jsonPath("$.status").value("DRAFTING"))
                .andReturn();
        JsonNode created = objectMapper.readTree(
                createdResult.getResponse().getContentAsString(StandardCharsets.UTF_8));
        int blankVersion = created.path("version").asInt();

        JsonNode saved = edit(projectId, 1, blankVersion, "空白草稿", "补上一段草稿正文", 200);
        mvc.perform(post("/api/projects/{id}/chapters/{index}/restore/{version}",
                        projectId, 1, blankVersion)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("expectedVersion", saved.path("version").asInt()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value(""))
                .andExpect(jsonPath("$.status").value("DRAFTING"));
    }

    @Test
    void 直接编辑生成变更前不可变快照且历史倒序可精确回退() throws Exception {
        String projectId = project("编辑回退");
        Chapter original = chapter(projectId, 1, "旧标题", LONG_BODY);
        int originalVersion = original.getVersion();

        JsonNode edited = edit(projectId, 1, originalVersion, "新标题", LONG_BODY + "第一次修改。", 200);
        assertEquals(originalVersion + 1, edited.path("version").asInt());
        assertEquals("新标题", edited.path("title").asText());

        List<ChapterVersion> firstHistory = repository.findChapterVersions(projectId, 1);
        assertEquals(1, firstHistory.size());
        assertEquals(originalVersion, firstHistory.get(0).getVersion());
        assertEquals("旧标题", firstHistory.get(0).getTitle());
        assertEquals(LONG_BODY, firstHistory.get(0).getContent());
        assertEquals("DIRECT_EDIT", firstHistory.get(0).getSource());

        JsonNode editedAgain = edit(projectId, 1, edited.path("version").asInt(),
                "第三版", LONG_BODY + "第二次修改。", 200);
        int beforeRestoreVersion = editedAgain.path("version").asInt();

        mvc.perform(get("/api/projects/{id}/chapters/{index}/versions", projectId, 1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].version").value(originalVersion + 1))
                .andExpect(jsonPath("$[1].version").value(originalVersion));

        mvc.perform(post("/api/projects/{id}/chapters/{index}/restore/{version}",
                        projectId, 1, originalVersion)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("expectedVersion", beforeRestoreVersion))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(beforeRestoreVersion + 1))
                .andExpect(jsonPath("$.title").value("旧标题"))
                .andExpect(jsonPath("$.content").value(LONG_BODY));

        List<ChapterVersion> history = repository.findChapterVersions(projectId, 1);
        assertEquals(List.of(beforeRestoreVersion, originalVersion + 1, originalVersion),
                history.stream().map(ChapterVersion::getVersion).toList());
        assertEquals("RESTORE", history.get(0).getSource());
        assertEquals("第三版", history.get(0).getTitle(), "回退前快照必须保持不可变");
        assertEquals("旧标题", repository.findChapterVersion(projectId, 1, originalVersion)
                .orElseThrow().getTitle());
    }

    @Test
    void 陈旧版本和跨项目目标均冲突且章节历史与派生状态零变化() throws Exception {
        String owner = project("章节所有者");
        String other = project("错误项目");
        Chapter original = chapter(owner, 1, "原始标题", LONG_BODY);
        JsonNode first = edit(owner, 1, original.getVersion(), "已保存标题", LONG_BODY + "已保存。", 200);
        Chapter before = repository.findChapterByIndex(owner, 1).orElseThrow();
        List<String> historyBefore = historySnapshot(owner, 1);
        long revisionBefore = memoryRevision.current(owner);

        edit(owner, 1, original.getVersion(), "陈旧覆盖", LONG_BODY + "陈旧。", 409);
        edit(other, 1, before.getVersion(), "跨项目覆盖", LONG_BODY + "越权。", 400);

        Chapter after = repository.findChapterByIndex(owner, 1).orElseThrow();
        assertEquals(first.path("version").asInt(), after.getVersion());
        assertEquals("已保存标题", after.getTitle());
        assertEquals(LONG_BODY + "已保存。", after.getContent());
        assertEquals(historyBefore, historySnapshot(owner, 1));
        assertEquals(revisionBefore, memoryRevision.current(owner));
        assertTrue(repository.findChapterVersions(other, 1).isEmpty());
    }

    @Test
    void 标题正文校验在快照前完成且完成章节不得降到五百字以下() throws Exception {
        String projectId = project("输入校验");
        Chapter chapter = chapter(projectId, 1, "原始标题", LONG_BODY);
        int version = chapter.getVersion();

        edit(projectId, 1, version, " ", LONG_BODY, 400);
        edit(projectId, 1, version, "有效标题", " ", 400);
        edit(projectId, 1, version, "有效标题", "不足五百字", 400);

        Chapter after = repository.findChapterByIndex(projectId, 1).orElseThrow();
        assertEquals(version, after.getVersion());
        assertEquals("原始标题", after.getTitle());
        assertEquals(LONG_BODY, after.getContent());
        assertTrue(repository.findChapterVersions(projectId, 1).isEmpty());
    }

    @Test
    void 同一expectedVersion并发编辑只有一个成功者() throws Exception {
        String projectId = project("并发编辑");
        Chapter original = chapter(projectId, 1, "原始标题", LONG_BODY);
        int expected = original.getVersion();

        CompletableFuture<Integer> first = CompletableFuture.supplyAsync(() ->
                editStatus(projectId, expected, "并发甲", LONG_BODY + "甲。"));
        CompletableFuture<Integer> second = CompletableFuture.supplyAsync(() ->
                editStatus(projectId, expected, "并发乙", LONG_BODY + "乙。"));

        List<Integer> statuses = List.of(first.join(), second.join()).stream().sorted().toList();
        assertEquals(List.of(200, 409), statuses);
        assertEquals(expected + 1, repository.findChapterByIndex(projectId, 1).orElseThrow().getVersion());
        assertEquals(1, repository.findChapterVersions(projectId, 1).size());
    }

    @Test
    void 活动写作期间所有章节mutation拒绝且释放后恢复() throws Exception {
        String projectId = project("工作权");
        Chapter original = chapter(projectId, 1, "原始标题", LONG_BODY);
        writingTaskExecutor.beginWork(projectId, "active-writer");
        try {
            edit(projectId, 1, original.getVersion(), "冲突编辑", LONG_BODY + "冲突。", 409);
            mvc.perform(post("/api/projects/{id}/chapters", projectId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("title", "新草稿", "content", "草稿"))))
                    .andExpect(status().isConflict());
            mvc.perform(post("/api/projects/{id}/chapters/{index}/restore/{version}",
                            projectId, 1, original.getVersion())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("expectedVersion", original.getVersion()))))
                    .andExpect(status().isConflict());
        } finally {
            writingTaskExecutor.endWork(projectId, "active-writer");
        }
        assertEquals(original.getVersion(), repository.findChapterByIndex(projectId, 1).orElseThrow().getVersion());
        assertTrue(repository.findChapterVersions(projectId, 1).isEmpty());
    }

    @Test
    void 章节检查点重写与直接编辑共用同一工作所有权边界() throws Exception {
        String projectId = project("检查点并发保护");
        Chapter original = chapter(projectId, 1, "原始标题", LONG_BODY);
        Project project = repository.findProject(projectId).orElseThrow();
        project.setCurrentChapterIndex(1);
        repository.saveProject(project);
        Outline outline = new Outline(projectId, "总纲");
        Volume volume = new Volume(projectId, 1, "第一卷", "卷摘要");
        volume.addSynopsis(new ChapterSynopsis(projectId, 1, "原始标题", "章节梗概"));
        outline.addVolume(volume);
        repository.saveOutline(outline);
        Checkpoint checkpoint = new Checkpoint(projectId, Checkpoint.Type.AFTER_CHAPTER, "第1章完成");
        repository.saveCheckpoint(checkpoint);

        writingTaskExecutor.beginWork(projectId, "active-direct-edit");
        try {
            mvc.perform(post("/api/projects/{id}/checkpoints/{checkpointId}/modify",
                            projectId, checkpoint.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("instructions", "增加对话"))))
                    .andExpect(status().isConflict());
        } finally {
            writingTaskExecutor.endWork(projectId, "active-direct-edit");
        }

        assertEquals(Checkpoint.Status.PENDING,
                repository.findCheckpoint(checkpoint.getId()).orElseThrow().getStatus());
        Chapter after = repository.findChapterByIndex(projectId, 1).orElseThrow();
        assertEquals(original.getVersion(), after.getVersion());
        assertEquals(original.getContent(), after.getContent());
        assertTrue(repository.findChapterVersions(projectId, 1).isEmpty());
    }

    /**
     * p36-12: 版本化批准（p36-04）与章节编辑共用同一工作所有权边界——
     * 旧入口永久 409 后（p36-06），本用例保证工权冲突覆盖仍由新接口真实承载：
     * 占用期间批准 409 WORK_OWNERSHIP_CONFLICT 且章节屏障不变；释放后同键批准成功（重试语义）。
     */
    @Test
    void 版本化批准与直接编辑共用同一工作所有权边界() throws Exception {
        String projectId = project("批准工权");
        Chapter chapter = new Chapter(projectId, 1, "第一章");
        chapter.setContent(LONG_BODY);
        chapter.setStatus(Chapter.Status.NEEDS_MANUAL_REVIEW);
        repository.saveChapter(chapter);
        repository.saveReviewBarrier(new ReviewBarrier(projectId, 1, chapter.getVersion(), "根源矛盾待复核"));

        JsonNode context = readReviewContext(projectId);
        String request = approvalRequest(projectId, context, "cde-key-busy");

        writingTaskExecutor.beginWork(projectId, "active-approver");
        try {
            mvc.perform(post("/api/projects/{id}/chapters/{index}/review-decisions", projectId, 1)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(request))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("WORK_OWNERSHIP_CONFLICT"));
        } finally {
            writingTaskExecutor.endWork(projectId, "active-approver");
        }

        assertEquals(Chapter.Status.NEEDS_MANUAL_REVIEW,
                repository.findChapterByIndex(projectId, 1).orElseThrow().getStatus());
        assertEquals(1, repository.findActiveReviewBarriers(projectId).size());

        // 释放后同一请求（同幂等键重试语义）批准成功
        mvc.perform(post("/api/projects/{id}/chapters/{index}/review-decisions", projectId, 1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(false));
        assertEquals(Chapter.Status.DONE, repository.findChapterByIndex(projectId, 1).orElseThrow().getStatus());
        assertTrue(repository.findActiveReviewBarriers(projectId).isEmpty(), "批准后屏障解除");
    }

    @Test
    void 当前章节与快照必须同事务失败时不能留下半份历史() throws Exception {
        String projectId = project("事务回滚");
        Chapter original = chapter(projectId, 1, "原始标题", LONG_BODY);
        String trigger = "fail_chapter_revision_" + projectId.replace("-", "");
        try (Connection connection = connectionProvider.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TRIGGER " + trigger + " BEFORE UPDATE ON chapters " +
                    "WHEN OLD.id='" + original.getId() + "' BEGIN SELECT RAISE(ABORT, 'forced'); END");
        }
        try {
            edit(projectId, 1, original.getVersion(), "不应保存", LONG_BODY + "不应保存。", 500);
        } finally {
            try (Connection connection = connectionProvider.getConnection(); Statement statement = connection.createStatement()) {
                statement.execute("DROP TRIGGER IF EXISTS " + trigger);
            }
        }
        Chapter after = repository.findChapterByIndex(projectId, 1).orElseThrow();
        assertEquals(original.getVersion(), after.getVersion());
        assertEquals("原始标题", after.getTitle());
        assertTrue(repository.findChapterVersions(projectId, 1).isEmpty());
    }

    private JsonNode readReviewContext(String projectId) throws Exception {
        MvcResult result = mvc.perform(get("/api/projects/{id}/chapters/{index}/review-context", projectId, 1))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private String approvalRequest(String projectId, JsonNode context, String idempotencyKey) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "identity", Map.of(
                        "projectId", projectId,
                        "chapterIndex", 1,
                        "version", context.path("identity").path("version").asInt(),
                        "contentHash", context.path("identity").path("contentHash").asText()),
                "reviewSetHash", context.path("reviewSetHash").asText(),
                "idempotencyKey", idempotencyKey));
    }

    private String project(String title) {
        Project project = new Project(title, "概念", "现实");
        repository.saveProject(project);
        return project.getId();
    }

    private Chapter chapter(String projectId, int index, String title, String content) {
        Chapter chapter = new Chapter(projectId, index, title);
        chapter.setContent(content);
        chapter.setStatus(Chapter.Status.DONE);
        repository.saveChapter(chapter);
        return chapter;
    }

    private JsonNode edit(String projectId, int index, int expectedVersion,
                          String title, String content, int expectedStatus) throws Exception {
        MvcResult result = mvc.perform(put("/api/projects/{id}/chapters/{index}", projectId, index)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "expectedVersion", expectedVersion,
                                "title", title,
                                "content", content))))
                .andExpect(status().is(expectedStatus))
                .andReturn();
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8).isBlank()
                ? objectMapper.createObjectNode()
                : objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private int editStatus(String projectId, int expectedVersion, String title, String content) {
        try {
            return mvc.perform(put("/api/projects/{id}/chapters/1", projectId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of(
                                    "expectedVersion", expectedVersion,
                                    "title", title,
                                    "content", content))))
                    .andReturn().getResponse().getStatus();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private List<String> historySnapshot(String projectId, int index) {
        return repository.findChapterVersions(projectId, index).stream()
                .map(v -> v.getVersion() + "|" + v.getTitle() + "|" + v.getContent() + "|" + v.getSource())
                .toList();
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }
}
