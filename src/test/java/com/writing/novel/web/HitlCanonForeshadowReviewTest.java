package com.writing.novel.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.writing.novel.domain.Chapter;
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
import java.nio.charset.StandardCharsets;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(StubLlmGatewayTestConfig.class)
@TestPropertySource(properties = {
        "persistence.embedding=stub",
        "persistence.sqlite.db-path=./data/test-hitl-cfr.db",
        "persistence.vector-store.index-path=./data/test-hitl-cfr-vecs.idx"
})
class HitlCanonForeshadowReviewTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;
    @Autowired NovelRepository repository;

    @BeforeAll
    static void createDataDir() { new File("./data").mkdirs(); }

    @Test
    void putFact_thenRetire_dropsFromActive() throws Exception {
        String id = createProject();
        String created = mvc.perform(post("/api/projects/" + id + "/facts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"factType\":\"WORLD_RULE\",\"subject\":\"铁律\",\"assertion\":\"债册不得记活人阳寿\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String factId = om.readTree(created).get("id").asText();

        mvc.perform(put("/api/projects/" + id + "/facts/" + factId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assertion\":\"债册只记阴司已勾之名\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assertion").value(org.hamcrest.Matchers.containsString("阴司已勾")));

        mvc.perform(get("/api/projects/" + id + "/facts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].assertion", hasItem(org.hamcrest.Matchers.containsString("阴司已勾"))))
                .andExpect(jsonPath("$[*].id", not(hasItem(factId))));

        String newerId = om.readTree(mvc.perform(get("/api/projects/" + id + "/facts"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8)).get(0).get("id").asText();

        mvc.perform(post("/api/projects/" + id + "/facts/" + newerId + "/retire"))
                .andExpect(status().isOk());

        mvc.perform(get("/api/projects/" + id + "/facts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", not(hasItem(newerId))));
    }

    @Test
    void postForeshadowing_thenResolve() throws Exception {
        String id = createProject();
        String created = mvc.perform(post("/api/projects/" + id + "/foreshadowings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"账册缺页\",\"mustResolve\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resolved").value(false))
                .andExpect(jsonPath("$.mustResolve").value(true))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String fid = om.readTree(created).get("id").asText();

        mvc.perform(get("/api/projects/" + id + "/foreshadowings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id=='" + fid + "')].description").value(org.hamcrest.Matchers.hasItem("账册缺页")));

        mvc.perform(post("/api/projects/" + id + "/foreshadowings/" + fid + "/resolve"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resolved").value(true));
    }

    @Test
    void resolveManualReview_setsChapterDone() throws Exception {
        String id = createProject();
        Chapter chapter = new Chapter(id, 1, "雨夜账");
        chapter.setContent("正文");
        chapter.setStatus(Chapter.Status.NEEDS_MANUAL_REVIEW);
        repository.saveChapter(chapter);

        mvc.perform(post("/api/projects/" + id + "/chapters/1/resolve-review"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DONE"));

        mvc.perform(get("/api/projects/" + id + "/chapters/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DONE"));
    }

    private String createProject() throws Exception {
        CreateProjectRequest req = new CreateProjectRequest("cfr", "c", "tag", "SOLO", null);
        String json = mvc.perform(post("/api/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(req)))
                .andReturn().getResponse().getContentAsString();
        return om.readTree(json).get("id").asText();
    }
}
