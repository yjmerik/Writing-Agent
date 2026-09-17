package com.writing.novel.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.writing.novel.llm.LlmGateway;
import com.writing.novel.llm.ScenarioLlmGateway;
import com.writing.novel.persistence.sqlite.ConnectionProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** p41-10: exercise actual preview, source edit and apply through HTTP and SQLite. */
@SpringBootTest(properties = {
        "persistence.embedding=stub",
        "persistence.sqlite.db-path=./data/test-stale-revision-api.db",
        "persistence.vector-store.index-path=./data/test-stale-revision-api.idx"
})
@AutoConfigureMockMvc
class StaleRevisionCandidateApiTest {
    private static final String BODY = "来客在雨夜寻找失落的铜钥匙。".repeat(50);
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;
    @Autowired ConnectionProvider connections;

    @TestConfiguration
    static class Config {
        @Bean @Primary LlmGateway scenarioGateway() { return new ScenarioLlmGateway(false); }
    }

    @ParameterizedTest
    @ValueSource(strings = {"CHAPTER", "PASSAGE"})
    void sourceEditedAfterPreviewReturns409WithoutChangingCurrentDraft(String target) throws Exception {
        String base = seed();
        String previewId = preview(base, target);
        JsonNode before = read(base + "/chapters/1");
        mvc.perform(put(base + "/chapters/1").contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("expectedVersion", before.path("version").asInt(),
                                "title", "作者的新章名", "content", BODY + "作者保留的新线索。"))))
                .andExpect(status().isOk());
        JsonNode current = draft(base);
        JsonNode history = read(base + "/chapters/1/versions");

        apply(base, previewId).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("候选来源已变化，请刷新后重新生成"));
        assertEquals(current, draft(base));
        assertEquals(history, read(base + "/chapters/1/versions"));
        assertEquals("STALE", read(base + "/revisions/" + previewId).path("status").asText());
        apply(base, previewId).andExpect(status().isConflict());
        assertEquals(current, draft(base));
    }

    @Test
    void validCandidateAppliesOnceAndRetryReturnsPersistedResult() throws Exception {
        String base = seed();
        JsonNode before = draft(base);
        String previewId = preview(base, "CHAPTER");
        apply(base, previewId).andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").value(true))
                .andExpect(jsonPath("$.replayed").value(false));
        JsonNode applied = draft(base);
        assertEquals(before.path("version").asInt() + 1, applied.path("version").asInt());
        assertNotEquals(before.path("title"), applied.path("title"));
        assertEquals(before.path("content"), applied.path("content"));
        JsonNode history = read(base + "/chapters/1/versions");
        apply(base, previewId).andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(true));
        assertEquals(applied, draft(base));
        assertEquals(history, read(base + "/chapters/1/versions"));
    }

    @Test
    void databaseFailureRemains500AndCandidateCanRetryAfterRecovery() throws Exception {
        String base = seed();
        String previewId = preview(base, "CHAPTER");
        JsonNode before = draft(base);
        JsonNode history = read(base + "/chapters/1/versions");
        String chapterId = read(base + "/chapters/1").path("id").asText();
        // Both SQL identifiers/values originate from generated UUIDs, never external input.
        String trigger = "fail_candidate_" + UUID.randomUUID().toString().replace("-", "");
        sql("CREATE TRIGGER " + trigger + " BEFORE UPDATE ON chapters WHEN OLD.id='" + chapterId
                + "' BEGIN SELECT RAISE(ABORT,'injected candidate storage failure'); END");
        try {
            apply(base, previewId).andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("injected candidate storage failure")));
            assertEquals(before, draft(base));
            assertEquals(history, read(base + "/chapters/1/versions"));
            assertEquals("READY", read(base + "/revisions/" + previewId).path("status").asText());
        } finally {
            sql("DROP TRIGGER " + trigger);
        }
        apply(base, previewId).andExpect(status().isOk()).andExpect(jsonPath("$.applied").value(true));
        assertEquals(before.path("version").asInt() + 1, draft(base).path("version").asInt());
    }

    private String seed() throws Exception {
        JsonNode project = json(mvc.perform(post("/api/projects").contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(Map.of("title", "候选冲突回归", "concept", "测试", "tags", "", "mode", "SOLO"))))
                .andExpect(status().isOk()));
        String base = "/api/projects/" + project.path("id").asText();
        mvc.perform(post(base + "/chapters").contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("title", "原章", "content", BODY))))
                .andExpect(status().isOk());
        return base;
    }

    private String preview(String base, String target) throws Exception {
        return json(mvc.perform(post(base + "/revisions/preview").contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("targetType", target,
                                "targetId", read(base + "/chapters/1").path("id").asText(),
                                "chapterIndex", 1, "passageIndex", 0, "instructions", "增加雨夜氛围",
                                "requestKey", UUID.randomUUID().toString()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("READY")))
                .path("previewId").asText();
    }

    private ResultActions apply(String base, String previewId) throws Exception {
        return mvc.perform(post(base + "/revisions/" + previewId + "/apply"));
    }

    private JsonNode draft(String base) throws Exception {
        JsonNode chapter = read(base + "/chapters/1");
        return om.createObjectNode().put("title", chapter.path("title").asText())
                .put("content", chapter.path("content").asText()).put("version", chapter.path("version").asInt());
    }

    private JsonNode read(String url) throws Exception {
        return json(mvc.perform(get(url)).andExpect(status().isOk()));
    }

    private JsonNode json(ResultActions response) throws Exception {
        return om.readTree(response.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private void sql(String statement) throws Exception {
        try (var conn = connections.getConnection(); var st = conn.createStatement()) { st.execute(statement); }
    }
}
