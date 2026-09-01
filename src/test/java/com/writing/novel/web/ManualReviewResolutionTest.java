package com.writing.novel.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.writing.novel.domain.Chapter;
import com.writing.novel.service.WritingTaskExecutor;
import com.writing.novel.spi.NovelRepository;
import com.writing.novel.web.dto.CreateProjectRequest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.File;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * p15-04: resolve-review 守卫——写作中拒绝、非 NEEDS_MANUAL_REVIEW 状态拒绝，均不得改章节。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(StubLlmGatewayTestConfig.class)
@TestPropertySource(properties = {
        "persistence.embedding=stub",
        "persistence.sqlite.db-path=./data/test-manual-review-guard.db",
        "persistence.vector-store.index-path=./data/test-manual-review-guard-vecs.idx"
})
class ManualReviewResolutionTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;
    @Autowired NovelRepository repository;
    @Autowired WritingTaskExecutor writingTaskExecutor;

    @BeforeAll
    static void createDataDir() { new File("./data").mkdirs(); }

    @Test
    void resolveManualReview_setsChapterDoneWhenNeedsReviewAndIdle() throws Exception {
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

    @Test
    void resolveManualReview_conflictWhenWriteTaskActive_keepsStatus() throws Exception {
        String id = createProject();
        Chapter chapter = new Chapter(id, 1, "雨夜账");
        chapter.setContent("正文");
        chapter.setStatus(Chapter.Status.NEEDS_MANUAL_REVIEW);
        repository.saveChapter(chapter);

        writingTaskExecutor.beginWork(id, "write-chapter");
        try {
            mvc.perform(post("/api/projects/" + id + "/chapters/1/resolve-review"))
                    .andExpect(status().isConflict());
        } finally {
            writingTaskExecutor.endWork(id, "write-chapter");
        }

        mvc.perform(get("/api/projects/" + id + "/chapters/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NEEDS_MANUAL_REVIEW"));
    }

    @Test
    void resolveManualReview_conflictWhenChapterNotNeedsReview_keepsStatus() throws Exception {
        String id = createProject();
        Chapter chapter = new Chapter(id, 1, "雨夜账");
        chapter.setContent("正文");
        chapter.setStatus(Chapter.Status.DRAFTING);
        repository.saveChapter(chapter);

        mvc.perform(post("/api/projects/" + id + "/chapters/1/resolve-review"))
                .andExpect(status().isConflict());

        mvc.perform(get("/api/projects/" + id + "/chapters/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFTING"));
    }

    private String createProject() throws Exception {
        CreateProjectRequest req = new CreateProjectRequest("cfr", "c", "tag", "SOLO", null);
        String json = mvc.perform(post("/api/projects")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(req)))
                .andReturn().getResponse().getContentAsString();
        return om.readTree(json).get("id").asText();
    }
}
