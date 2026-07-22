package com.writing.novel.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.writing.novel.domain.WritingPreferences;
import com.writing.novel.llm.LlmGateway;
import com.writing.novel.llm.LlmRequest;
import com.writing.novel.llm.LlmTaskType;
import com.writing.novel.llm.prompt.PromptTemplateService;
import com.writing.novel.spi.NovelRepository;

import java.util.HashMap;
import java.util.Map;

/**
 * 写作偏好调节 Agent（人机交互）。
 * 作者通过自然语言调整写作参数，Agent 解析意图并更新持久化配置。
 */
public class WritingPreferencesAgent {

    private final LlmGateway gateway;
    private final NovelRepository repository;
    private final PromptTemplateService promptService;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public WritingPreferencesAgent(LlmGateway gateway, NovelRepository repository,
                                   PromptTemplateService promptService) {
        this.gateway = gateway;
        this.repository = repository;
        this.promptService = promptService;
    }

    /**
     * 处理作者的自然语言输入，更新偏好并返回回复。
     */
    public ChatResult chat(String projectId, String userMessage) {
        // 加载或初始化偏好
        WritingPreferences prefs = repository.findWritingPreferences(projectId)
                .orElseGet(() -> {
                    var p = new WritingPreferences(projectId);
                    repository.saveWritingPreferences(p);
                    return p;
                });

        // 渲染 prompt
        String currentConfig = MAPPER.valueToTree(prefs.toMap()).toString();
        String systemPrompt = promptService.render("preferences-chat", Map.of(
                "currentConfig", currentConfig,
                "userMessage", userMessage));

        var req = LlmRequest.builder()
                .taskType(LlmTaskType.GENERAL)
                .systemPrompt(systemPrompt)
                .userMessage(userMessage)
                .temperature(0.3)
                .maxTokens(2000)
                .build();

        var resp = gateway.complete(req);
        return parseResponse(resp.getContent(), prefs);
    }

    @SuppressWarnings("unchecked")
    private ChatResult parseResponse(String json, WritingPreferences prefs) {
        try {
            String clean = json.trim();
            if (clean.startsWith("```")) {
                clean = clean.replaceAll("^```(json)?\\s*", "").replaceAll("\\s*```$", "");
            }
            JsonNode root = MAPPER.readTree(clean);

            String reply = root.path("reply").asText("已更新。");
            JsonNode updates = root.path("updates");

            if (updates != null && !updates.isEmpty()) {
                Map<String, Object> updateMap = new HashMap<>();
                updates.fieldNames().forEachRemaining(key -> {
                    JsonNode val = updates.get(key);
                    if (val.isBoolean()) updateMap.put(key, val.asBoolean());
                    else if (val.isNumber()) updateMap.put(key, val.asDouble());
                    else if (val.isArray()) {
                        try { updateMap.put(key, MAPPER.treeToValue(val, java.util.List.class)); }
                        catch (Exception ignored) {}
                    }
                    else updateMap.put(key, val.asText());
                });
                prefs.applyUpdates(updateMap);
                repository.saveWritingPreferences(prefs);
            }

            return new ChatResult(reply, prefs);
        } catch (Exception e) {
            return new ChatResult("解析失败，请重试。错误：" + e.getMessage(), prefs);
        }
    }

    public record ChatResult(String reply, WritingPreferences preferences) {}
}
