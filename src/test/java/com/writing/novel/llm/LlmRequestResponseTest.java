package com.writing.novel.llm;

import com.writing.novel.llm.client.ChatMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LlmRequestResponseTest {

    @Test
    void 请求由系统提示与用户消息组成并标记任务类型() {
        LlmRequest req = LlmRequest.builder()
                .taskType(LlmTaskType.CHAPTER_WRITING)
                .systemPrompt("你是网文写作助手")
                .userMessage("写第一章，张三拜入山门。")
                .maxTokens(3000)
                .temperature(0.8)
                .build();

        assertEquals(LlmTaskType.CHAPTER_WRITING, req.getTaskType());
        assertEquals("你是网文写作助手", req.getSystemPrompt());
        assertEquals("写第一章，张三拜入山门。", req.getUserMessage());
        assertEquals(3000, req.getMaxTokens());
        assertEquals(0.8, req.getTemperature());
    }

    @Test
    void 请求可携带多轮历史消息() {
        LlmRequest req = LlmRequest.builder()
                .taskType(LlmTaskType.CONTINUITY_CHECK)
                .systemPrompt("你是连续性审查员")
                .userMessage("检查第5章是否与设定矛盾")
                .history(List.of(
                        new ChatMessage("assistant", "前文设定：张三是剑修"),
                        new ChatMessage("user", "第5章提到他是丹师")
                ))
                .build();

        assertEquals(2, req.getHistory().size());
        assertEquals("assistant", req.getHistory().get(0).role());
    }

    @Test
    void 响应包含正文与token用量() {
        LlmResponse resp = new LlmResponse("生成的正文", 120, 350);

        assertEquals("生成的正文", resp.getContent());
        assertEquals(120, resp.getPromptTokens());
        assertEquals(350, resp.getCompletionTokens());
        assertEquals(470, resp.getTotalTokens());
    }

    @Test
    void 任务类型覆盖核心场景() {
        assertNotNull(LlmTaskType.valueOf("WORLDBUILDING"));
        assertNotNull(LlmTaskType.valueOf("CHARACTER_DESIGN"));
        assertNotNull(LlmTaskType.valueOf("PLOTTING"));
        assertNotNull(LlmTaskType.valueOf("CHAPTER_WRITING"));
        assertNotNull(LlmTaskType.valueOf("CONTINUITY_CHECK"));
        assertNotNull(LlmTaskType.valueOf("STYLE_GUARDIAN"));
        assertNotNull(LlmTaskType.valueOf("MEMORY_SUMMARY"));
    }
}
