package com.writing.novel.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.writing.novel.domain.Chapter;
import com.writing.novel.domain.ChapterSynopsis;
import com.writing.novel.domain.Checkpoint;
import com.writing.novel.domain.Outline;
import com.writing.novel.domain.Project;
import com.writing.novel.domain.ReviewBarrier;
import com.writing.novel.domain.Volume;
import com.writing.novel.spi.NovelRepository;
import org.junit.jupiter.api.BeforeAll;
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
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * p36-06：无版本批准的兼容旁路关闭。
 * 1) 旧 POST /chapters/{n}/resolve-review → 409，章节/屏障不变；
 * 2) 章节类（AFTER_CHAPTER / ON_CONTINUITY_FIX）checkpoint approve → 409，checkpoint 保持 PENDING；
 * 3) 章节类 checkpoint modify 保留重写但不再默认批准——checkpoint 回到待审，屏障不放行；
 * 4) 初始设定/卷计划 checkpoint 审批保留独立流程；
 * 5) 新版本化批准入口全量可用。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(StubLlmGatewayTestConfig.class)
@TestPropertySource(properties = {
        "persistence.embedding=stub",
        "persistence.sqlite.db-path=./data/test-legacy-approval-gate.db",
        "persistence.vector-store.index-path=./data/test-legacy-approval-gate-vecs.idx"
})
class LegacyApprovalGateTest {

    private static final String LONG_BODY = "待作者复核的正文内容，长度满足已完成章节口径。".repeat(20);

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;
    @Autowired NovelRepository repository;

    private String pid;
    private String afterChapterCpId;
    private String continuityCpId;
    private String initialSetupCpId;

    @BeforeAll
    static void createDataDir() { new File("./data").mkdirs(); }

    @BeforeEach
    void setUp() {
        Project project = new Project("旁路关闭", "概念", "悬疑");
        project.setState(com.writing.novel.domain.NovelState.WRITING);
        repository.saveProject(project);
        pid = project.getId();

        // 大纲（modify 重写路径需要梗概）
        Outline outline = new Outline(pid, "总纲：旁路关闭验证");
        Volume volume = new Volume(pid, 1, "卷一", "卷摘要");
        volume.addSynopsis(new ChapterSynopsis(pid, 1, "第一章", "章节梗概"));
        outline.addVolume(volume);
        repository.saveOutline(outline);

        Chapter chapter = new Chapter(pid, 1, "第一章");
        chapter.setContent(LONG_BODY);
        chapter.setStatus(Chapter.Status.NEEDS_MANUAL_REVIEW);
        repository.saveChapter(chapter);
        repository.saveReviewBarrier(new ReviewBarrier(pid, 1, chapter.getVersion(), "根源矛盾待复核"));

        Checkpoint afterChapter = new Checkpoint(pid, Checkpoint.Type.AFTER_CHAPTER, "第1章「第一章」已完成，待审核");
        afterChapter.setPayload("{\"chapterIndex\":1,\"title\":\"第一章\"}");
        repository.saveCheckpoint(afterChapter);
        afterChapterCpId = afterChapter.getId();

        Checkpoint continuity = new Checkpoint(pid, Checkpoint.Type.ON_CONTINUITY_FIX, "第1章发现矛盾待修复");
        continuity.setPayload("{\"chapterIndex\":1}");
        repository.saveCheckpoint(continuity);
        continuityCpId = continuity.getId();

        Checkpoint initialSetup = new Checkpoint(pid, Checkpoint.Type.AFTER_INITIAL_SETUP, "初始设定已生成，待审核");
        repository.saveCheckpoint(initialSetup);
        initialSetupCpId = initialSetup.getId();

        repository.findProject(pid).ifPresent(p -> {
            p.setCurrentChapterIndex(1);
            repository.saveProject(p);
        });
    }

    @Test
    void 旧resolveReview入口关闭且不改变章节屏障() throws Exception {
        mvc.perform(post("/api/projects/" + pid + "/chapters/1/resolve-review"))
                .andExpect(status().isConflict());

        assertEquals(Chapter.Status.NEEDS_MANUAL_REVIEW,
                repository.findChapterByIndex(pid, 1).orElseThrow().getStatus(), "章节保持待审");
        assertEquals(1, repository.findActiveReviewBarriers(pid).size(), "屏障不放行");
    }

    @Test
    void 章节类checkpointApprove拒绝且checkpoint保持待审() throws Exception {
        mvc.perform(post("/api/projects/" + pid + "/checkpoints/" + afterChapterCpId + "/approve"))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/projects/" + pid + "/checkpoints/" + continuityCpId + "/approve"))
                .andExpect(status().isConflict());

        assertEquals(Checkpoint.Status.PENDING,
                repository.findCheckpoint(afterChapterCpId).orElseThrow().getStatus());
        assertEquals(Checkpoint.Status.PENDING,
                repository.findCheckpoint(continuityCpId).orElseThrow().getStatus());
        assertEquals(Chapter.Status.NEEDS_MANUAL_REVIEW,
                repository.findChapterByIndex(pid, 1).orElseThrow().getStatus(), "章节不得被无版本放行");
        assertEquals(1, repository.findActiveReviewBarriers(pid).size(), "屏障不放行");
    }

    @Test
    void 章节类checkpointModify保留重写但回到待审不放行() throws Exception {
        int versionBefore = repository.findChapterByIndex(pid, 1).orElseThrow().getVersion();

        mvc.perform(post("/api/projects/" + pid + "/checkpoints/" + afterChapterCpId + "/modify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("instructions", "增加对话，改善节奏"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("modified_pending_review"));

        // checkpoint 回到待审（不得默认批准），屏障不放行
        assertEquals(Checkpoint.Status.PENDING,
                repository.findCheckpoint(afterChapterCpId).orElseThrow().getStatus(),
                "modify 不得默认批准 checkpoint");
        assertTrue(repository.findActiveReviewBarriers(pid).size() >= 1, "屏障不放行");
        Chapter after = repository.findChapterByIndex(pid, 1).orElseThrow();
        assertTrue(after.getVersion() > versionBefore, "重写动作保存了新稿");
        assertEquals(Chapter.Status.NEEDS_MANUAL_REVIEW, after.getStatus(), "新稿回到待审");
    }

    @Test
    void 连续性checkpointModify返回409且无任何改变() throws Exception {
        int versionBefore = repository.findChapterByIndex(pid, 1).orElseThrow().getVersion();

        mvc.perform(post("/api/projects/" + pid + "/checkpoints/" + continuityCpId + "/modify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("instructions", "修复矛盾"))))
                .andExpect(status().isConflict());

        assertEquals(Checkpoint.Status.PENDING,
                repository.findCheckpoint(continuityCpId).orElseThrow().getStatus());
        assertEquals(versionBefore,
                repository.findChapterByIndex(pid, 1).orElseThrow().getVersion(), "章节不变");
        assertEquals(1, repository.findActiveReviewBarriers(pid).size(), "屏障不放行");
    }

    @Test
    void 初始设定checkpoint批准保留独立流程() throws Exception {
        mvc.perform(post("/api/projects/" + pid + "/checkpoints/" + initialSetupCpId + "/approve"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("approved"));

        assertEquals(Checkpoint.Status.APPROVED,
                repository.findCheckpoint(initialSetupCpId).orElseThrow().getStatus(),
                "初始设定审批流程不受影响");
    }

    @Test
    void 版本化批准新入口全量可用() throws Exception {
        MvcResult contextResult = mvc.perform(get("/api/projects/" + pid + "/chapters/1/review-context"))
                .andExpect(status().isOk()).andReturn();
        JsonNode context = om.readTree(contextResult.getResponse().getContentAsString(StandardCharsets.UTF_8));

        mvc.perform(post("/api/projects/" + pid + "/chapters/1/review-decisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of(
                                "identity", Map.of(
                                        "projectId", pid,
                                        "chapterIndex", 1,
                                        "version", context.path("identity").path("version").asInt(),
                                        "contentHash", context.path("identity").path("contentHash").asText()),
                                "reviewSetHash", context.path("reviewSetHash").asText(),
                                "idempotencyKey", "legacy-gate-" + UUID.randomUUID()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(false));

        assertEquals(Chapter.Status.DONE, repository.findChapterByIndex(pid, 1).orElseThrow().getStatus());
        assertTrue(repository.findActiveReviewBarriers(pid).isEmpty(), "屏障解除");
        assertEquals(Checkpoint.Status.APPROVED,
                repository.findCheckpoint(afterChapterCpId).orElseThrow().getStatus(),
                "同章章节类检查点由版本化批准一并处理");
    }

    private String json(Object value) throws Exception {
        return om.writeValueAsString(value);
    }
}
