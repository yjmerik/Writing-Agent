package com.writing.novel.agent;

import com.writing.novel.llm.LlmGateway;
import com.writing.novel.llm.LlmRequest;
import com.writing.novel.llm.LlmResponse;
import com.writing.novel.llm.client.StreamHandler;
import com.writing.novel.memory.SummaryService;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * NCS-116: 快照与卷摘要生成修复——
 * 1) 全书快照用专用 book-snapshot 模板（system prompt 指令 JSON/mainlineProgress，
 *    旧路径复用纯文本摘要模板）；
 * 2) extractSnapshotJson：合法 JSON 含 mainlineProgress 通过，纯文本/缺字段返回 null；
 * 3) 卷摘要输入有界截断（超长卷不再直塞爆窗）。
 */
class BookSnapshotGenerationTest {

    private MemoryManagerAgent agentReturning(String content, AtomicReference<String> sysPrompt,
                                              AtomicReference<String> userMessage) {
        LlmGateway gateway = new LlmGateway() {
            @Override public LlmResponse complete(LlmRequest req) {
                if (sysPrompt != null) sysPrompt.set(req.getSystemPrompt());
                if (userMessage != null) userMessage.set(req.getUserMessage());
                return new LlmResponse(content, 10, 100);
            }
            @Override public void streamComplete(LlmRequest req, StreamHandler h) {}
        };
        return new MemoryManagerAgent(gateway, (SummaryService) null, TestPromptHelper.create());
    }

    @Test
    void 快照走专用模板_指令含JSON与mainlineProgress() {
        var sysPrompt = new AtomicReference<String>();
        var userMessage = new AtomicReference<String>();
        var agent = agentReturning("{\"mainlineProgress\":\"主角抵达北境\"}", sysPrompt, userMessage);

        String out = agent.draftSnapshot("旧快照", "本卷摘要", "- 林凡：持断剑");
        assertEquals("{\"mainlineProgress\":\"主角抵达北境\"}", out.trim());
        assertTrue(sysPrompt.get().contains("mainlineProgress"), "专用模板必须指令 JSON 结构");
        assertTrue(sysPrompt.get().contains("旧快照"), "变量注入");
        assertTrue(sysPrompt.get().contains("本卷摘要"));
        assertTrue(sysPrompt.get().contains("持断剑"));
    }

    @Test
    void 快照JSON提取_合法通过_纯文本或缺字段拒绝() {
        assertEquals("{\"mainlineProgress\":\"x\"}",
                com.writing.novel.orchestrator.NovelOrchestrator.extractSnapshotJson(
                        "```json\n{\"mainlineProgress\":\"x\"}\n```"));
        assertNull(com.writing.novel.orchestrator.NovelOrchestrator.extractSnapshotJson(
                "本章压缩为三句话的摘要。"), "纯文本（旧模板典型输出）必须拒绝");
        assertNull(com.writing.novel.orchestrator.NovelOrchestrator.extractSnapshotJson(
                "{\"other\":1}"), "缺 mainlineProgress 拒绝");
        assertNull(com.writing.novel.orchestrator.NovelOrchestrator.extractSnapshotJson(""));
    }

    @Test
    void 卷摘要输入有界截断() {
        var userMessage = new AtomicReference<String>();
        var agent = agentReturning("摘要", null, userMessage);
        String huge = "很长的正文段落。".repeat(40000);  // ~30 万字
        var summary = agent.draftSummary("p", 1, 30, huge);
        assertNotNull(summary);
        assertTrue(userMessage.get().length() < huge.length() / 2,
                "超长输入必须截断（旧实现全文直塞）");
        assertTrue(userMessage.get().contains("已截断"), "截断需显式标注");
        // 短输入不截断
        var shortMsg = new AtomicReference<String>();
        agentReturning("摘要", null, shortMsg).draftSummary("p", 1, 2, "短正文");
        assertFalse(shortMsg.get().contains("已截断"));
    }
}
