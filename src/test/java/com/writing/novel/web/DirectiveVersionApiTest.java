package com.writing.novel.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.writing.novel.domain.AuthorDirective;
import com.writing.novel.domain.Chapter;
import com.writing.novel.spi.NovelRepository;
import com.writing.novel.web.dto.CreateProjectRequest;
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

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.hasKey;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * p37-08：意见接口呈现范围、证据与显式撤销。
 * 原 confirm 不能凭空确认 OPEN；缺 version/hash 不补填 → 400；范围编辑 CAS；HARD 撤销需理由。
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "persistence.embedding=stub",
        "persistence.sqlite.db-path=./data/test-directive-version.db",
        "persistence.vector-store.index-path=./data/test-directive-version-vecs.idx"
})
class DirectiveVersionApiTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;
    @Autowired NovelRepository repository;

    private String pid;
    private String directiveId;
    private int chapterVersion;

    @BeforeEach
    void setUp() throws Exception {
        CreateProjectRequest req = new CreateProjectRequest("意见API", "c", "tag", "SOLO", null);
        String json = mvc.perform(post("/api/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(req)))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        pid = om.readTree(json).get("id").asText();

        Chapter chapter = new Chapter(pid, 4, "第四章");
        chapter.setContent("正文中包含雨夜对话场景，满足要求。".repeat(20));
        chapter.setStatus(Chapter.Status.DONE);
        repository.saveChapter(chapter);
        chapterVersion = chapter.getVersion();

        // 全书 HARD
        AuthorDirective hard = new AuthorDirective(pid, AuthorDirective.Kind.HARD, null, "每章一场雨夜对话");
        repo_save(hard);
        directiveId = hard.getId();
    }

    private void repo_save(AuthorDirective d) { repository.saveAuthorDirective(d); }

    @Test
    void GET返回enabled_revision_范围与evidence数组() throws Exception {
        mvc.perform(get("/api/projects/{id}/directives", pid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].enabled").value(true))
                .andExpect(jsonPath("$[0].revision").value(1))
                .andExpect(jsonPath("$[0].fromChapter").value(1))
                .andExpect(jsonPath("$[0].toChapter").doesNotExist())
                .andExpect(jsonPath("$[0].evidence").isArray())
                .andExpect(jsonPath("$[0].evidence").isEmpty());
    }

    @Test
    void 原confirm无证据时确认OPEN被拒绝() throws Exception {
        mvc.perform(post("/api/projects/{id}/directives/{did}/confirm", pid, directiveId))
                .andExpect(status().isConflict());
    }

    @Test
    void scope编辑CAS成功与冲突() throws Exception {
        // 正确 expectedRevision=1：成功且 revision 变 2
        mvc.perform(patch("/api/projects/{id}/directives/{did}", pid, directiveId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of(
                                "expectedRevision", 1,
                                "content", "每章至少两场对话",
                                "fromChapter", 3,
                                "toChapter", 8))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(2))
                .andExpect(jsonPath("$.fromChapter").value(3));
        // 过期 expectedRevision=1：409
        mvc.perform(patch("/api/projects/{id}/directives/{did}", pid, directiveId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of(
                                "expectedRevision", 1,
                                "content", "再次修改",
                                "fromChapter", 1, "toChapter", 5))))
                .andExpect(status().isConflict());
    }

    @Test
    void HARD撤销必须理由且留审计() throws Exception {
        // 无理由 → 400
        mvc.perform(post("/api/projects/{id}/directives/{did}/revoke", pid, directiveId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("expectedRevision", 1))))
                .andExpect(status().isBadRequest());
        // 空理由 → 400
        mvc.perform(post("/api/projects/{id}/directives/{did}/revoke", pid, directiveId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("expectedRevision", 1, "reason", " "))))
                .andExpect(status().isBadRequest());
        // 有理由 → 200 且 status=DISMISSED, enabled=false, revision=2
        mvc.perform(post("/api/projects/{id}/directives/{did}/revoke", pid, directiveId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("expectedRevision", 1, "reason", "作者改变方向"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISMISSED"))
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.revision").value(2));
    }

    @Test
    void evidence确认需要identity且APPLIED_STATUS() throws Exception {
        // 先保存一条 APPLIED_SYSTEM 证据
        Chapter chapter = repository.findChapterByIndex(pid, 4).orElseThrow();
        String contentHash = com.writing.novel.domain.ChapterApproval.contentHash(
                chapter.getTitle(), chapter.getContent());
        repository.saveDirectiveEvidence(pid, new AuthorDirective.Evidence(
                UUID.randomUUID().toString(), directiveId, 1, 4, chapterVersion, contentHash,
                null, "雨夜对话场景", "1", AuthorDirective.EvidenceStatus.APPLIED_SYSTEM, null));

        // 缺 identity → 400（发空 JSON 使请求到达 controller，由 IAE → 400）
        mvc.perform(post("/api/projects/{id}/directives/{did}/evidence/{eid}/confirm",
                        pid, directiveId, evidenceId0())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
        // 正确 identity → 200 CONFIRMED
        mvc.perform(post("/api/projects/{id}/directives/{did}/evidence/{eid}/confirm",
                        pid, directiveId, evidenceId0())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of(
                                "identity", Map.of("projectId", pid, "chapterIndex", 4,
                                        "version", chapterVersion, "contentHash", contentHash),
                                "directiveRevision", 1))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));
    }

    private String evidenceId0() throws Exception {
        MvcResult result = mvc.perform(get("/api/projects/{id}/directives", pid))
                .andExpect(status().isOk()).andReturn();
        return om.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .get(0).path("evidence").get(0).path("id").asText();
    }

    @Test
    void 跨项目确认被拒绝() throws Exception {
        Chapter chapter = repository.findChapterByIndex(pid, 4).orElseThrow();
        String contentHash = com.writing.novel.domain.ChapterApproval.contentHash(
                chapter.getTitle(), chapter.getContent());
        repository.saveDirectiveEvidence(pid, new AuthorDirective.Evidence(
                UUID.randomUUID().toString(), directiveId, 1, 4, chapterVersion, contentHash,
                null, "雨夜对话场景", "1", AuthorDirective.EvidenceStatus.APPLIED_SYSTEM, null));
        String eid = evidenceId0();
        CreateProjectRequest other = new CreateProjectRequest("其他项目", "c", "tag", "SOLO", null);
        String json = mvc.perform(post("/api/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(other)))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String otherPid = om.readTree(json).get("id").asText();
        mvc.perform(post("/api/projects/{id}/directives/{did}/evidence/{eid}/confirm",
                        otherPid, directiveId, eid)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of(
                                "identity", Map.of("projectId", pid, "chapterIndex", 4,
                                        "version", chapterVersion, "contentHash", contentHash),
                                "directiveRevision", 1))))
                .andExpect(status().isBadRequest());
    }

    private String json(Object v) throws Exception { return om.writeValueAsString(v); }
}
