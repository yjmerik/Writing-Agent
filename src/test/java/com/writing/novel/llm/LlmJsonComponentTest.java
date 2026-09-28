package com.writing.novel.llm;

import com.writing.novel.llm.LlmJson.Parsed;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * NCS-105: LlmJson 中心化结构化解析——围栏剥离/类型化失败/带因修复重试/终态事件/
 * 高危迁移点（QualityEvaluation 不再静默全 0 分、Foreshadow 提取失败可观测）。
 */
class LlmJsonComponentTest {

    private static final LlmGateway NULL_GATEWAY = new LlmGateway() {
        @Override public LlmResponse complete(LlmRequest req) { return new LlmResponse("", 0, 0); }
        @Override public void streamComplete(LlmRequest req, com.writing.novel.llm.client.StreamHandler h) {}
    };

    @Test
    void 围栏剥离_各形态() {
        assertEquals("{\"a\":1}", LlmJson.stripFences("```json\n{\"a\":1}\n```"));
        assertEquals("{\"a\":1}", LlmJson.stripFences("```\n{\"a\":1}\n```"));
        assertEquals("{\"a\":1}", LlmJson.stripFences("  {\"a\":1}  "));
        assertEquals("", LlmJson.stripFences(null));
    }

    @Test
    void 类型化失败_空与非法与截断特征() {
        assertEquals(LlmJson.Failure.EMPTY, LlmJson.parse("   ").failure());
        Parsed invalid = LlmJson.parse("not json at all");
        assertEquals(LlmJson.Failure.INVALID, invalid.failure());
        assertFalse(invalid.reason().isBlank());
        // 半截 JSON（截断特征）：未闭合容器 → INVALID 且 reason 提示疑似截断
        Parsed half = LlmJson.parse("[{\"desc\":\"断剑");
        assertEquals(LlmJson.Failure.INVALID, half.failure());
        assertTrue(half.reason().contains("截断"), "未闭合容器应提示疑似截断: " + half.reason());
    }

    @Test
    void 成功路径_对象与数组() {
        assertTrue(LlmJson.parse("{\"scores\":{},\"overallScore\":7}").ok());
        assertTrue(LlmJson.parse("[{\"type\":\"OBJECT_STATE\"}]").ok());
        assertEquals(7, LlmJson.parseOrThrow("{\"overallScore\":7}", "ctx")
                .path("overallScore").asInt());
        var ex = assertThrows(IllegalArgumentException.class,
                () -> LlmJson.parseOrThrow("broken", "ctx"));
        assertTrue(ex.getMessage().contains("ctx") && ex.getMessage().contains("INVALID"));
    }

    @Test
    void 带因重试一次_成功返回重试结果() {
        AtomicInteger calls = new AtomicInteger();
        StringBuilder seenHint = new StringBuilder();
        LlmGateway gateway = new LlmGateway() {
            @Override public LlmResponse complete(LlmRequest req) {
                calls.incrementAndGet();
                seenHint.append(req.getUserMessage());
                return new LlmResponse("{\"foreshadowings\":[]}", 1, 1);
            }
            @Override public void streamComplete(LlmRequest req, com.writing.novel.llm.client.StreamHandler h) {}
        };
        var req = LlmRequest.builder().taskType(LlmTaskType.GENERAL)
                .systemPrompt("s").userMessage("u").temperature(0.3).maxTokens(100).build();
        Parsed parsed = LlmJson.parseWithRetry("broken", gateway, req, "请只输出JSON。", "test-phase");
        assertTrue(parsed.ok(), "重试应成功: failure=" + parsed.failure() + " reason=" + parsed.reason()
                + " calls=" + calls.get() + " hintSeen=" + seenHint);
        assertEquals(1, calls.get(), "恰好一次修复重试（原始输出由调用方传入，不经网关）");
        assertTrue(seenHint.toString().contains("请只输出JSON。"), "重试应携带修复提示: " + seenHint);
    }

    @Test
    void 重试仍失败_类型化返回且事件可发射() {
        LlmGateway gateway = new LlmGateway() {
            @Override public LlmResponse complete(LlmRequest req) { return new LlmResponse("still broken", 1, 1); }
            @Override public void streamComplete(LlmRequest req, com.writing.novel.llm.client.StreamHandler h) {}
        };
        var req = LlmRequest.builder().taskType(LlmTaskType.GENERAL)
                .systemPrompt("s").userMessage("u").temperature(0.3).maxTokens(100).build();
        Parsed parsed = LlmJson.parseWithRetry("broken", gateway, req, "hint", "test-phase");
        assertFalse(parsed.ok());
        assertEquals(LlmJson.Failure.INVALID, parsed.failure());
        assertTrue(parsed.reason().contains("重试后仍"));
        assertDoesNotThrow(() -> LlmJson.emitFailure("test-phase", "GENERAL", parsed));
    }

    @Test
    void 高危迁移_QualityEvaluation失败不再静默() {
        // 直接验证类型化路径：坏 JSON 走 EMPTY/INVALID 而非吞异常
        var agent = new com.writing.novel.agent.QualityEvaluationAgent(NULL_GATEWAY, null,
                com.writing.novel.agent.TestPromptHelper.create());
        var report = agent.parseReport("not-json");
        assertNotNull(report);
        assertTrue(report.summary.contains("评价解析失败"), "降级报告必须显式标注解析失败");
        assertTrue(report.summary.contains("INVALID"));
    }

    @Test
    void 高危迁移_ForeshadowExtract失败路径走中心化() {
        // 首次坏输出 + 重试仍坏 → extract 返回 0（事件在内部发射，不再静默吞掉）
        LlmGateway gateway = new LlmGateway() {
            @Override public LlmResponse complete(LlmRequest req) { return new LlmResponse("broken", 1, 1); }
            @Override public void streamComplete(LlmRequest req, com.writing.novel.llm.client.StreamHandler h) {}
        };
        var repo = org.mockito.Mockito.mock(com.writing.novel.spi.NovelRepository.class);
        org.mockito.Mockito.when(repo.findForeshadowingsByProject("p")).thenReturn(List.of());
        var extractor = new com.writing.novel.agent.ForeshadowingExtractor(gateway, repo,
                com.writing.novel.agent.TestPromptHelper.create());
        var chapter = new com.writing.novel.domain.Chapter("p", 1, "第一章");
        chapter.setContent("正文内容");
        int count = extractor.extract("p", chapter);
        assertEquals(0, count);
    }
}
