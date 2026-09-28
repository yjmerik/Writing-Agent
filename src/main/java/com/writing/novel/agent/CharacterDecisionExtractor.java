package com.writing.novel.agent;

import com.writing.novel.domain.CharacterDecision;
import com.writing.novel.domain.Chapter;
import com.writing.novel.llm.LlmGateway;
import com.writing.novel.llm.LlmRequest;
import com.writing.novel.llm.LlmResponse;
import com.writing.novel.llm.LlmTaskType;
import com.writing.novel.llm.prompt.PromptTemplateService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * NCS-012: 关键决策提取 Agent——从章节正文识别可追踪的人物选择（方案 §07/F16）。
 * 纯候选输出：解析结果交由 CharacterDecisionService 落库；坏输出返回空列表，
 * 状态分类由服务层经检查信封记录（INVALID/UNKNOWN 不伪装为无决策）。
 */
public class CharacterDecisionExtractor {

    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private final LlmGateway gateway;
    private final PromptTemplateService promptService;

    public CharacterDecisionExtractor(LlmGateway gateway, PromptTemplateService promptService) {
        this.gateway = gateway;
        this.promptService = promptService;
    }

    /** 提取结果：候选决策 + 分类状态（SUCCESS/VALID_EMPTY/INVALID/UNKNOWN） */
    public record Extraction(List<CharacterDecision> decisions,
                             CheckResultEnvelope.Status status, String reason) {}

    public Extraction extract(String projectId, Chapter chapter) {
        String systemPrompt = promptService.render("character-decision", Map.of());
        var req = LlmRequest.builder()
                .taskType(LlmTaskType.GENERAL)
                .systemPrompt(systemPrompt)
                .userMessage(chapter.getContent() == null ? "" : chapter.getContent())
                .temperature(0.2)
                .maxTokens(2000)
                .build();
        LlmResponse resp;
        try {
            resp = gateway.complete(req);
        } catch (Exception e) {
            return new Extraction(List.of(), CheckResultEnvelope.Status.UNKNOWN,
                    "提取调用失败: " + e.getClass().getSimpleName());
        }
        String raw = resp.getContent();
        if (raw == null || raw.isBlank()) {
            return new Extraction(List.of(), CheckResultEnvelope.Status.UNKNOWN, "空响应");
        }
        int start = raw.indexOf('[');
        int end = raw.lastIndexOf(']');
        if (start < 0 || end <= start) {
            return new Extraction(List.of(), CheckResultEnvelope.Status.INVALID, "输出不含 JSON 数组");
        }
        List<CharacterDecision> decisions = new ArrayList<>();
        try {
            var arr = com.writing.novel.llm.LlmJson.parseOrThrow(raw.substring(start, end + 1), "角色决策响应");
            for (var item : arr) {
                String character = item.path("character").asText("");
                String chosen = item.path("chosenAction").asText("");
                if (character.isBlank() || chosen.isBlank()) continue;
                CharacterDecision d = new CharacterDecision();
                d.setProjectId(projectId);
                d.setCharacterName(character);
                d.setChapterIndex(chapter.getChapterIndex());
                d.setChapterVersion(chapter.getVersion());
                d.setTriggerEvent(item.path("triggerEvent").asText(""));
                d.setKnewThen(item.path("knewThen").asText(""));
                List<String> options = new ArrayList<>();
                item.path("options").forEach(o -> {
                    String text = o.asText("");
                    if (!text.isBlank()) options.add(text);
                });
                d.setOptions(options);
                d.setChosenAction(chosen);
                d.setCost(item.path("cost").asText(""));
                d.setConflictWithCommitment(item.path("conflictWithCommitment").asText(""));
                d.setEvidenceQuote(item.path("evidenceQuote").asText(""));
                decisions.add(d);
            }
        } catch (Exception e) {
            return new Extraction(List.of(), CheckResultEnvelope.Status.INVALID, "JSON 解析失败: " + e.getMessage());
        }
        if (decisions.isEmpty() && raw.contains("[]")) {
            return new Extraction(List.of(), CheckResultEnvelope.Status.VALID_EMPTY, "");
        }
        if (decisions.isEmpty()) {
            return new Extraction(List.of(), CheckResultEnvelope.Status.INVALID, "数组无有效条目");
        }
        return new Extraction(decisions, CheckResultEnvelope.Status.SUCCESS, "");
    }
}
