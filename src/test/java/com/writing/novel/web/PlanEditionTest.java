package com.writing.novel.web;

import com.writing.novel.domain.Chapter;
import com.writing.novel.domain.ChapterSynopsis;
import com.writing.novel.domain.Foreshadowing;
import com.writing.novel.domain.Outline;
import com.writing.novel.domain.Project;
import com.writing.novel.domain.Volume;
import com.writing.novel.domain.WritingPreferences;
import com.writing.novel.spi.NovelRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * p33-08（设计 5.6 / 体验 #1、"20 章后仍 WRITING"）：篇幅变更与计划一致。
 * 缩减篇幅：先返回影响预览（超出新目标的未回收承诺、将被裁剪的章）；
 * confirm=true 才正式生效——目标更新 + 大纲裁剪 + 未回收承诺标记"失去承载位置"。
 * 此后大纲在 20 章耗尽，成书门禁可达（不再 WRITING 空转）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(StubLlmGatewayTestConfig.class)
@TestPropertySource(properties = {
        "persistence.embedding=stub",
        "persistence.sqlite.db-path=./data/test-plan-edition.db",
        "persistence.vector-store.index-path=./data/test-plan-edition-vecs.idx"
})
class PlanEditionTest {

    @Autowired MockMvc mvc;
    @Autowired NovelRepository repository;

    private String pid;

    @BeforeEach
    void setUp() throws Exception {
        Files.createDirectories(Path.of("./data"));
        Project project = new Project("篇幅一致", "概念", "悬疑");
        project.setState(com.writing.novel.domain.NovelState.WRITING);
        repository.saveProject(project);
        pid = project.getId();
        repository.saveWritingPreferences(new WritingPreferences(pid));
        Outline outline = new Outline(pid, "总纲");
        Volume v1 = new Volume(pid, 1, "卷一", "摘要");
        Volume v2 = new Volume(pid, 2, "卷二", "摘要");
        for (int i = 1; i <= 4; i++) v1.addSynopsis(new ChapterSynopsis(pid, i, "第" + i + "章", "梗概"));
        for (int i = 5; i <= 8; i++) v2.addSynopsis(new ChapterSynopsis(pid, i, "第" + i + "章", "梗概"));
        outline.addVolume(v1);
        outline.addVolume(v2);
        repository.saveOutline(outline);
        // 已写 1-3 章
        for (int i = 1; i <= 3; i++) {
            Chapter ch = new Chapter(pid, i, "第" + i + "章");
            ch.setContent("正文。".repeat(200));
            ch.setStatus(Chapter.Status.DONE);
            repository.saveChapter(ch);
        }
        repository.findProject(pid).ifPresent(p -> { p.setCurrentChapterIndex(3); repository.saveProject(p); });
        // 一个承诺预计第 7 章回收（超出新目标 5）
        Foreshadowing f = new Foreshadowing(pid, "红回形针的来历", "埋设", 2);
        f.setPriority(Foreshadowing.Priority.HIGH);
        f.setExpectedResolveChapter(7);
        repository.saveForeshadowing(f);
    }

    @Test
    void 缩减篇幅先给预览不生效() throws Exception {
        mvc.perform(post("/api/projects/" + pid + "/plan/target-chapters")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetChapters\":5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").value(false))
                .andExpect(jsonPath("$.preview.prunedChapters[0]").value(6))
                .andExpect(jsonPath("$.preview.affectedPromises[0].description").value("红回形针的来历"))
                .andExpect(jsonPath("$.preview.writtenChapters").value(3));

        // 预览不落盘：目标未变、大纲未裁
        assertEquals(24, repository.findWritingPreferences(pid).orElseThrow().getTargetChapters());
        assertEquals(8, countSynopses());
        // 未确认前不生成成书安排（无承诺失去承载位置的落库变化）
        assertEquals(7, repository.findForeshadowingsByProject(pid).get(0)
                .getExpectedResolveChapter());
    }

    @Test
    void 确认后目标更新大纲裁剪并暴露未决承诺() throws Exception {
        mvc.perform(post("/api/projects/" + pid + "/plan/target-chapters")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetChapters\":5,\"confirm\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").value(true))
                .andExpect(jsonPath("$.targetChapters").value(5))
                .andExpect(jsonPath("$.preview.affectedPromises[0].description").value("红回形针的来历"))
                .andExpect(jsonPath("$.preview.affectedPromises[0].expectedResolveChapter").value(7));

        assertEquals(5, repository.findWritingPreferences(pid).orElseThrow().getTargetChapters());
        assertEquals(5, countSynopses(), "大纲裁剪到新目标");
        assertTrue(countSynopses() >= 3, "已写章节的梗概不得被裁掉");
        // 未决承诺保留原期望值（作者裁决迁移/开放结局），系统不静默改写
        assertEquals(7, repository.findForeshadowingsByProject(pid).get(0).getExpectedResolveChapter());
    }

    @Test
    void 新目标低于已写章数被拒绝() throws Exception {
        mvc.perform(post("/api/projects/" + pid + "/plan/target-chapters")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetChapters\":2,\"confirm\":true}"))
                .andExpect(status().isBadRequest());
        assertEquals(24, repository.findWritingPreferences(pid).orElseThrow().getTargetChapters());
    }

    private int countSynopses() {
        Outline outline = repository.findOutlineByProject(pid).orElseThrow();
        return outline.getVolumes().stream().mapToInt(v -> v.getSynopses().size()).sum();
    }
}
