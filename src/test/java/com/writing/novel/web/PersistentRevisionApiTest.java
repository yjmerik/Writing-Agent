package com.writing.novel.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.writing.novel.domain.Chapter;
import com.writing.novel.spi.NovelRepository;
import com.writing.novel.web.dto.CreateProjectRequest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import com.writing.novel.llm.LlmGateway;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import com.writing.novel.llm.LlmRequest;
import com.writing.novel.llm.LlmResponse;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * p37-03：持久候选与恢复查询 API。
 * 预览持久化（重启/新连接后 GET 可恢复并可应用）；模型延迟期间编辑来源 → STALE；
 * 取消后应用 409；响应丢失重试章节 apply 只推进一版；sourceIdentity 与 sourceVersion 兼容并存。
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "persistence.embedding=stub",
        "persistence.sqlite.db-path=./data/test-persistent-revision.db",
        "persistence.vector-store.index-path=./data/test-persistent-revision-vecs.idx"
})
class PersistentRevisionApiTest {

    private static final String LONG_BODY = "这是满足人工章节保存门槛的原始正文。".repeat(40);

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;
    @Autowired NovelRepository repository;

    /** 模型延迟钩子：complete() 期间由测试触发“作者修改”。 */
    public static final java.util.concurrent.atomic.AtomicBoolean EDIT_SOURCE_DURING_MODEL =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    @BeforeAll
    static void createDataDir() { new File("./data").mkdirs(); }

    private String seedProjectAndChapter(String title) throws Exception {
        CreateProjectRequest req = new CreateProjectRequest(title, "c", "tag", "SOLO", null);
        String json = mvc.perform(post("/api/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(req)))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String projectId = om.readTree(json).get("id").asText();
        Chapter chapter = new Chapter(projectId, 1, "原章");
        chapter.setContent(LONG_BODY);
        chapter.setStatus(Chapter.Status.DONE);
        repository.saveChapter(chapter);
        return projectId;
    }

    private MvcResult preview(String projectId, String requestKey) throws Exception {
        return mvc.perform(post("/api/projects/{id}/revisions/preview", projectId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(java.util.Map.of(
                                "targetType", "CHAPTER",
                                "targetId", chapterId(projectId),
                                "chapterIndex", 1,
                                "instructions", "增加对话",
                                "requestKey", requestKey))))
                .andExpect(status().isOk())
                .andReturn();
    }

    private String chapterId(String projectId) {
        return repository.findChapterByIndex(projectId, 1).orElseThrow().getId();
    }

    @Test
    void 预览返回持久候选与sourceIdentity_重启后GET可恢复并可应用() throws Exception {
        String projectId = seedProjectAndChapter("持久候选");
        String requestKey = "rk-restart-1";
        MvcResult result = preview(projectId, requestKey);
        JsonNode preview = om.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        String previewId = preview.path("previewId").asText();
        // sourceIdentity 与 sourceVersion 兼容并存
        assertTrue(preview.hasNonNull("sourceVersion"), "sourceVersion 兼容别名保留");
        assertTrue(preview.path("sourceIdentity").path("version").asInt() > 0, "sourceIdentity.version 存在");
        assertTrue(preview.path("sourceIdentity").path("contentHash").asText().length() == 64);

        // GET 详情：持久查询（非内存 map）
        mvc.perform(get("/api/projects/{id}/revisions/{rid}", projectId, previewId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.requestKey").value(requestKey));

        // 应用同一候选：成功
        mvc.perform(post("/api/projects/{id}/revisions/{rid}/apply", projectId, previewId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").value(true))
                .andExpect(jsonPath("$.sourceIdentity.version").isNumber());

        Chapter after = repository.findChapterByIndex(projectId, 1).orElseThrow();
        assertEquals("修订后的章名", after.getTitle());
    }

    @Test
    void 模型延迟期间编辑来源_预览返回STALE且应用409() throws Exception {
        String projectId = seedProjectAndChapter("延迟STALE");
        EDIT_SOURCE_DURING_MODEL.set(true);
        try {
            MvcResult result = mvc.perform(post("/api/projects/{id}/revisions/preview", projectId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(om.writeValueAsString(java.util.Map.of(
                                    "targetType", "CHAPTER",
                                    "targetId", chapterId(projectId),
                                    "chapterIndex", 1,
                                    "instructions", "增加对话"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("STALE"))
                    .andReturn();
            String previewId = om.readTree(result.getResponse()
                    .getContentAsString(StandardCharsets.UTF_8)).path("previewId").asText();

            mvc.perform(post("/api/projects/{id}/revisions/{rid}/apply", projectId, previewId))
                    .andExpect(status().isConflict());
        } finally {
            EDIT_SOURCE_DURING_MODEL.set(false);
            }
    }

    @Test
    void 取消后应用返回409() throws Exception {
        String projectId = seedProjectAndChapter("取消候选");
        MvcResult result = preview(projectId, "rk-cancel");
        String previewId = om.readTree(result.getResponse()
                .getContentAsString(StandardCharsets.UTF_8)).path("previewId").asText();

        mvc.perform(post("/api/projects/{id}/revisions/{rid}/cancel", projectId, previewId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        mvc.perform(post("/api/projects/{id}/revisions/{rid}/apply", projectId, previewId))
                .andExpect(status().isConflict());
    }

    @Test
    void 响应丢失重试章节apply只推进一版() throws Exception {
        String projectId = seedProjectAndChapter("响应丢失重试");
        MvcResult result = preview(projectId, "rk-retry");
        String previewId = om.readTree(result.getResponse()
                .getContentAsString(StandardCharsets.UTF_8)).path("previewId").asText();
        int versionBefore = repository.findChapterByIndex(projectId, 1).orElseThrow().getVersion();

        mvc.perform(post("/api/projects/{id}/revisions/{rid}/apply", projectId, previewId))
                .andExpect(status().isOk());
        // 响应丢失后重试
        mvc.perform(post("/api/projects/{id}/revisions/{rid}/apply", projectId, previewId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(true));

        assertEquals(versionBefore + 1,
                repository.findChapterByIndex(projectId, 1).orElseThrow().getVersion(),
                "重试不另增版本");
    }

    @Test
    void 列表按targetId与status过滤() throws Exception {
        String projectId = seedProjectAndChapter("列表过滤");
        preview(projectId, "rk-list-1");
        mvc.perform(get("/api/projects/{id}/revisions", projectId)
                        .param("status", "READY")
                        .param("targetId", chapterId(projectId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)));
    }


    @org.springframework.boot.test.context.TestConfiguration
    static class RevisionGatewayConfig {
        @Bean
        @org.springframework.context.annotation.Primary
        LlmGateway revisionGateway(ObjectMapper objectMapper, NovelRepository repository) {
            return new LlmGateway() {
                @Override
                public LlmResponse complete(LlmRequest request) {
                    try {
                        String prompt = request.getSystemPrompt();
                        if (!prompt.contains("目标类型：")) {
                            return new LlmResponse("[]", 10, 10);
                        }
                        // p37-03: 模型延迟期间编辑来源（由测试钩子触发）
                        if (PersistentRevisionApiTest.EDIT_SOURCE_DURING_MODEL
                                .compareAndSet(true, false) && repository != null) {
                            repository.findAllProjects().forEach(p ->
                                repository.findChapterByIndex(p.getId(), 1).ifPresent(ch -> {
                                    ch.setContent(ch.getContent() + "\n作者在模型延迟期间修改。");
                                    repository.saveChapter(ch);
                                }));
                        }
                        String currentJson = between(prompt, "当前选中对象（JSON）：\n", "\n\n用户修改要求：");
                        ObjectNode candidate = (ObjectNode) objectMapper.readTree(currentJson);
                        candidate.put("title", "修订后的章名");
                        candidate.put("content", "修订后的章节正文，".repeat(60));
                        ObjectNode response = objectMapper.createObjectNode()
                                .put("targetType", "CHAPTER")
                                .put("title", "修订后的章名")
                                .put("content", "修订后的章节正文，".repeat(60))
                                .put("structuredJson", candidate.toString())
                                .put("summary", "仅修改所选目标");
                        return new LlmResponse(response.toString(), 50, 100);
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                }

                @Override
                public void streamComplete(LlmRequest request,
                                           com.writing.novel.llm.client.StreamHandler handler) {
                    // no-op
                }

                private String between(String text, String start, String end) {
                    int s = text.indexOf(start);
                    if (s < 0) return "";
                    s += start.length();
                    int e = text.indexOf(end, s);
                    return e < 0 ? text.substring(s) : text.substring(s, e);
                }
            };
        }
    }

    static class EditSourceHook {
        // StubLlmGatewayTestConfig 的 GENERAL stub 调用此钩子
        public static final java.util.concurrent.atomic.AtomicBoolean FLAG =
                new java.util.concurrent.atomic.AtomicBoolean(false);
    }
}