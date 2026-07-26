package com.writing.novel.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.writing.novel.llm.LlmGateway;
import com.writing.novel.llm.LlmRequest;
import com.writing.novel.llm.LlmTaskType;
import com.writing.novel.llm.prompt.PromptTemplateService;
import com.writing.novel.service.NarrativeThreadTracker;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 叙事线索提取 Agent——每章写后从正文中提取剧情线索。
 * 与 NarrativeThreadTracker 配合使用，更新线索状态。
 */
public class NarrativeThreadExtractor {

    private final LlmGateway gateway;
    private final NarrativeThreadTracker threadTracker;
    private final PromptTemplateService promptService;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public NarrativeThreadExtractor(LlmGateway gateway, NarrativeThreadTracker threadTracker,
                                     PromptTemplateService promptService) {
        this.gateway = gateway;
        this.threadTracker = threadTracker;
        this.promptService = promptService;
    }

    /**
     * 从章节中提取叙事线索并更新追踪状态。
     */
    public void extractAndUpdate(String projectId, int chapterIndex, String chapterContent) {
        String systemPrompt = promptService.render("narrative-extract", Map.of(
                "chapterContent", chapterContent != null ? chapterContent : ""));

        var req = LlmRequest.builder()
                .taskType(LlmTaskType.GENERAL)
                .systemPrompt(systemPrompt)
                .userMessage("请提取本章涉及的叙事线索。")
                .temperature(0.3)
                .maxTokens(1500)
                .build();

        var resp = gateway.complete(req);
        parseAndUpdate(resp.getContent(), projectId, chapterIndex);
    }

    private void parseAndUpdate(String json, String projectId, int chapterIndex) {
        try {
            String clean = json.trim();
            if (clean.startsWith("```")) {
                clean = clean.replaceAll("^```(json)?\\s*", "").replaceAll("\\s*```$", "");
            }
            JsonNode root = MAPPER.readTree(clean);
            JsonNode threads = root.path("threads");
            if (!threads.isArray()) return;

            List<String> mentioned = new ArrayList<>();
            List<String> resolved = new ArrayList<>();

            for (JsonNode t : threads) {
                String name = t.path("name").asText("");
                String status = t.path("status").asText("active");
                String desc = t.path("description").asText("");

                if (name.isBlank()) continue;

                mentioned.add(name);
                threadTracker.registerThread(projectId, name, chapterIndex, desc);

                if ("resolved".equalsIgnoreCase(status)) {
                    resolved.add(name);
                }
            }

            threadTracker.updateThreads(projectId, chapterIndex, mentioned, resolved);
        } catch (Exception ignored) {
            // 提取失败不阻断写作流程
        }
    }
}
