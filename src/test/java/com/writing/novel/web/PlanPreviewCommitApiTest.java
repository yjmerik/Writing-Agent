package com.writing.novel.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.writing.novel.domain.Chapter;
import com.writing.novel.spi.NovelRepository;
import org.springframework.beans.factory.annotation.Autowired;
import com.writing.novel.web.dto.CreateProjectRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** p38-03：计划预览凭证提交 API。 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(StubLlmGatewayTestConfig.class)
@TestPropertySource(properties = {
        "persistence.embedding=stub",
        "persistence.sqlite.db-path=./data/test-plan-preview-commit.db",
        "persistence.vector-store.index-path=./data/test-plan-preview-commit-vecs.idx"
})
class PlanPreviewCommitApiTest {

    @Autowired MockMvc mvc;
    @Autowired NovelRepository repository;
    @Autowired ObjectMapper om;

    @Test
    void 预览返回preview和appliedFalse() throws Exception {
        CreateProjectRequest req = new CreateProjectRequest("ppc", "c", "tag", "SOLO", null);
        String json = mvc.perform(post("/api/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(req)))
                .andReturn().getResponse().getContentAsString();
        String pid = om.readTree(json).get("id").asText();
        // Add minimal outline
        var outline = new com.writing.novel.domain.Outline(pid, "总纲");
        var volume = new com.writing.novel.domain.Volume(pid, 1, "第一卷", "摘要");
        volume.addSynopsis(new com.writing.novel.domain.ChapterSynopsis(pid, 1, "第1章", "梗概"));
        outline.addVolume(volume);
        repository.saveOutline(outline);

        mvc.perform(post("/api/projects/{id}/plan/target-chapters", pid)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(java.util.Map.of(
                                "targetChapters", 20, "confirm", "false"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").value(false))
                .andExpect(jsonPath("$.targetChapters").isNumber());
    }
}
