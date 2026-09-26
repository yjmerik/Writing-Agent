package com.writing.novel.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.writing.novel.agent.CheckResultEnvelope;
import com.writing.novel.domain.Chapter;
import com.writing.novel.domain.ChapterSynopsis;
import com.writing.novel.domain.Checkpoint;
import com.writing.novel.domain.NovelState;
import com.writing.novel.domain.Outline;
import com.writing.novel.domain.Project;
import com.writing.novel.domain.Volume;
import com.writing.novel.persistence.sqlite.CheckResultRepository;
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
 * NCS-088：未查清检查信封的作者担责豁免（AUTHOR_WAIVED）。
 * 1) 版本化批准成功后，该章最新 UNKNOWN/INVALID 信封自动追加 AUTHOR_WAIVED 行（reason 记 approvalId）；
 * 2) unknownCount 随之清零——成书门禁不再因 unknown 阻塞；
 * 3) 手动豁免端点：缺 reason 400、身份不符 409、成功后最新状态 AUTHOR_WAIVED 且 reason 可审计；
 * 4) 已有有效结论（SUCCESS/VALID_EMPTY）的检查拒绝豁免；
 * 5) 豁免后审阅分类视图不再列出该未查清项。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(StubLlmGatewayTestConfig.class)
@TestPropertySource(properties = {
        "persistence.embedding=stub",
        "persistence.sqlite.db-path=./data/test-author-waiver-envelope.db",
        "persistence.vector-store.index-path=./data/test-author-waiver-envelope-vecs.idx"
})
class AuthorWaiverCheckEnvelopeTest {

    private static final String LONG_BODY = "作者担责豁免的正文内容，长度满足已完成章节口径。".repeat(20);

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;
    @Autowired NovelRepository repository;
    @Autowired CheckResultRepository checkResultRepository;

    private String pid;

    @BeforeAll
    static void createDataDir() { new File("./data").mkdirs(); }

    @BeforeEach
    void setUp() {
        Project project = new Project("担责豁免", "概念", "悬疑");
        project.setState(NovelState.WRITING);
        repository.saveProject(project);
        pid = project.getId();

        Outline outline = new Outline(pid, "总纲：担责豁免验证");
        Volume volume = new Volume(pid, 1, "卷一", "卷摘要");
        volume.addSynopsis(new ChapterSynopsis(pid, 1, "第一章", "章节梗概"));
        volume.addSynopsis(new ChapterSynopsis(pid, 2, "第二章", "章节梗概"));
        outline.addVolume(volume);
        repository.saveOutline(outline);

        for (int i : new int[]{1, 2}) {
            Chapter chapter = new Chapter(pid, i, i == 1 ? "第一章" : "第二章");
            chapter.setContent(LONG_BODY);
            chapter.setStatus(Chapter.Status.NEEDS_MANUAL_REVIEW);
            repository.saveChapter(chapter);
            Checkpoint cp = new Checkpoint(pid, Checkpoint.Type.AFTER_CHAPTER,
                    "第" + i + "章「" + chapter.getTitle() + "」已完成，待审核");
            cp.setPayload("{\"chapterIndex\":" + i + "}");
            repository.saveCheckpoint(cp);
        }
        repository.findProject(pid).ifPresent(p -> {
            p.setCurrentChapterIndex(2);
            repository.saveProject(p);
        });
    }

    private JsonNode reviewContext(int chapter) throws Exception {
        MvcResult result = mvc.perform(get("/api/projects/" + pid + "/chapters/" + chapter + "/review-context"))
                .andExpect(status().isOk()).andReturn();
        return om.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private String waiverBody(JsonNode context, String checker, String reason) {
        var body = om.createObjectNode();
        var identity = body.putObject("identity");
        identity.put("projectId", pid);
        identity.put("chapterIndex", context.path("identity").path("chapterIndex").asInt());
        identity.put("version", context.path("identity").path("version").asInt());
        identity.put("contentHash", context.path("identity").path("contentHash").asText());
        body.put("checker", checker);
        if (reason != null) body.put("reason", reason);
        return body.toString();
    }

    @Test
    void 批准成功后自动担责豁免该章未查清信封() throws Exception {
        checkResultRepository.save(pid, 1, "consistency-guard",
                CheckResultEnvelope.Status.UNKNOWN, "守卫输出不可解析", LONG_BODY);
        assertEquals(1, checkResultRepository.unknownCount(pid), "前置：存在未查清");

        JsonNode context = reviewContext(1);
        mvc.perform(post("/api/projects/" + pid + "/chapters/1/review-decisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of(
                                "identity", Map.of(
                                        "projectId", pid,
                                        "chapterIndex", 1,
                                        "version", context.path("identity").path("version").asInt(),
                                        "contentHash", context.path("identity").path("contentHash").asText()),
                                "reviewSetHash", context.path("reviewSetHash").asText(),
                                "idempotencyKey", "waiver-" + UUID.randomUUID()))))
                .andExpect(status().isOk());

        CheckResultRepository.LatestCheck latest = checkResultRepository.latestForChapter(pid, 1).stream()
                .filter(c -> c.checker().equals("consistency-guard")).findFirst().orElseThrow();
        assertEquals(CheckResultEnvelope.Status.AUTHOR_WAIVED, latest.status(), "批准后自动担责豁免");
        assertTrue(latest.reason().contains("approvalId="), "豁免行可追溯批准号：" + latest.reason());
        assertEquals(0, checkResultRepository.unknownCount(pid), "豁免后门禁不再因 unknown 阻塞");
    }

    @Test
    void 幂等重放批准不重复追加豁免行() throws Exception {
        checkResultRepository.save(pid, 1, "consistency-guard",
                CheckResultEnvelope.Status.UNKNOWN, "守卫超时", LONG_BODY);
        JsonNode context = reviewContext(1);
        String body = om.writeValueAsString(Map.of(
                "identity", Map.of(
                        "projectId", pid,
                        "chapterIndex", 1,
                        "version", context.path("identity").path("version").asInt(),
                        "contentHash", context.path("identity").path("contentHash").asText()),
                "reviewSetHash", context.path("reviewSetHash").asText(),
                "idempotencyKey", "waiver-replay-fixed-key"));
        mvc.perform(post("/api/projects/" + pid + "/chapters/1/review-decisions")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        long waiversAfterFirst = checkResultRepository.latestForChapter(pid, 1).stream()
                .filter(c -> c.status() == CheckResultEnvelope.Status.AUTHOR_WAIVED).count();
        mvc.perform(post("/api/projects/" + pid + "/chapters/1/review-decisions")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(true));
        long waiversAfterReplay = checkResultRepository.latestForChapter(pid, 1).stream()
                .filter(c -> c.status() == CheckResultEnvelope.Status.AUTHOR_WAIVED).count();
        assertEquals(waiversAfterFirst, waiversAfterReplay, "重放不重复追加豁免");
    }

    @Test
    void 手动豁免需要理由并绑定当前稿身份() throws Exception {
        checkResultRepository.save(pid, 2, "correction-verification",
                CheckResultEnvelope.Status.UNKNOWN, "复验中断", LONG_BODY);
        JsonNode context = reviewContext(2);

        mvc.perform(post("/api/projects/" + pid + "/chapters/2/check-waiver")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(waiverBody(context, "correction-verification", null)))
                .andExpect(status().isBadRequest());

        mvc.perform(post("/api/projects/" + pid + "/chapters/2/check-waiver")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of(
                                "identity", Map.of(
                                        "projectId", pid,
                                        "chapterIndex", 2,
                                        "version", context.path("identity").path("version").asInt() + 5,
                                        "contentHash", context.path("identity").path("contentHash").asText()),
                                "checker", "correction-verification",
                                "reason", "理由"))))
                .andExpect(status().isConflict());

        mvc.perform(post("/api/projects/" + pid + "/chapters/2/check-waiver")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(waiverBody(context, "correction-verification", "作者已复核：该复验中断不影响结论成立")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AUTHOR_WAIVED"));
        CheckResultRepository.LatestCheck latest = checkResultRepository.latestForChapter(pid, 2).stream()
                .filter(c -> c.checker().equals("correction-verification")).findFirst().orElseThrow();
        assertEquals(CheckResultEnvelope.Status.AUTHOR_WAIVED, latest.status());
        assertTrue(latest.reason().contains("作者担责豁免") && latest.reason().contains("作者已复核"),
                "豁免理由可审计：" + latest.reason());
        assertEquals(0, checkResultRepository.unknownCount(pid));
    }

    @Test
    void 已有有效结论的检查拒绝豁免() throws Exception {
        checkResultRepository.save(pid, 1, "continuity",
                CheckResultEnvelope.Status.SUCCESS, "无问题", LONG_BODY);
        JsonNode context = reviewContext(1);
        mvc.perform(post("/api/projects/" + pid + "/chapters/1/check-waiver")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(waiverBody(context, "continuity", "多余豁免")))
                .andExpect(status().isConflict());
        CheckResultRepository.LatestCheck latest = checkResultRepository.latestForChapter(pid, 1).stream()
                .filter(c -> c.checker().equals("continuity")).findFirst().orElseThrow();
        assertEquals(CheckResultEnvelope.Status.SUCCESS, latest.status(), "有效结论不被覆盖");
    }

    @Test
    void 豁免后审阅分类视图不再列出未查清() throws Exception {
        checkResultRepository.save(pid, 2, "consistency-guard",
                CheckResultEnvelope.Status.UNKNOWN, "守卫未完成", LONG_BODY);
        mvc.perform(get("/api/projects/" + pid + "/review-classification"))
                .andExpect(jsonPath("$.unknown.length()").value(1));
        JsonNode context = reviewContext(2);
        mvc.perform(post("/api/projects/" + pid + "/chapters/2/check-waiver")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(waiverBody(context, "consistency-guard", "作者已复核本章守卫关注点，确认无此类矛盾")))
                .andExpect(status().isOk());
        mvc.perform(get("/api/projects/" + pid + "/review-classification"))
                .andExpect(jsonPath("$.unknown.length()").value(0));
    }
}
