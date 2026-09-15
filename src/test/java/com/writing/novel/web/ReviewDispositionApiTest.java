package com.writing.novel.web;

import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.util.HashMap;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(StubLlmGatewayTestConfig.class)
@TestPropertySource(properties = {
        "persistence.embedding=stub",
        "persistence.sqlite.db-path=./data/test-review-disp.db",
        "persistence.vector-store.index-path=./data/test-review-disp-vecs.idx"
})
class ReviewDispositionApiTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;

    @Test
    void create_and_list() throws Exception {
        CreateProjectRequest req = new CreateProjectRequest("dp", "c", "tag", "SOLO", null);
        String json = mvc.perform(post("/api/projects")
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(req)))
                .andReturn().getResponse().getContentAsString();
        String pid = om.readTree(json).get("id").asText();

        Map<String, Object> body = new HashMap<>();
        body.put("issueId", "issue-1");
        body.put("action", "FALSE_POSITIVE");
        body.put("reason", "false positive reason");
        body.put("identity", Map.of("projectId", pid, "chapterIndex", 1,
                "version", 1, "contentHash", "h1"));

        mvc.perform(post("/api/projects/{id}/dispositions", pid)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.action").value("FALSE_POSITIVE"));

        mvc.perform(get("/api/projects/{id}/dispositions", pid)
                .param("chapterIndex", "1")
                .param("chapterVersion", "1")
                .param("contentHash", "h1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void missingReasonReturns400() throws Exception {
        CreateProjectRequest req = new CreateProjectRequest("dp2", "c", "tag", "SOLO", null);
        String json = mvc.perform(post("/api/projects")
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(req)))
                .andReturn().getResponse().getContentAsString();
        String pid = om.readTree(json).get("id").asText();

        Map<String, Object> body = new HashMap<>();
        body.put("issueId", "issue-1");
        body.put("action", "FALSE_POSITIVE");
        body.put("identity", Map.of("projectId", pid, "chapterIndex", 1,
                "version", 1, "contentHash", "h1"));

        mvc.perform(post("/api/projects/{id}/dispositions", pid)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(body)))
                .andExpect(status().isBadRequest());
    }
}
