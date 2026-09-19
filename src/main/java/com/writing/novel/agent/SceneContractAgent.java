package com.writing.novel.agent;

import com.writing.novel.llm.LlmGateway;
import com.writing.novel.llm.LlmRequest;
import com.writing.novel.llm.LlmResponse;
import com.writing.novel.llm.LlmTaskType;
import com.writing.novel.llm.prompt.PromptTemplateService;

import java.util.Map;

/**
 * NCS-018（方案 §06/F15）：场景合同生成 Agent——为指定章梗概产出可检查的因果合同。
 * 纯候选输出：解析为合同 JSON 文本交由 SceneContractService 落库；
 * 坏输出返回 null（不伪装为空合同）。
 */
public class SceneContractAgent {

    private final LlmGateway gateway;
    private final PromptTemplateService promptService;

    public SceneContractAgent(LlmGateway gateway, PromptTemplateService promptService) {
        this.gateway = gateway;
        this.promptService = promptService;
    }

    /** 返回合同 JSON 文本；解析失败返回 null */
    public String generate(String chapterTitle, String synopsis, String contextNotes) {
        String systemPrompt = promptService.render("scene-contract", Map.of(
                "chapterTitle", chapterTitle == null ? "" : chapterTitle,
                "synopsis", synopsis == null ? "" : synopsis,
                "contextNotes", contextNotes == null ? "" : contextNotes));
        var req = LlmRequest.builder()
                .taskType(LlmTaskType.GENERAL)
                .systemPrompt(systemPrompt)
                .userMessage("请为该章生成场景合同。")
                .temperature(0.3)
                .maxTokens(1500)
                .build();
        LlmResponse resp = gateway.complete(req);
        String raw = resp.getContent();
        if (raw == null || raw.isBlank()) return null;
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) return null;
        String json = raw.substring(start, end + 1);
        try {
            var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
            if (!node.isObject() || node.path("purpose").asText("").isBlank()) return null;
            return node.toString();
        } catch (Exception e) {
            return null;
        }
    }
}
