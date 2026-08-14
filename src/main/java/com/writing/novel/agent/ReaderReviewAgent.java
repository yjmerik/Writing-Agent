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
        ReviewReport report = parseReport(resp.getContent(), chapterIndex);
        // p12-03: 解析失败时降温+严格 JSON 指令重试 1 次（之前直接 5/10 假象）
        if ("检验解析失败".equals(report.getSummary())) {
            var retryReq = LlmRequest.builder()
                    .taskType(LlmTaskType.GENERAL)
                    .systemPrompt(systemPrompt)
                    .userMessage(chapterContent + "\n\n【重要】请只输出合法 JSON（含 scores.engagement/coherence/character/hook 与 overall/summary/improvement 字段），不要任何其他文字或代码块标记。")
                    .temperature(0.0)
                    .maxTokens(1500)
                    .build();
            try {
                report = parseReport(gateway.complete(retryReq).getContent(), chapterIndex);
            } catch (Exception ignored) {
            }
        }
        return report;
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

            return new ReviewReport(chapterIndex, overall, engagement, coherence,
                    character, hook, summary, issues, improvement);
        } catch (Exception e) {
            return new ReviewReport(chapterIndex, 5, 5, 5, 5, 5, "检验解析失败", List.of(), "");
        }
    }

    /** 通过阈值 */
    public int getPassThreshold() { return PASS_THRESHOLD; }
}
