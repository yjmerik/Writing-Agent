package com.writing.novel.web;

import com.writing.novel.domain.Chapter;
import com.writing.novel.domain.ChapterSynopsis;
import com.writing.novel.domain.Foreshadowing;
import com.writing.novel.domain.NovelState;
import com.writing.novel.domain.Outline;
import com.writing.novel.domain.Project;
import com.writing.novel.domain.Volume;
import com.writing.novel.spi.NovelRepository;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.File;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * NCS-089：追加尾声回收卷解除 NEEDS_REVISION 禁写（第二轮旅程 A-8②）。
 * 作者宣告完结门禁失败后（NEEDS_REVISION），选择追加回收卷即意味着"继续写作回收章"——
 * 若停留在 NEEDS_REVISION，WRITE_CHAPTER/WRITE_ALL 被动作策略禁止，回收卷永远写不了。
 * 1) NEEDS_REVISION 下追加回收卷 → 状态回到 WRITING，写作动作恢复可用；
 * 2) 非 NEEDS_REVISION（如 WRITING）路径行为不变；
 * 3) 已有回收卷（有界一次）不再追加、不改状态。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(StubLlmGatewayTestConfig.class)
@TestPropertySource(properties = {
        "persistence.embedding=stub",
        "persistence.sqlite.db-path=./data/test-recovery-volume-resumes.db",
        "persistence.vector-store.index-path=./data/test-recovery-volume-resumes-vecs.idx"
})
class RecoveryVolumeResumesWritingTest {

    @Autowired MockMvc mvc;
    @Autowired NovelRepository repository;

    private String pid;

    @BeforeAll
    static void createDataDir() { new File("./data").mkdirs(); }

    @BeforeEach
    void setUp() {
        Project project = new Project("回收卷续写", "概念", "武侠");
        project.setState(NovelState.NEEDS_REVISION);
        repository.saveProject(project);
        pid = project.getId();

        Outline outline = new Outline(pid, "总纲：回收卷续写验证");
        Volume volume = new Volume(pid, 1, "卷一", "卷摘要");
        volume.addSynopsis(new ChapterSynopsis(pid, 1, "第一章", "章节梗概"));
        outline.addVolume(volume);
        repository.saveOutline(outline);

        Chapter chapter = new Chapter(pid, 1, "第一章");
        chapter.setContent("回收卷续写测试的正文内容，长度满足口径。".repeat(20));
        chapter.setStatus(Chapter.Status.DONE);
        repository.saveChapter(chapter);
        repository.findProject(pid).ifPresent(p -> {
            p.setCurrentChapterIndex(1);
            repository.saveProject(p);
        });

        Foreshadowing f = new Foreshadowing(pid, "必须回收的关键承诺：师父之死的真凶线索", "绳结记名", 1);
        f.setMustResolve(true);
        repository.saveForeshadowing(f);
    }

    @Test
    void NEEDS_REVISION下追加回收卷回到WRITING且写作动作可用() throws Exception {
        mvc.perform(post("/api/projects/" + pid + "/recovery-volume"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.appended").value(true));

        assertEquals(NovelState.WRITING, repository.findProject(pid).orElseThrow().getState(),
                "追加回收卷后回到写作态");
        var availability = new com.writing.novel.orchestrator.ProjectActionPolicy()
                .evaluate(repository.findProject(pid).orElseThrow(), true, false);
        assertTrue(availability.allowedActions().contains(
                        com.writing.novel.orchestrator.ProjectActionPolicy.ProjectAction.WRITE_CHAPTER),
                "写作动作恢复可用");
        assertEquals(2, repository.findOutlineByProject(pid).orElseThrow().getVolumes().size(),
                "回收卷已追加为第 2 卷");
    }

    @Test
    void 有界一次_已存在回收卷时不再追加也不改状态() throws Exception {
        mvc.perform(post("/api/projects/" + pid + "/recovery-volume"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.appended").value(true));
        // 作者此时又经其他路径进入 NEEDS_REVISION（如重审失败）
        repository.findProject(pid).ifPresent(p -> {
            p.setState(NovelState.NEEDS_REVISION);
            repository.saveProject(p);
        });

        mvc.perform(post("/api/projects/" + pid + "/recovery-volume"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.appended").value(false));

        assertEquals(NovelState.NEEDS_REVISION, repository.findProject(pid).orElseThrow().getState(),
                "未追加时不改状态");
        assertEquals(2, repository.findOutlineByProject(pid).orElseThrow().getVolumes().size(),
                "有界一次：不重复追加");
    }

    @Test
    void 写作态路径行为不变() throws Exception {
        repository.findProject(pid).ifPresent(p -> {
            p.setState(NovelState.WRITING);
            repository.saveProject(p);
        });
        mvc.perform(post("/api/projects/" + pid + "/recovery-volume"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.appended").value(true));
        assertEquals(NovelState.WRITING, repository.findProject(pid).orElseThrow().getState());
    }
}
