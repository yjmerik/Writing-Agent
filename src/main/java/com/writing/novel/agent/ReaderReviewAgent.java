package com.writing.novel.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.writing.novel.domain.ReviewReport;
import com.writing.novel.llm.LlmGateway;
import com.writing.novel.llm.LlmRequest;
import com.writing.novel.llm.LlmTaskType;
import com.writing.novel.llm.prompt.PromptTemplateService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 读者检验 Agent——以大众读者视角检验每章质量。
 * 评分 < 7 则建议自动重写。
 */
public class ReaderReviewAgent {

    private final LlmGateway gateway;
    private final PromptTemplateService promptService;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int PASS_THRESHOLD = 7;

    public ReaderReviewAgent(LlmGateway gateway, PromptTemplateService promptService) {
        this.gateway = gateway;
        this.promptService = promptService;
    }

    /**
     * 以读者视角检验章节。
     */
    public ReviewReport review(int chapterIndex, String chapterContent) {
        String systemPrompt = promptService.render("reader-review", Map.of());

        var req = LlmRequest.builder()
                .taskType(LlmTaskType.GENERAL)
                .systemPrompt(systemPrompt)
                .userMessage(chapterContent)
                .temperature(0.3)
                .maxTokens(1500)
                .build();

        var resp = gateway.complete(req);
        return parseReport(resp.getContent(), chapterIndex);
    }

    private ReviewReport parseReport(String json, int chapterIndex) {
        try {
            String clean = json.trim();
            if (clean.startsWith("```")) {
                clean = clean.replaceAll("^```(json)?\\s*", "").replaceAll("\\s*```$", "");
            }
            JsonNode root = MAPPER.readTree(clean);

            int engagement = root.path("scores").path("engagement").asInt(5);
            int coherence = root.path("scores").path("coherence").asInt(5);
            int character = root.path("scores").path("character").asInt(5);
            int hook = root.path("scores").path("hook").asInt(5);
            int overall = root.path("overall").asInt(5);

            String summary = root.path("summary").asText("");
            String improvement = root.path("improvement").asText("");

            List<String> issues = new ArrayList<>();
            JsonNode issuesNode = root.path("issues");
            if (issuesNode.isArray()) {
                for (JsonNode issue : issuesNode) {
                    issues.add(issue.asText());
                }
            }

            return new ReviewReport(chapterIndex, overall, summary, issues, improvement);
        } catch (Exception e) {
            return new ReviewReport(chapterIndex, 5, "检验解析失败", List.of(), "");
        }
    }

    /** 通过阈值 */
    public int getPassThreshold() { return PASS_THRESHOLD; }
}
