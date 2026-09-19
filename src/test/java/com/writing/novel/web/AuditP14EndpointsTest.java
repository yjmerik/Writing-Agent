package com.writing.novel.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.writing.novel.domain.Chapter;
import com.writing.novel.domain.ChapterApproval;
import com.writing.novel.domain.ReviewBarrier;
import com.writing.novel.domain.StoryFact;
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
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 审计 P1-4 修复验证：/book-editions、/facts/evidence、/chapters/{n}/recheck
 * 全部可用且语义正确（原实测 500）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(StubLlmGatewayTestConfig.class)
@TestPropertySource(properties = {
        "persistence.embedding=stub",
        "persistence.sqlite.db-path=./data/test-audit-p14.db",
        "persistence.vector-store.index-path=./data/test-audit-p14-vecs.idx"
})
class AuditP14EndpointsTest {

    private static final String BODY = "Chapter body text for audit endpoint verification. ".repeat(20);

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;
    @Autowired NovelRepository repository;
    @Autowired StoryFactRepository storyFactRepository;
    @Autowired com.writing.novel.persistence.sqlite.CheckResultRepository checks;

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
        ch.setStatus(Chapter.Status.DONE);
        repository.saveChapter(ch);
        return ch;
    }

    // --- book-editions ---

    @Test
    void bookEditions创建列表详情批准导出全链路() throws Exception {
        String pid = createProject("audit-book");
        Chapter ch1 = seedChapter(pid, 1);
        seedChapter(pid, 2);

        // 创建快照
        MvcResult created = mvc.perform(post("/api/projects/{id}/book-editions", pid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.editionId").isNotEmpty())
                .andExpect(jsonPath("$.manifestHash").isNotEmpty())
                .andExpect(jsonPath("$.state").value("DRAFT"))
                .andExpect(jsonPath("$.chapterCount").value(2))
                .andReturn();
        JsonNode edition = om.readTree(created.getResponse().getContentAsString(StandardCharsets.UTF_8));
        String editionId = edition.path("editionId").asText();
        String manifestHash = edition.path("manifestHash").asText();

        // 列表
        mvc.perform(get("/api/projects/{id}/book-editions", pid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].editionId").value(editionId));

        // 详情
        mvc.perform(get("/api/projects/{id}/book-editions/{eid}", pid, editionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chapters.length()").value(2))
                .andExpect(jsonPath("$.chapters[0].chapterIndex").value(1));

        // 未批准时导出仍可提供（DRAFT 明示），批准后固定字节
        MvcResult export1 = mvc.perform(get("/api/projects/{id}/book-editions/{eid}/export", pid, editionId))
                .andExpect(status().isOk())
                .andReturn();
        String md1 = export1.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(md1.contains("DRAFT"), "导出标注版本状态");
        assertTrue(md1.contains("Ch1"), "含章标题");

        // 缺 manifestHash → 400；错误 hash → 409
        mvc.perform(post("/api/projects/{id}/book-editions/{eid}/approve", pid, editionId)
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/projects/{id}/book-editions/{eid}/approve", pid, editionId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(Map.of("manifestHash", "wrong"))))
                .andExpect(status().isConflict());

        // 章节未批准 → 批准被阻塞（覆盖资格重读）
        mvc.perform(post("/api/projects/{id}/book-editions/{eid}/approve", pid, editionId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(Map.of("manifestHash", manifestHash))))
                .andExpect(status().isConflict());

        // 批准两章当前稿后再批成书 → APPROVED
        for (int i = 1; i <= 2; i++) {
            approveChapterViaApi(pid, i);
            checks.save(pid, i, "continuity", com.writing.novel.agent.CheckResultEnvelope.Status.VALID_EMPTY, "无矛盾");
        }
        mvc.perform(post("/api/projects/{id}/book-editions/{eid}/validate", pid, editionId))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PASS"));
        mvc.perform(post("/api/projects/{id}/book-editions/{eid}/approve", pid, editionId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(Map.of("manifestHash", manifestHash))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("APPROVED"));

        // 修改正文后导出字节不变（固定版本）
        Chapter mutated = repository.findChapterByIndex(pid, 1).orElseThrow();
        repository.saveChapterRevision(
                new com.writing.novel.domain.ChapterVersion(java.util.UUID.randomUUID().toString(),
                        pid, 1, mutated.getVersion(), mutated.getTitle(), mutated.getContent(),
                        "DIRECT_EDIT", java.time.Instant.now()),
                mutatedWith(pid, mutated, "MUTATED AFTER APPROVAL ".repeat(30)),
                mutated.getVersion());
        MvcResult export2 = mvc.perform(get("/api/projects/{id}/book-editions/{eid}/export", pid, editionId))
                .andExpect(status().isOk())
                .andReturn();
        String md2 = export2.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertEquals(md1.replace("DRAFT", "APPROVED"), md2.replace("DRAFT", "APPROVED"),
                "正文修改不影响历史成书导出字节");
        assertTrue(!md2.contains("MUTATED"), "导出不得含修改后内容");
    }

    private Chapter mutatedWith(String pid, Chapter source, String content) {
        Chapter revised = new Chapter(pid, source.getChapterIndex(), source.getTitle());
        try {
            var fId = Chapter.class.getDeclaredField("id");
            fId.setAccessible(true);
            fId.set(revised, source.getId());
            var fCreated = Chapter.class.getDeclaredField("createdAt");
            fCreated.setAccessible(true);
            fCreated.set(revised, source.getCreatedAt());
            var fVersion = Chapter.class.getDeclaredField("version");
            fVersion.setAccessible(true);
            fVersion.set(revised, source.getVersion() + 1);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        revised.setContent(content);
        revised.setStatus(Chapter.Status.DONE);
        return revised;
    }

    private void approveChapterViaApi(String pid, int chapterIndex) throws Exception {
        MvcResult ctx = mvc.perform(get("/api/projects/{id}/chapters/{n}/review-context", pid, chapterIndex))
                .andExpect(status().isOk()).andReturn();
        JsonNode context = om.readTree(ctx.getResponse().getContentAsString(StandardCharsets.UTF_8));
        Map<String, Object> body = new HashMap<>();
        body.put("identity", Map.of(
                "projectId", pid,
                "chapterIndex", chapterIndex,
                "version", context.path("identity").path("version").asInt(),
                "contentHash", context.path("identity").path("contentHash").asText()));
        body.put("reviewSetHash", context.path("reviewSetHash").asText());
        body.put("idempotencyKey", java.util.UUID.randomUUID().toString());
        mvc.perform(post("/api/projects/{id}/chapters/{n}/review-decisions", pid, chapterIndex)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(body)))
                .andExpect(status().isOk());
    }

    // --- facts/evidence ---

    @Test
    void factsEvidence保存与查询() throws Exception {
        String pid = createProject("audit-facts");
        StoryFact fact = new StoryFact(pid, StoryFact.FactType.WORLD_RULE, "rule", "assertion", 1);
        storyFactRepository.save(fact);
        seedChapter(pid, 1);
        Chapter ch = repository.findChapterByIndex(pid, 1).orElseThrow();
        String hash = ChapterApproval.contentHash(ch.getTitle(), ch.getContent());

        // POST 证据（含知情者）
        Map<String, Object> body = new HashMap<>();
        body.put("chapterIndex", 1);
        body.put("chapterVersion", ch.getVersion());
        body.put("contentHash", hash);
        body.put("quote", "Chapter body text");
        body.put("validFromChapter", 1);
        body.put("observers", List.of(Map.of("characterId", "char-1", "learnedAtChapter", 1)));
        mvc.perform(post("/api/projects/{id}/facts/{fid}/evidence", pid, fact.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quoteKnown").value(true));

        // 缺身份字段 → 400
        mvc.perform(post("/api/projects/{id}/facts/{fid}/evidence", pid, fact.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(Map.of("quote", "x"))))
                .andExpect(status().isBadRequest());

        // GET 查询
        mvc.perform(get("/api/projects/{id}/facts/evidence", pid)
                .param("factId", fact.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
        // asOfChapter 时点过滤
        mvc.perform(get("/api/projects/{id}/facts/evidence", pid)
                .param("factId", fact.getId()).param("asOfChapter", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
        // 知情者过滤：知情角色命中，未知角色为空
        mvc.perform(get("/api/projects/{id}/facts/evidence", pid)
                .param("factId", fact.getId()).param("characterId", "char-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
        mvc.perform(get("/api/projects/{id}/facts/evidence", pid)
                .param("factId", fact.getId()).param("characterId", "char-unknown"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    // --- recheck ---

    @Test
    void recheck需要当前身份且旧身份409() throws Exception {
        String pid = createProject("audit-recheck");
        seedChapter(pid, 1);
        Chapter ch = repository.findChapterByIndex(pid, 1).orElseThrow();
        String hash = ChapterApproval.contentHash(ch.getTitle(), ch.getContent());

        // 缺 identity → 400
        mvc.perform(post("/api/projects/{id}/chapters/1/recheck", pid)
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());

        // 当前身份 → 200
        mvc.perform(post("/api/projects/{id}/chapters/1/recheck", pid)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(Map.of("identity", Map.of(
                        "projectId", pid, "chapterIndex", 1,
                        "version", ch.getVersion(), "contentHash", hash)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chapterVersion").value(ch.getVersion()));

        // 旧身份 → 409
        mvc.perform(post("/api/projects/{id}/chapters/1/recheck", pid)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(Map.of("identity", Map.of(
                        "projectId", pid, "chapterIndex", 1,
                        "version", ch.getVersion() + 5, "contentHash", hash)))))
                .andExpect(status().isConflict());
    }
}
