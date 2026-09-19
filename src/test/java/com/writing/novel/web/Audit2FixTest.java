package com.writing.novel.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.writing.novel.domain.*;
import com.writing.novel.persistence.sqlite.StoryFactRepository;
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

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 审计二轮 P1 修复验证：计划原子性、批准满足性、成书按快照版本、事实来源校验。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(StubLlmGatewayTestConfig.class)
@TestPropertySource(properties = {
        "persistence.embedding=stub",
        "persistence.sqlite.db-path=./data/test-audit2.db",
        "persistence.vector-store.index-path=./data/test-audit2-vecs.idx"
})
class Audit2FixTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;
    @Autowired NovelRepository repository;
    @Autowired StoryFactRepository storyFactRepository;

    private static final String BODY = "Chapter body for audit2 verification testing. ".repeat(20);

    private String createProject(String title) throws Exception {
        CreateProjectRequest req = new CreateProjectRequest(title, "c", "tag", "SOLO", null);
        String json = mvc.perform(post("/api/projects")
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(req)))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return om.readTree(json).get("id").asText();
    }

    private Chapter seedChapter(String pid, int index) {
        Chapter ch = new Chapter(pid, index, "Ch" + index);
        ch.setContent(BODY);
        ch.setStatus(Chapter.Status.NEEDS_MANUAL_REVIEW);
        repository.saveChapter(ch);
        return ch;
    }

    /** P1-1: 仅修改梗概内容（不改章号/标题）也必须使旧预览失效。 */
    @Test
    void 仅修改梗概使旧计划预览失效() throws Exception {
        String pid = createProject("plan-synopsis");
        // 建大纲
        Outline outline = new Outline(pid, "总纲");
        Volume v = new Volume(pid, 1, "卷一", "摘要");
        for (int i = 1; i <= 10; i++) {
            v.addSynopsis(new ChapterSynopsis(pid, i, "第" + i + "章", "原始梗概" + i));
        }
        outline.addVolume(v);
        repository.saveOutline(outline);

        // 预览 8 章
        MvcResult prev = mvc.perform(post("/api/projects/{id}/plan/target-chapters", pid)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(Map.of("targetChapters", 8, "confirm", false))))
                .andExpect(status().isOk()).andReturn();
        String previewId = om.readTree(prev.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("previewId").asText();
        String sourcePlanHash = om.readTree(prev.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("sourcePlanHash").asText();

        // 仅修改第 3 章梗概内容（不改章号/标题）
        Outline current = repository.findOutlineByProject(pid).orElseThrow();
        current.getVolumes().get(0).getSynopses().stream()
                .filter(s -> s.getChapterIndex() == 3)
                .findFirst()
                .ifPresent(s -> s.setSynopsis("修改后的梗概内容"));
        repository.saveOutline(current);

        // 用旧凭证确认 → 必须被拒绝（来源已变化）
        mvc.perform(post("/api/projects/{id}/plan/target-chapters", pid)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(Map.of(
                        "targetChapters", 8, "confirm", true,
                        "previewId", previewId, "sourcePlanHash", sourcePlanHash,
                        "decisions", java.util.List.of()))))
                .andExpect(status().isConflict());
    }

    /** P1-2: 有未落实 HARD 意见时不能批准章节。 */
    @Test
    void 未落实HARD意见阻塞批准() throws Exception {
        String pid = createProject("gate-hard");
        seedChapter(pid, 1);

        // 登记 HARD 意见（全书范围）
        Map<String, Object> dirBody = new HashMap<>();
        dirBody.put("kind", "HARD");
        dirBody.put("content", "每章必须有雨夜对话");
        dirBody.put("fromChapter", 1);
        mvc.perform(post("/api/projects/{id}/directives", pid)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(dirBody)))
                .andExpect(status().isOk());

        // 读取审阅上下文（此时意见已在 reviewSetHash 中）
        MvcResult ctx = mvc.perform(get("/api/projects/{id}/chapters/1/review-context", pid))
                .andExpect(status().isOk()).andReturn();
        var context = om.readTree(ctx.getResponse().getContentAsString(StandardCharsets.UTF_8));

        // 尝试批准 → 必须被拒绝（HARD 未落实）
        mvc.perform(post("/api/projects/{id}/chapters/1/review-decisions", pid)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(Map.of(
                        "identity", Map.of(
                                "projectId", pid, "chapterIndex", 1,
                                "version", context.path("identity").path("version").asInt(),
                                "contentHash", context.path("identity").path("contentHash").asText()),
                        "reviewSetHash", context.path("reviewSetHash").asText(),
                        "idempotencyKey", "gate-hard-key"))))
                .andExpect(status().isConflict())
                .andExpect(result -> {
                    String body = result.getResponse()
                            .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
                    assertTrue(body.contains("未落实") || body.contains("要求"),
                            "错误消息应提及未落实/要求，实际: " + body);
                });
    }

    /** P1-3: 成书快照按快照内版本验证批准，不借用新稿的批准。 */
    @Test
    void 成书快照不借用新稿批准() throws Exception {
        String pid = createProject("edition-ver");
        seedChapter(pid, 1);
        seedChapter(pid, 2);

        // 创建快照（此时章节未批准 → approvalId=null）
        MvcResult created = mvc.perform(post("/api/projects/{id}/book-editions", pid))
                .andExpect(status().isOk()).andReturn();
        var edition = om.readTree(created.getResponse().getContentAsString(StandardCharsets.UTF_8));
        String editionId = edition.path("editionId").asText();
        String manifestHash = edition.path("manifestHash").asText();

        // 编辑第 1 章产生 v3 并批准 v3
        Chapter ch1 = repository.findChapterByIndex(pid, 1).orElseThrow();
        Chapter revised = new Chapter(pid, 1, "Ch1");
        try {
            var fId = Chapter.class.getDeclaredField("id");
            fId.setAccessible(true); fId.set(revised, ch1.getId());
            var fC = Chapter.class.getDeclaredField("createdAt");
            fC.setAccessible(true); fC.set(revised, ch1.getCreatedAt());
            var fV = Chapter.class.getDeclaredField("version");
            fV.setAccessible(true); fV.set(revised, ch1.getVersion() + 1);
        } catch (Exception e) { throw new IllegalStateException(e); }
        revised.setContent(BODY + "New version content.");
        revised.setStatus(Chapter.Status.NEEDS_MANUAL_REVIEW);
        repository.saveChapterRevision(
                new ChapterVersion(java.util.UUID.randomUUID().toString(), pid, 1,
                        ch1.getVersion(), ch1.getTitle(), ch1.getContent(), "DIRECT_EDIT",
                        java.time.Instant.now()),
                revised, ch1.getVersion());

        // 批准 v3
        MvcResult ctx = mvc.perform(get("/api/projects/{id}/chapters/1/review-context", pid))
                .andExpect(status().isOk()).andReturn();
        var context = om.readTree(ctx.getResponse().getContentAsString(StandardCharsets.UTF_8));
        mvc.perform(post("/api/projects/{id}/chapters/1/review-decisions", pid)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(Map.of(
                        "identity", Map.of("projectId", pid, "chapterIndex", 1,
                                "version", context.path("identity").path("version").asInt(),
                                "contentHash", context.path("identity").path("contentHash").asText()),
                        "reviewSetHash", context.path("reviewSetHash").asText(),
                        "idempotencyKey", "ed-v3-key"))))
                .andExpect(status().isOk());

        // 解决所有屏障（确保不是屏障阻塞而是版本校验阻塞）
        for (int i = 1; i <= 2; i++) {
            var ch = repository.findChapterByIndex(pid, i).orElseThrow();
            if (ch.getStatus() == Chapter.Status.NEEDS_MANUAL_REVIEW) {
                var ctxI = mvc.perform(get("/api/projects/{id}/chapters/{n}/review-context", pid, i))
                        .andExpect(status().isOk()).andReturn();
                var contextI = om.readTree(ctxI.getResponse()
                        .getContentAsString(StandardCharsets.UTF_8));
                // 只有在上下文返回 barrier 为空时才能批准
                // 先跳过有 HARD 未落实的章（此测试不登记 HARD）
                try {
                    mvc.perform(post("/api/projects/{id}/chapters/{n}/review-decisions", pid, i)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(om.writeValueAsString(Map.of(
                                    "identity", Map.of("projectId", pid, "chapterIndex", i,
                                            "version", contextI.path("identity").path("version").asInt(),
                                            "contentHash", contextI.path("identity").path("contentHash").asText()),
                                    "reviewSetHash", contextI.path("reviewSetHash").asText(),
                                    "idempotencyKey", "ed-bar-" + i))))
                            .andExpect(status().isOk());
                } catch (Exception e) {
                    // 第 2 章如果还在 NEEDS_MANUAL_REVIEW 会有屏障——先解决
                }
            }
        }

        // 新门禁报告也必须识别快照稿号未批准，不能借用新稿批准。
        mvc.perform(post("/api/projects/{id}/book-editions/{eid}/validate", pid, editionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("BLOCKED"))
                .andExpect(result -> assertTrue(result.getResponse().getContentAsString()
                        .contains("AUTHOR_APPROVAL")));
        mvc.perform(post("/api/projects/{id}/book-editions/{eid}/approve", pid, editionId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(Map.of("manifestHash", manifestHash))))
                .andExpect(status().isConflict());

    }

    /** P1-4: 事实证据验证章存在、身份匹配、项目归属、引文核验。 */
    @Test
    void 事实证据拒绝伪造来源() throws Exception {
        String pid = createProject("fact-verify");
        StoryFact fact = new StoryFact(pid, StoryFact.FactType.WORLD_RULE, "rule", "assertion", 1);
        storyFactRepository.save(fact);
        seedChapter(pid, 1);
        Chapter ch = repository.findChapterByIndex(pid, 1).orElseThrow();
        String realHash = ChapterApproval.contentHash(ch.getTitle(), ch.getContent());

        // 不存在的章 → 400
        mvc.perform(post("/api/projects/{id}/facts/{fid}/evidence", pid, fact.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(Map.of(
                        "chapterIndex", 999, "chapterVersion", ch.getVersion(),
                        "contentHash", realHash))))
                .andExpect(status().isBadRequest());

        // 错误版本 → 400
        mvc.perform(post("/api/projects/{id}/facts/{fid}/evidence", pid, fact.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(Map.of(
                        "chapterIndex", 1, "chapterVersion", 77,
                        "contentHash", realHash))))
                .andExpect(status().isBadRequest());

        // 错误 hash → 400
        mvc.perform(post("/api/projects/{id}/facts/{fid}/evidence", pid, fact.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(Map.of(
                        "chapterIndex", 1, "chapterVersion", ch.getVersion(),
                        "contentHash", "fake-hash-000"))))
                .andExpect(status().isBadRequest());

        // 伪造引文 → 400
        mvc.perform(post("/api/projects/{id}/facts/{fid}/evidence", pid, fact.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(Map.of(
                        "chapterIndex", 1, "chapterVersion", ch.getVersion(),
                        "contentHash", realHash,
                        "quote", "这句话不在正文中"))))
                .andExpect(status().isBadRequest());

        // 跨项目事实 → 400
        String pid2 = createProject("fact-other");
        mvc.perform(post("/api/projects/{id}/facts/{fid}/evidence", pid2, fact.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(Map.of(
                        "chapterIndex", 1, "chapterVersion", ch.getVersion(),
                        "contentHash", realHash))))
                .andExpect(status().isBadRequest());

        // 正确身份 + 真实引文 → 200
        mvc.perform(post("/api/projects/{id}/facts/{fid}/evidence", pid, fact.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(Map.of(
                        "chapterIndex", 1, "chapterVersion", ch.getVersion(),
                        "contentHash", realHash,
                        "quote", BODY.substring(0, 20)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quoteKnown").value(true));
    }
}
