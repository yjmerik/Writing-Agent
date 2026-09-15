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

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** p38-11：问题裁决 API——保存/查询/缺理由400。 */
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
        CreateProjectRequest req = new CreateProjectRequest("裁决API", "c", "tag", "SOLO", null);
        String json = mvc.perform(post("/api/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(req)))
                .andReturn().getResponse().getContentAsString();
        String pid = om.readTree(json).get("id").asText();

        mvc.perform(post("/api/projects/{id}/dispositions", pid)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of(
                                "issueId", "issue-1",
                                "action", "FALSE_POSITIVE",
                                "reason", "误报：实际存在",
                                "identity", Map.of("projectId", pid, "chapterIndex", 1,
                                        "version", 1, "contentHash", "h1"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.action").value("FALSE_POSITIVE"));

        mvc.perform(get("/api/projects/{id}/dispositions", pid)
                        .param("chapterIndex", "1").param("chapterVersion", "1").param("contentHash", "h1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void 缺理由返回400() throws Exception {
        CreateProjectRequest req = new CreateProjectRequest("缺理由", "c", "tag", "SOLO", null);
        String json = mvc.perform(post("/api/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(req)))
                .andReturn().getResponse().getContentAsString();
        String pid = om.readTree(json).get("id").asText();

        mvc.perform(post("/api/projects/{id}/dispositions", pid)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of(
                                "issueId", "issue-1", "action", "FALSE_POSITIVE",
                                "identity", Map.of("projectId", pid, "chapterIndex", 1,
                                        "version", 1, "contentHash", "h1")))))
                .andExpect(status().isBadRequest());
    }
}
