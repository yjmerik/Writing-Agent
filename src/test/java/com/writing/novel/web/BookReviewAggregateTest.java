package com.writing.novel.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.writing.novel.domain.Chapter;
import com.writing.novel.domain.ChapterSynopsis;
import com.writing.novel.domain.Foreshadowing;
import com.writing.novel.domain.Outline;
import com.writing.novel.domain.Project;
import com.writing.novel.domain.Volume;
import com.writing.novel.spi.NovelRepository;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.File;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * p35-05（设计 5.7 / 验收缺口"成书清单与导出"第一版）：
 * GET /book-review 聚合覆盖清单（各章稿号/状态/评分/报告有效性）与未回收承诺；
 * GET /book-export 生成全书 Markdown 导出（含各章正文与稿号）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(StubLlmGatewayTestConfig.class)
@TestPropertySource(properties = {
        "persistence.embedding=stub",
        "persistence.sqlite.db-path=./data/test-book-review.db",
        "persistence.vector-store.index-path=./data/test-book-review-vecs.idx"
})
class BookReviewAggregateTest {

    @Autowired MockMvc mvc;
    @Autowired NovelRepository repository;

    @BeforeAll
    static void createDataDir() {
        new File("./data").mkdirs();
    }

    private String pid;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        Project project = new Project("成书审查", "概念", "悬疑");
        project.setState(com.writing.novel.domain.NovelState.WRITING);
        repository.saveProject(project);
        pid = project.getId();
        Outline outline = new Outline(pid, "总纲");
        Volume v = new Volume(pid, 1, "卷一", "摘要");
        for (int i = 1; i <= 2; i++) v.addSynopsis(new ChapterSynopsis(pid, i, "第" + i + "章", "梗概"));
        outline.addVolume(v);
        repository.saveOutline(outline);
        Chapter ch1 = new Chapter(pid, 1, "第一章");
        ch1.setContent("第一章正文。".repeat(150));
        ch1.setStatus(Chapter.Status.DONE);
        repository.saveChapter(ch1);
        Chapter ch2 = new Chapter(pid, 2, "第二章");
        ch2.setContent("第二章正文。".repeat(150));
        ch2.setStatus(Chapter.Status.NEEDS_MANUAL_REVIEW);
        repository.saveChapter(ch2);
        Foreshadowing f = new Foreshadowing(pid, "红回形针的来历", "埋设", 1);
        f.setPriority(Foreshadowing.Priority.HIGH);
        repository.saveForeshadowing(f);
    }

    @Test
    void 覆盖清单聚合各章状态稿号与未回收承诺() throws Exception {
        mvc.perform(get("/api/projects/" + pid + "/book-review"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chapters[0].chapterIndex").value(1))
                .andExpect(jsonPath("$.chapters[0].status").value("DONE"))
                .andExpect(jsonPath("$.chapters[1].status").value("NEEDS_MANUAL_REVIEW"))
                .andExpect(jsonPath("$.summary.doneChapters").value(1))
                .andExpect(jsonPath("$.summary.totalChapters").value(2))
                .andExpect(jsonPath("$.summary.totalWords").value(1800))
                .andExpect(jsonPath("$.unresolvedPromises[0].description").value("红回形针的来历"))
                .andExpect(jsonPath("$.targetChapters").exists());
    }

    @Test
    void 导出为全书Markdown含稿号与全部章节() throws Exception {
        var res = mvc.perform(get("/api/projects/" + pid + "/book-export"))
                .andExpect(status().isOk())
                .andReturn();
        String body = res.getResponse().getContentAsString();
        assertTrue(body.contains("# 成书审查：成书审查"), "导出应含书名标题: " + body.substring(0, 80));
        assertTrue(body.contains("第1章"), "导出应含第 1 章标题");
        assertTrue(body.contains("第二章正文"), "导出应含第 2 章正文");
        assertTrue(body.contains("v"), "导出应标注稿号");
        assertTrue(res.getResponse().getContentType() != null
                && res.getResponse().getContentType().contains("text/markdown"));
    }
}
