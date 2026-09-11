package com.writing.novel.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.writing.novel.domain.Chapter;
import com.writing.novel.domain.ChapterSynopsis;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * p33-02（设计 7）：统一工作区状态 API——前端按钮可用性的单一来源。
 * GET /api/projects/{id}/workspace 返回 stage/collabState/todoList（章节+稿号+原因）/
 * allowedActions+blockedReason；写作中/待人审/已解决三情形字段正确。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(StubLlmGatewayTestConfig.class)
@TestPropertySource(properties = {
        "persistence.embedding=stub",
        "persistence.sqlite.db-path=./data/test-workspace-status.db",
        "persistence.vector-store.index-path=./data/test-workspace-status-vecs.idx"
})
class WorkspaceStatusApiTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;
    @Autowired NovelRepository repository;

    @BeforeAll
    static void createDataDir() {
        new File("./data").mkdirs();
    }

    private String seedProject(String state) {
        Project project = new Project("状态API", "概念", "悬疑");
        project.setState(com.writing.novel.domain.NovelState.valueOf(state));
        repository.saveProject(project);
        String pid = project.getId();
        Outline outline = new Outline(pid, "总纲");
        Volume v = new Volume(pid, 1, "卷一", "卷摘要");
        for (int i = 1; i <= 3; i++) v.addSynopsis(new ChapterSynopsis(pid, i, "第" + i + "章", "梗概"));
        outline.addVolume(v);
        repository.saveOutline(outline);
        return pid;
    }

    private void writeChapter(String pid, int index, Chapter.Status status) {
        Chapter ch = new Chapter(pid, index, "第" + index + "章");
        ch.setContent("正文内容。".repeat(150));
        ch.setStatus(status);
        repository.saveChapter(ch);
    }

    @Test
    void 写作中状态与动作() throws Exception {
        String pid = seedProject("WRITING");
        writeChapter(pid, 1, Chapter.Status.DONE);
        repository.findProject(pid).ifPresent(p -> { p.setCurrentChapterIndex(1); repository.saveProject(p); });

        mvc.perform(get("/api/projects/" + pid + "/workspace"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stage").value("WRITING"))
                .andExpect(jsonPath("$.collabState").value("WRITING"))
                .andExpect(jsonPath("$.canContinue").value(true))
                .andExpect(jsonPath("$.primaryAction").value("WRITE_NEXT"))
                .andExpect(jsonPath("$.todoList").isEmpty())
                .andExpect(jsonPath("$.busy").value(false));
    }

    @Test
    void 待人审状态带稿号与阻塞原因() throws Exception {
        String pid = seedProject("WRITING");
        writeChapter(pid, 1, Chapter.Status.DONE);
        writeChapter(pid, 2, Chapter.Status.NEEDS_MANUAL_REVIEW);
        repository.findProject(pid).ifPresent(p -> { p.setCurrentChapterIndex(2); repository.saveProject(p); });

        mvc.perform(get("/api/projects/" + pid + "/workspace"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.collabState").value("AWAITING_REVIEW"))
                .andExpect(jsonPath("$.canContinue").value(false))
                .andExpect(jsonPath("$.blockedReason").value(org.hamcrest.Matchers.containsString("人工复核")))
                .andExpect(jsonPath("$.primaryAction").value("REVIEW_CHAPTER"))
                .andExpect(jsonPath("$.todoList[0].chapterIndex").value(2))
                .andExpect(jsonPath("$.todoList[0].chapterVersion").value(org.hamcrest.Matchers.greaterThan(0)))
                .andExpect(jsonPath("$.todoList[0].kind").value("MANUAL_REVIEW"))
                .andExpect(jsonPath("$.todoList[0].reason").exists());
    }

    @Test
    void 放行后恢复可写() throws Exception {
        String pid = seedProject("WRITING");
        writeChapter(pid, 1, Chapter.Status.DONE);
        writeChapter(pid, 2, Chapter.Status.NEEDS_MANUAL_REVIEW);
        repository.findProject(pid).ifPresent(p -> { p.setCurrentChapterIndex(2); repository.saveProject(p); });

        mvc.perform(post("/api/projects/" + pid + "/chapters/2/resolve-review"))
                .andExpect(status().isOk());

        mvc.perform(get("/api/projects/" + pid + "/workspace"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.collabState").value("WRITING"))
                .andExpect(jsonPath("$.canContinue").value(true))
                .andExpect(jsonPath("$.todoList").isEmpty());
    }
}
