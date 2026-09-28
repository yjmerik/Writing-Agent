package com.writing.novel.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.writing.novel.domain.AuthorDirective;
import com.writing.novel.llm.LlmGateway;
import com.writing.novel.llm.LlmRequest;
import com.writing.novel.llm.LlmTaskType;
import com.writing.novel.llm.prompt.PromptTemplateService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * p37-15：生成后作者要求核对器——只读。对照作者要求逐条核对章节正文，
 * 返回证据候选（APPLIED_SYSTEM 需逐字 quote；否则 UNMET+原因）。
 * 核对器失败/漏项/伪造引用一律降级为 UNMET，绝不当作已落实；不修改正文、不确认意见。
 */
@Component
public class DirectiveEvidenceAgent {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private final LlmGateway gateway;
    private final PromptTemplateService promptService;

    public DirectiveEvidenceAgent(LlmGateway gateway, PromptTemplateService promptService) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.promptService = Objects.requireNonNull(promptService, "promptService");
    }

    /**
     * @param chapterJson    章节 JSON（含 title/content）
     * @param directivesJson 作者要求 JSON 数组（directiveId/revision/kind/fromChapter/toChapter/content）
     */
    public List<AuthorDirective.Evidence> evaluate(String projectId, int chapterIndex,
                                                   int chapterVersion, String chapterJson,
                                                   String directivesJson) {
        if (chapterJson == null || chapterJson.isBlank()
                || directivesJson == null || directivesJson.isBlank()) {
            throw new IllegalArgumentException("核对输入不能为空");
        }
        JsonNode directiveArray;
        try {
            directiveArray = com.writing.novel.llm.LlmJson.parseOrThrow(directivesJson, "directivesJson");
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("directivesJson 非法", e);
        }
        if (!directiveArray.isArray()) {
            throw new IllegalArgumentException("directivesJson 必须是数组");
        }
        if (directiveArray.isEmpty()) {
            return List.of();
        }
        // 已知意见 ID 与 revision——输出必须恰好覆盖，未知/重复/遗漏都视为未落实
        Map<String, Integer> known = new java.util.LinkedHashMap<>();
        for (JsonNode d : directiveArray) {
            String id = d.path("directiveId").asText(null);
            if (id == null || id.isBlank() || known.containsKey(id)) {
                throw new IllegalArgumentException("directivesJson 含空或重复的 directiveId");
            }
            known.put(id, d.path("revision").asInt(1));
        }

        String prompt = promptService.render("directive-evidence", Map.of(
                "chapterJson", chapterJson,
                "directivesJson", directivesJson));
        LlmRequest request = LlmRequest.builder()
                .taskType(LlmTaskType.GENERAL)
                .systemPrompt(prompt)
                .userMessage("请逐条核对作者要求并只输出 JSON 数组。")
                .temperature(0.0)
                .maxTokens(4096)
                .build();
        String content = gateway.complete(request).getContent();
        List<AuthorDirective.Evidence> candidates = parse(content);

        // 严格化：未知/重复 ID、APPLIED_SYSTEM 无逐字 quote → UNMET；遗漏 → UNMET
        String body = extractChapterBody(chapterJson);
        Set<String> seen = new HashSet<>();
        List<AuthorDirective.Evidence> result = new ArrayList<>();
        for (AuthorDirective.Evidence e : candidates) {
            if (!known.containsKey(e.directiveId()) || !seen.add(e.directiveId())) {
                continue; // 未知或重复 ID 不采信
            }
            boolean quoteVerified = e.status() == AuthorDirective.EvidenceStatus.APPLIED_SYSTEM
                    && e.quote() != null && !e.quote().isBlank()
                    && body != null && body.contains(e.quote());
            if (e.status() == AuthorDirective.EvidenceStatus.APPLIED_SYSTEM && !quoteVerified) {
                result.add(new AuthorDirective.Evidence(e.id(), e.directiveId(), e.directiveRevision(),
                        chapterIndex, chapterVersion, null, e.proposalId(),
                        e.quote(), e.anchor(), AuthorDirective.EvidenceStatus.UNMET,
                        "引用不在当前正文中，证据不足"));
            } else if (e.status() == AuthorDirective.EvidenceStatus.APPLIED_SYSTEM) {
                result.add(new AuthorDirective.Evidence(e.id(), e.directiveId(), e.directiveRevision(),
                        chapterIndex, chapterVersion, null, e.proposalId(),
                        e.quote(), e.anchor(), AuthorDirective.EvidenceStatus.APPLIED_SYSTEM, null));
            } else {
                result.add(new AuthorDirective.Evidence(e.id(), e.directiveId(), e.directiveRevision(),
                        chapterIndex, chapterVersion, null, e.proposalId(),
                        e.quote(), e.anchor(), AuthorDirective.EvidenceStatus.UNMET,
                        e.reason() == null || e.reason().isBlank() ? "证据不足" : e.reason()));
            }
        }
        // 遗漏的意见：UNMET（核对器漏项不等于已落实）
        for (Map.Entry<String, Integer> entry : known.entrySet()) {
            if (!seen.contains(entry.getKey())) {
                result.add(new AuthorDirective.Evidence(UUID_FOR_MISSING(), entry.getKey(),
                        entry.getValue(), chapterIndex, chapterVersion, null, null,
                        null, null, AuthorDirective.EvidenceStatus.UNMET, "核对器未覆盖该要求"));
            }
        }
        return result;
    }

    private List<AuthorDirective.Evidence> parse(String content) {
        if (content == null || content.isBlank()) {
            throw new IllegalStateException("核对器返回为空：要求保持未验证");
        }
        String json = content.trim();
        int start = json.indexOf('[');
        int end = json.lastIndexOf(']');
        if (start < 0 || end <= start) {
            throw new IllegalStateException("核对器返回非 JSON 数组：要求保持未验证");
        }
        try {
            JsonNode array = com.writing.novel.llm.LlmJson.parseOrThrow(json.substring(start, end + 1), "核对响应");
            List<AuthorDirective.Evidence> list = new ArrayList<>();
            for (JsonNode node : array) {
                String id = node.path("directiveId").asText(null);
                if (id == null) continue;
                AuthorDirective.EvidenceStatus status = parseStatus(node.path("status").asText(""));
                list.add(new AuthorDirective.Evidence(
                        java.util.UUID.randomUUID().toString(), id,
                        node.path("revision").asInt(1), 0, 0, null, null,
                        node.path("quote").asText(null), node.path("anchor").asText(null),
                        status == null ? AuthorDirective.EvidenceStatus.UNMET : status,
                        node.path("reason").asText(null)));
            }
            return list;
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("核对器返回解析失败：要求保持未验证", e);
        }
    }

    private AuthorDirective.EvidenceStatus parseStatus(String text) {
        try {
            return AuthorDirective.EvidenceStatus.valueOf(text);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private String extractChapterBody(String chapterJson) {
        var parsed = com.writing.novel.llm.LlmJson.parse(chapterJson);
        return parsed.ok() ? parsed.node().path("content").asText(null) : null;
    }

    private String UUID_FOR_MISSING() {
        return java.util.UUID.randomUUID().toString();
    }
}
