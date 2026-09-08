package com.writing.novel.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.writing.novel.domain.Chapter;
import com.writing.novel.domain.Project;
import com.writing.novel.domain.Summary;
import com.writing.novel.llm.LlmGateway;
import com.writing.novel.llm.LlmRequest;
import com.writing.novel.llm.LlmResponse;
import com.writing.novel.llm.LlmTaskType;
import com.writing.novel.llm.client.StreamHandler;
import com.writing.novel.service.WritingTaskExecutor;
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

import java.io.File;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * p32-13（UI 验证发现）：REST 修订路径的派生刷新必须异步。
 * 复现：restore/编辑保存后，同步派生链（摘要重算等 LLM 调用）把 HTTP 响应
 * 与工作所有权阻塞十几分钟——UI 显示"等待内容处理完成"形似死锁（数据其实已落库）。
 * 修复后：响应在正文落库后立即返回、所有权即刻释放，派生刷新经
 * DerivedWriteBoundary 版本门在后台完成。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(RevisionResponseLatencyTest.SlowSummaryGatewayConfig.class)
@TestPropertySource(properties = {
        "persistence.embedding=stub",
        "persistence.sqlite.db-path=./data/test-revision-latency.db",
        "persistence.vector-store.index-path=./data/test-revision-latency-vecs.idx"
})
class RevisionResponseLatencyTest {

    private static final String LONG_BODY = "这是一段满足人工章节保存门槛的正文。".repeat(40);
    private static final String SLOW_SUMMARY = "重算摘要（慢网关产物）";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired NovelRepository repository;
    @Autowired WritingTaskExecutor writingTaskExecutor;

    /** MEMORY_SUMMARY 人为变慢（4s），模拟真实 LLM 摘要耗时 */
    @TestConfiguration
    static class SlowSummaryGatewayConfig {
        static final AtomicInteger summaryCalls = new AtomicInteger();

        @org.springframework.context.annotation.Primary
        @org.springframework.context.annotation.Bean
        LlmGateway slowSummaryGateway() {
            return new LlmGateway() {
                @Override
                public LlmResponse complete(LlmRequest req) {
                    if (req.getTaskType() == LlmTaskType.MEMORY_SUMMARY) {
                        summaryCalls.incrementAndGet();
                        try {
                            Thread.sleep(4000);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        return new LlmResponse(SLOW_SUMMARY, 50, 50);
                    }
                    return new LlmResponse(
                            "这是桩 LLM 生成的正文内容，足够长用于测试章节写入和存储。".repeat(80), 50, 100);
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

    @Test
    void 慢派生刷新不阻塞修订响应且所有权立即释放最终一致() throws Exception {
        Project project = new Project("响应延迟", "概念", "现实");
        repository.saveProject(project);
        String projectId = project.getId();
        Chapter chapter = new Chapter(projectId, 1, "第一章");
        chapter.setContent(LONG_BODY);
        chapter.setStatus(Chapter.Status.DONE);
        repository.saveChapter(chapter);
        repository.saveSummary(new Summary(projectId, 1, 1, "旧摘要"));
        int baseVersion = repository.findChapterByIndex(projectId, 1).orElseThrow().getVersion();
        SlowSummaryGatewayConfig.summaryCalls.set(0);

        long t0 = System.nanoTime();
        mvc.perform(put("/api/projects/{id}/chapters/{index}", projectId, 1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "expectedVersion", baseVersion,
                                "title", "第一章",
                                "content", LONG_BODY + "作者校订。"))))
                .andExpect(status().isOk());
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;

        assertTrue(elapsedMs < 2500,
                "修订响应不得等待 4s 慢摘要（同步派生链回归），实际 " + elapsedMs + "ms");
        assertFalse(writingTaskExecutor.hasActiveTask(projectId),
                "响应返回后工作所有权必须已释放（UI busy 应立即恢复）");
        Chapter saved = repository.findChapterByIndex(projectId, 1).orElseThrow();
        assertEquals(LONG_BODY + "作者校订。", saved.getContent());
        assertEquals(baseVersion + 1, saved.getVersion());

        // 派生刷新在后台最终完成：摘要被重算为慢网关产物
        long deadline = System.currentTimeMillis() + 20_000;
        boolean recomputed = false;
        while (System.currentTimeMillis() < deadline) {
            recomputed = repository.findSummariesByProject(projectId).stream()
                    .anyMatch(s -> s.getFromChapter() <= 1 && s.getToChapter() >= 1
                            && SLOW_SUMMARY.equals(s.getContent()));
            if (recomputed) break;
            Thread.sleep(300);
        }
        assertTrue(recomputed, "后台派生刷新应最终重算摘要（最终一致）");
        assertFalse(writingTaskExecutor.hasActiveTask(projectId),
                "后台派生刷新不得持有项目工作所有权");
    }
}
