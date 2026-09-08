package com.writing.novel.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.writing.novel.domain.Chapter;
import com.writing.novel.domain.Project;
import com.writing.novel.spi.NovelRepository;
import com.writing.novel.web.dto.CreateProjectRequest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.File;

import static org.hamcrest.Matchers.matchesRegex;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * p32-04（体验报告 #1/P1）：章节记录所用计划出处。
 * 新写章节持久化 plan_ref（卷号+梗概内容指纹），GET 单章 API 透出；
 * 作者修订计划后指纹变化，可据此核对每章生成时点采用的计划版本；旧行 NULL 兼容。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(StubLlmGatewayTestConfig.class)
@TestPropertySource(properties = {
        "persistence.embedding=stub",
        "persistence.sqlite.db-path=./data/test-plan-provenance.db",
        "persistence.vector-store.index-path=./data/test-plan-provenance-vecs.idx"
})
class ChapterPlanProvenanceTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;
    @Autowired NovelRepository repository;

    @BeforeAll
    static void createDataDir() {
        new File("./data").mkdirs();
    }

    @Test
    void 新写章节持久化计划出处并经单章API透出() throws Exception {
        String id = createAndConceive();
        mvc.perform(post("/api/projects/" + id + "/run"));

        mvc.perform(post("/api/projects/" + id + "/write-chapter"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.planRef", matchesRegex("volume=\\d+;synopsis=[0-9a-f]{12}")));

        mvc.perform(get("/api/projects/" + id + "/chapters/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.planRef", matchesRegex("volume=\\d+;synopsis=[0-9a-f]{12}")));

        Chapter written = repository.findChapterByIndex(id, 1).orElseThrow();
        org.junit.jupiter.api.Assertions.assertTrue(
                written.getPlanRef().startsWith("volume="), "plan_ref 必须落库可回读");
    }

    @Test
    void 旧章节无plan_ref时读取兼容() throws Exception {
        Project project = new Project("旧数据", "概念", "现实");
        repository.saveProject(project);
        Chapter legacy = new Chapter(project.getId(), 1, "旧章");
        legacy.setContent("旧正文。".repeat(200));
        repository.saveChapter(legacy);

        Chapter readBack = repository.findChapterByIndex(project.getId(), 1).orElseThrow();
        org.junit.jupiter.api.Assertions.assertNull(readBack.getPlanRef(), "旧行 plan_ref 应为 NULL");

        String body = mvc.perform(get("/api/projects/" + project.getId() + "/chapters/1"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        var node = om.readTree(body).path("planRef");
        org.junit.jupiter.api.Assertions.assertTrue(
                node.isMissingNode() || node.isNull(), "API 对旧行 plan_ref 缺失或 null 均可: " + body);
    }

    private String createAndConceive() throws Exception {
        CreateProjectRequest req = new CreateProjectRequest("出处", "c", "tag", "SOLO", null);
        String json = mvc.perform(post("/api/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(req)))
                .andReturn().getResponse().getContentAsString();
        String id = om.readTree(json).get("id").asText();
        RunTaskSupport.runAndAwait(mvc, om, id);
        return id;
    }
}
