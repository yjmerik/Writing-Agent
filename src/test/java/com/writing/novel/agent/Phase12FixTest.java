package com.writing.novel.agent;

import com.writing.novel.llm.LlmGateway;
import com.writing.novel.llm.LlmRequest;
import com.writing.novel.llm.LlmResponse;
import com.writing.novel.llm.client.StreamHandler;
import com.writing.novel.memory.KnowledgeBaseService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * p12-02/03/04: 新题材验证失败回流修复——premise 客套剥离 / ReaderReview 解析重试 / 丢弃未命名空卡。
 */
class Phase12FixTest {

    private static LlmGateway llm(AtomicInteger counter, String... responses) {
        return new LlmGateway() {
            public LlmResponse complete(LlmRequest r) {
                int i = counter.incrementAndGet() - 1;
                return new LlmResponse(responses[Math.min(i, responses.length - 1)], 10, 10);
            }
            public void streamComplete(LlmRequest r, StreamHandler h) {}
        };
    }

    @Test
    void p12_02_stripPreamble剥离总纲前的客套话() {
        String dirty = "好的，作为您的专属长篇小说大纲规划专家，我已仔细研读。【总纲】主线：测试。";
        String clean = PlotterAgent.stripPreamble(dirty);
        assertTrue(clean.startsWith("【总纲】"), "应剥离客套前言");
        assertFalse(clean.contains("好的"));
        // 无前言不动
        assertEquals("【总纲】直接开始。", PlotterAgent.stripPreamble("【总纲】直接开始。"));
        assertEquals("", PlotterAgent.stripPreamble(null));
    }

    @Test
    void p12_03_解析失败时重试一次成功() {
        var calls = new AtomicInteger();
        String bad = "我认为这章写得不错（非JSON）";
        String good = "{\"scores\":{\"engagement\":8,\"coherence\":8,\"character\":8,\"hook\":8},\"overall\":9,\"summary\":\"不错\",\"improvement\":\"\",\"issues\":[]}";
        var agent = new ReaderReviewAgent(llm(calls, bad, good), TestPromptHelper.create());
        var report = agent.review(1, "正文".repeat(200));
        assertEquals(9, report.getScore());
        assertEquals(2, calls.get(), "解析失败应重试 1 次");
    }

    @Test
    void p12_03_重试仍失败才fallback() {
        var calls = new AtomicInteger();
        var agent = new ReaderReviewAgent(llm(calls, "还是不是JSON", "依旧不是JSON"), TestPromptHelper.create());
        var report = agent.review(1, "正文".repeat(200));
        assertTrue("检验解析失败".equals(report.getSummary()));
        assertEquals(2, calls.get());
    }

    @Test
    void p12_04_未命名空卡被丢弃不保存() {
        var calls = new AtomicInteger();
        var kb = mock(KnowledgeBaseService.class);
        var agent = new CharacterDesignerAgent(
                llm(calls, "（模型未按格式输出，无姓名字段）"), kb, TestPromptHelper.create());
        var result = agent.design("p", "设计一个配角", List.of(), "");
        assertNull(result, "未命名卡应返回 null");
        verify(kb, never()).saveCharacter(any());
    }
}
