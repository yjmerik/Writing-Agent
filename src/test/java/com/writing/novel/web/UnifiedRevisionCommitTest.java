package com.writing.novel.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.writing.novel.domain.Chapter;
import com.writing.novel.domain.ChapterSynopsis;
import com.writing.novel.domain.Outline;
import com.writing.novel.domain.Project;
import com.writing.novel.domain.Volume;
import com.writing.novel.llm.LlmGateway;
import com.writing.novel.llm.LlmRequest;
import com.writing.novel.llm.LlmResponse;
import com.writing.novel.llm.client.StreamHandler;
import com.writing.novel.spi.NovelRepository;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * p33-05（设计 5.2 / 体验 #9、#12）：统一版本提交不变量。
 * #9：零修改保存不得产生新版本与快照（幂等）。
 * #12：后处理链与作者修订竞态时，状态推进不得整行覆盖作者新稿
 *      （原 saveChapter 用内存旧对象写回：版本不变、内容回退、无快照——审计链断裂）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(UnifiedRevisionCommitTest.SlowPostProcessGatewayConfig.class)
@TestPropertySource(properties = {
        "persistence.embedding=stub",
        "persistence.sqlite.db-path=./data/test-unified-commit.db",
        "persistence.vector-store.index-path=./data/test-unified-commit-vecs.idx"
})
class UnifiedRevisionCommitTest {

    private static final String BODY = "这是足够长的章节正文，用于通过写入门槛。".repeat(120);

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;
    @Autowired NovelRepository repository;

    /** 后处理类调用慢返回（制造与作者编辑的竞态窗口）；正文生成快返回 */
    @TestConfiguration
    static class SlowPostProcessGatewayConfig {
        static final AtomicBoolean slowPhase = new AtomicBoolean(false);

        @org.springframework.context.annotation.Primary
        @org.springframework.context.annotation.Bean
        LlmGateway slowPostProcessGateway() {
            return new LlmGateway() {
                @Override
                public LlmResponse complete(LlmRequest req) {
                    switch (req.getTaskType()) {
                        case CHAPTER_WRITING -> {
                            return new LlmResponse(BODY, 50, 100);
                        }
                        case CONTINUITY_CHECK, GENERAL, MEMORY_SUMMARY -> {
                            if (slowPhase.get()) {
                                try { Thread.sleep(1200); } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                }
                            }
                            if (req.getTaskType() == com.writing.novel.llm.LlmTaskType.CONTINUITY_CHECK)
                                return new LlmResponse("未发现连续性问题。", 20, 20);
                            if (req.getSystemPrompt().startsWith("你是小说一致性审查专家"))
                                return new LlmResponse("{\"abilityViolations\":[],\"evidenceProblems\":[],\"anachronisms\":[]}", 20, 20);
                            if (req.getSystemPrompt().startsWith("你是一位资深网络小说读者"))
                                return new LlmResponse("{\"scores\":{\"engagement\":8,\"coherence\":8,\"character\":8,\"hook\":8},\"overall\":8,\"summary\":\"通过\"}", 20, 20);
                            return new LlmResponse("{\"issues\":[],\"score\":8}", 20, 20);
                        }
                        default -> {
                            return new LlmResponse("[]", 10, 10);
                        }
                    }
                }

                @Override
                public void streamComplete(LlmRequest req, StreamHandler handler) {
                }
            };
        }
    }

    @BeforeAll
    static void createDataDir() {
        new File("./data").mkdirs();
    }

    private String seedWritingProject() {
        Project project = new Project("统一提交", "概念", "悬疑");
        project.setState(com.writing.novel.domain.NovelState.WRITING);
        repository.saveProject(project);
        String pid = project.getId();
        Outline outline = new Outline(pid, "总纲");
        Volume v = new Volume(pid, 1, "卷一", "卷摘要");
        v.addSynopsis(new ChapterSynopsis(pid, 1, "第1章", "梗概"));
        outline.addVolume(v);
        repository.saveOutline(outline);
        return pid;
    }

    private Chapter awaitChapter(String pid) throws Exception {
        for (int i = 0; i < 100; i++) {
            var ch = repository.findChapterByIndex(pid, 1);
            if (ch.isPresent()) return ch.get();
            Thread.sleep(100);
        }
        throw new AssertionError("章节未在 10s 内出现");
    }

    private void awaitDone(String pid) throws Exception {
        for (int i = 0; i < 200; i++) {
            var ch = repository.findChapterByIndex(pid, 1);
            if (ch.isPresent() && ch.get().getStatus() == Chapter.Status.DONE) return;
            Thread.sleep(100);
        }
        throw new AssertionError("章节未在 20s 内 DONE");
    }

    @Test
    void 零修改保存不产生新版本或快照() throws Exception {
        String pid = seedWritingProject();
        Chapter ch = new Chapter(pid, 1, "第1章");
        ch.setContent(BODY);
        ch.setStatus(Chapter.Status.DONE);
        repository.saveChapter(ch);
        int version = repository.findChapterByIndex(pid, 1).orElseThrow().getVersion();
        int snapshots = repository.findChapterVersions(pid, 1).size();

        mvc.perform(put("/api/projects/" + pid + "/chapters/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of(
                                "expectedVersion", version, "title", "第1章", "content", BODY))))
                .andExpect(status().isOk());

        Chapter after = repository.findChapterByIndex(pid, 1).orElseThrow();
        assertEquals(version, after.getVersion(), "相同内容保存不得产生新版本（体验 #9 垃圾版本）");
        assertEquals(snapshots, repository.findChapterVersions(pid, 1).size(),
                "相同内容保存不得产生新快照");
    }

    @Test
    void 后处理期间作者编辑不得被状态推进覆盖() throws Exception {
        String pid = seedWritingProject();
        SlowPostProcessGatewayConfig.slowPhase.set(true);
        try {
            mvc.perform(post("/api/projects/" + pid + "/write-chapter"))
                    .andExpect(status().isOk());
            Chapter drafting = awaitChapter(pid);
            int authorBase = drafting.getVersion();

            // 竞态窗口：后处理慢链运行中，作者保存编辑
            String authorContent = BODY + "\n\n（作者定稿注：保留此段。）";
            MvcResult putResult = mvc.perform(put("/api/projects/" + pid + "/chapters/1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(om.writeValueAsString(Map.of(
                                    "expectedVersion", repository
                                            .findChapterByIndex(pid, 1).orElseThrow().getVersion(),
                                    "title", "第1章", "content", authorContent))))
                    .andReturn();
            System.out.println("[DEBUG_PUT] status=" + putResult.getResponse().getStatus()
                    + " body=" + putResult.getResponse().getContentAsString(StandardCharsets.UTF_8));
            org.junit.jupiter.api.Assertions.assertEquals(200, putResult.getResponse().getStatus());

            awaitDone(pid);

            Chapter finalChapter = repository.findChapterByIndex(pid, 1).orElseThrow();
            assertTrue(finalChapter.getContent().contains("作者定稿注"),
                    "后处理完成的状态推进不得覆盖作者新稿（体验 #12），实际内容尾："
                            + finalChapter.getContent().substring(Math.max(0,
                            finalChapter.getContent().length() - 50)));
            assertTrue(finalChapter.getVersion() >= authorBase + 1, "作者编辑的版本推进不得丢失");
            // 审计链：作者编辑前的内容必须留有快照
            assertTrue(repository.findChapterVersions(pid, 1).stream()
                            .anyMatch(v -> v.getContent().contains("足够长的章节正文")),
                    "被作者替换的稿子必须留有快照");
        } finally {
            SlowPostProcessGatewayConfig.slowPhase.set(false);
        }
    }
}
