package com.writing.novel.agent;

import com.writing.novel.domain.Summary;
import com.writing.novel.llm.LlmGateway;
import com.writing.novel.llm.LlmRequest;
import com.writing.novel.llm.LlmResponse;
import com.writing.novel.llm.client.StreamHandler;
import com.writing.novel.memory.SummaryService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MemoryManagerAgentTest {

    @Test
    void 压缩章节为摘要() {
        LlmGateway mockGateway = new LlmGateway() {
            @Override
            public LlmResponse complete(LlmRequest req) {
                return new LlmResponse("第1-3章：张三拜入青云宗，觉醒剑骨，初露锋芒，与师兄结怨。", 200, 100);
            }
            @Override
            public void streamComplete(LlmRequest req, StreamHandler handler) {}
        };

        boolean[] saved = {false};
        SummaryService summarySvc = new SummaryService(null) {
            @Override public void save(Summary s) { saved[0] = true; }
        };

        MemoryManagerAgent agent = new MemoryManagerAgent(mockGateway, summarySvc);
        Summary summary = agent.summarize("proj-1", 1, 3, "第1章正文……\n第2章正文……\n第3章正文……");

        assertNotNull(summary);
        assertEquals(1, summary.getFromChapter());
        assertEquals(3, summary.getToChapter());
        assertTrue(summary.getContent().contains("张三"));
        assertTrue(saved[0], "应保存摘要");
    }
}
