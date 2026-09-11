package com.writing.novel.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.writing.novel.domain.Chapter;
import com.writing.novel.domain.ChapterSynopsis;
import com.writing.novel.domain.Outline;
import com.writing.novel.domain.Project;
import com.writing.novel.domain.Volume;
import com.writing.novel.domain.WritingTask;
import com.writing.novel.persistence.sqlite.ConnectionProvider;
import com.writing.novel.service.WritingTaskExecutor;
import com.writing.novel.spi.NovelRepository;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * p33-01（设计 5.1 / 体验 #18）：人审屏障必须可靠阻断后续章节生成。
 * 复现：第 5 章被标记 NEEDS_MANUAL_REVIEW 时若 currentChapterIndex 已推进（写作循环
 * 曾经绕过单章状态检查继续写），write-all 仍不得启动新章；屏障解决后恢复。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(StubLlmGatewayTestConfig.class)
@TestPropertySource(properties = {
        "persistence.embedding=stub",
        "persistence.sqlite.db-path=./data/test-review-barrier.db",
        "persistence.vector-store.index-path=./data/test-review-barrier-vecs.idx"
})
class ReviewBarrierGateTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;
    @Autowired NovelRepository repository;

    @BeforeAll
    static void createDataDir() {
        new File("./data").mkdirs();
    }

    /** 建 7 章大纲 + 写完 1-4 章 DONE + 第 5 章 NEEDS_MANUAL_REVIEW + index 推进到 6 */
    private String seedProjectWithStaleBarrier() {
        Project project = new Project("屏障门", "概念", "悬疑");
        project.setState(com.writing.novel.domain.NovelState.WRITING);
        repository.saveProject(project);
        String pid = project.getId();
        Outline outline = new Outline(pid, "总纲：代笔信案");
        Volume volume = new Volume(pid, 1, "卷一", "卷摘要");
        for (int i = 1; i <= 7; i++) {
            volume.addSynopsis(new ChapterSynopsis(pid, i, "第" + i + "章", "梗概" + i));
        }
        outline.addVolume(volume);
        repository.saveOutline(outline);
        for (int i = 1; i <= 4; i++) {
            Chapter ch = new Chapter(pid, i, "第" + i + "章");
            ch.setContent("已完成正文。".repeat(120));
            ch.setStatus(Chapter.Status.DONE);
            repository.saveChapter(ch);
        }
        Chapter pending = new Chapter(pid, 5, "第5章");
        pending.setContent("待审正文。".repeat(120));
        pending.setStatus(Chapter.Status.NEEDS_MANUAL_REVIEW);
        repository.saveChapter(pending);
        // 体验 #18 现场：标记后索引已推进（下一章即将启动）
        project.setCurrentChapterIndex(6);
        repository.saveProject(project);
        return pid;
    }

    record TaskView(String status, String errorMessage) {}

    private TaskView awaitTerminal(String pid, String taskId) throws Exception {
        for (int i = 0; i < 150; i++) {
            MvcResult r = mvc.perform(get("/api/projects/" + pid + "/writing-tasks/" + taskId))
                    .andExpect(status().isOk()).andReturn();
            var node = om.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
            String status = node.path("status").asText();
            if (List.of("COMPLETED", "FAILED", "PAUSED", "CANCELLED").contains(status)) {
                return new TaskView(status, node.path("errorMessage").asText(""));
            }
            Thread.sleep(100);
        }
        throw new AssertionError("任务未在 15s 内到终态");
    }

    @Test
    void 待审屏障存在时索引推进后写作仍不得启动新章() throws Exception {
        String pid = seedProjectWithStaleBarrier();
        MvcResult res = mvc.perform(post("/api/projects/" + pid + "/write-all"))
                .andExpect(status().isOk()).andReturn();
        String taskId = om.readTree(res.getResponse().getContentAsString()).path("taskId").asText();

        TaskView terminal = awaitTerminal(pid, taskId);
        assertEquals("PAUSED", terminal.status(),
                "存在待人审章节时任务必须暂停而不是继续写，实际 " + terminal.status()
                        + " err=" + terminal.errorMessage());
        assertTrue(terminal.errorMessage().contains("人工复核"),
                "暂停原因应指向人工复核: " + terminal.errorMessage());
        // 下一章（第 7 章，index=6 之后）不得被启动（体验 #18 中曾被绕过写出新章）
        assertTrue(repository.findChapterByIndex(pid, 7).isEmpty(),
                "屏障存在时不得生成第 7 章正文");
        assertEquals(4, repository.findChaptersByProject(pid).stream()
                .filter(c -> c.getStatus() == Chapter.Status.DONE).count());
    }

    @Test
    void 屏障解决后写作恢复() throws Exception {
        String pid = seedProjectWithStaleBarrier();
        // 作者放行第 5 章
        mvc.perform(post("/api/projects/" + pid + "/chapters/5/resolve-review"))
                .andExpect(status().isOk());
        assertEquals(Chapter.Status.DONE,
                repository.findChapterByIndex(pid, 5).orElseThrow().getStatus());

        MvcResult res = mvc.perform(post("/api/projects/" + pid + "/write-all"))
                .andExpect(status().isOk()).andReturn();
        String taskId = om.readTree(res.getResponse().getContentAsString()).path("taskId").asText();
        TaskView terminal = awaitTerminal(pid, taskId);
        // 屏障解除后允许继续写作（stub 网关下会写第 6、7 章直至大纲耗尽）
        assertTrue(List.of("COMPLETED", "PAUSED").contains(String.valueOf(terminal.status())),
                "解除后应可继续，实际 " + terminal.status() + " err=" + terminal.errorMessage());
        assertTrue(repository.findChapterByIndex(pid, 7).isPresent(),
                "解除后下一章（第 7 章）应被生成");
    }

    @Test
    void 屏障从数据库读回并在重启后仍有效() throws Exception {
        String pid = seedProjectWithStaleBarrier();
        // 直接从仓储读回屏障（模拟重启后的新实例视角）
        var barriers = repository.findActiveReviewBarriers(pid);
        assertEquals(1, barriers.size(), "标记人审应存在一条持久化屏障");
        var barrier = barriers.get(0);
        assertEquals(5, barrier.getChapterIndex());
        assertTrue(barrier.getChapterVersion() > 0, "屏障应绑定稿号");
        assertEquals(com.writing.novel.domain.ReviewBarrier.Status.ACTIVE, barrier.getStatus());

        mvc.perform(post("/api/projects/" + pid + "/chapters/5/resolve-review"))
                .andExpect(status().isOk());
        assertTrue(repository.findActiveReviewBarriers(pid).isEmpty(), "放行后屏障应解决");
    }

    private int countWords(String pid, int idx) {
        return repository.findChapterByIndex(pid, idx).map(Chapter::getWordCount).orElse(0);
    }
}
