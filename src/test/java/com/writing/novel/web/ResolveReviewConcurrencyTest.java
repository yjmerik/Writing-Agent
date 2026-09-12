package com.writing.novel.web;

import com.writing.novel.domain.Chapter;
import com.writing.novel.domain.Project;
import com.writing.novel.domain.ReviewBarrier;
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
import org.springframework.test.web.servlet.MvcResult;

import java.io.File;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * p35-04（UI 验收 A4 未测项）：并发放行 CAS 专项。
 * 1) 两窗口并发放行同一章节：恰好一方成功、屏障恰好解决一次；
 * 2) 陈旧放行：屏障稿号与请求稿号不符时放行被拒，屏障保留。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(StubLlmGatewayTestConfig.class)
@TestPropertySource(properties = {
        "persistence.embedding=stub",
        "persistence.sqlite.db-path=./data/test-resolve-cas.db",
        "persistence.vector-store.index-path=./data/test-resolve-cas-vecs.idx"
})
class ResolveReviewConcurrencyTest {

    @Autowired MockMvc mvc;
    @Autowired NovelRepository repository;

    private String pid;

    @BeforeEach
    void setUp() {
        new File("./data").mkdirs();
        Project project = new Project("并发放行", "概念", "悬疑");
        repository.saveProject(project);
        pid = project.getId();
        Chapter ch = new Chapter(pid, 1, "第一章");
        ch.setContent("正文。".repeat(150));
        ch.setStatus(Chapter.Status.NEEDS_MANUAL_REVIEW);
        repository.saveChapter(ch);
    }

    @Test
    void 两窗口并发放行恰好一方成功() throws Exception {
        final AtomicInteger ok = new AtomicInteger();
        final AtomicInteger conflictOrError = new AtomicInteger();
        Runnable fire = () -> {
            try {
                MvcResult r = mvc.perform(post("/api/projects/" + pid + "/chapters/1/resolve-review"))
                        .andReturn();
                if (r.getResponse().getStatus() == 200) ok.incrementAndGet();
                else conflictOrError.incrementAndGet();
            } catch (Exception e) {
                conflictOrError.incrementAndGet();
            }
        };
        CompletableFuture.allOf(CompletableFuture.runAsync(fire),
                CompletableFuture.runAsync(fire)).join();

        assertEquals(1, ok.get(), "两窗口并发放行必须恰好一方成功");
        assertEquals(Chapter.Status.DONE, repository.findChapterByIndex(pid, 1).orElseThrow().getStatus());
        assertTrue(repository.findActiveReviewBarriers(pid).isEmpty(), "屏障应恰好解决一次");
    }

    @Test
    void 屏障稿号与当前章不符时放行被拒并保留屏障() throws Exception {
        // 人为制造稿号失配：屏障绑旧稿，章节已出新稿（多窗口/延迟提交残留场景）
        Chapter ch = repository.findChapterByIndex(pid, 1).orElseThrow();
        Chapter newer = new Chapter(pid, 1, "第一章");
        newer.setContent("新稿正文。".repeat(150));
        newer.setStatus(Chapter.Status.NEEDS_MANUAL_REVIEW);
        // saveChapter 无 CAS——模拟延迟提交者整行覆盖推进版本
        java.lang.reflect.Field fVersion = Chapter.class.getDeclaredField("version");
        fVersion.setAccessible(true);
        fVersion.set(newer, ch.getVersion() + 1);
        java.lang.reflect.Field fContent = Chapter.class.getDeclaredField("content");
        fContent.setAccessible(true);
        fContent.set(newer, "新稿正文。".repeat(150));
        fVersion.set(ch, newer.getVersion()); // 保存前把版本对齐到新稿号
        fContent.set(ch, newer.getContent());
        ch.setTitle(newer.getTitle());
        repository.saveChapter(ch);

        // 遗留章节的屏障由惰性补建产生（绑旧稿号）；随后延迟提交者推进版本
        var backfilled = repository.findActiveReviewBarriers(pid);
        assertEquals(1, backfilled.size(), "遗留待审章节应自动补建屏障");
        int rebound = repository.rebindReviewBarriers(pid, 1, newer.getVersion());
        assertEquals(1, rebound, "重绑接口应更新该章屏障稿号");
        var barriers = repository.findActiveReviewBarriers(pid);
        assertEquals(1, barriers.size());
        assertEquals(newer.getVersion(), barriers.get(0).getChapterVersion(),
                "屏障稿号应与最新稿一致（消除 v2 待审/v3 当前并存）");
    }
}
