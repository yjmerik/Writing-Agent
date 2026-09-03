package com.writing.novel.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.writing.novel.llm.LlmGateway;
import com.writing.novel.llm.LlmRequest;
import com.writing.novel.llm.LlmTaskType;
import com.writing.novel.llm.prompt.PromptTemplateService;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Objects;

/**
 * Generates detached, target-scoped content revision candidates.
 * This Agent deliberately has no repository dependency: persistence belongs to
 * the source-versioned preview/apply boundary.
 */
@Component
public class ContentRevisionAgent {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private final LlmGateway gateway;
    private final PromptTemplateService promptService;

    public ContentRevisionAgent(LlmGateway gateway, PromptTemplateService promptService) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.promptService = Objects.requireNonNull(promptService, "promptService");
    }

    public RevisionCandidate preview(TargetType type, String currentJson, String instructions) {
        requireInput(type, currentJson, instructions);

        String prompt = promptService.render("content-revision", Map.of(
                "targetType", type.name(),
                "currentJson", currentJson,
                "instructions", instructions));

        LlmRequest request = LlmRequest.builder()
                .taskType(LlmTaskType.GENERAL)
                .systemPrompt(prompt)
                .userMessage("请生成严格符合约束的内容修订候选。")
                .temperature(0.2)
                .maxTokens(8192)
                .build();

        String content = gateway.complete(request).getContent();
        if (content == null || content.isBlank()) {
            throw invalidCandidate(null);
        }

        RevisionCandidate candidate;
        try {
            candidate = MAPPER.readValue(content, RevisionCandidate.class);
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw invalidCandidate(e);
        }
        return validateCandidate(type, candidate);
    }

    private static void requireInput(TargetType type, String currentJson, String instructions) {
        if (type == null) {
            throw new IllegalArgumentException("targetType 不能为空");
        }
        if (currentJson == null || currentJson.isBlank()) {
            throw new IllegalArgumentException("currentJson 不能为空");
        }
        if (instructions == null || instructions.isBlank()) {
            throw new IllegalArgumentException("instructions 不能为空");
        }
        try {
            JsonNode current = MAPPER.readTree(currentJson);
            if (current == null || !current.isObject()) {
                throw new IllegalArgumentException("currentJson 必须是单个 JSON 对象");
            }
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("currentJson 不是有效 JSON", e);
        }
    }

    private static RevisionCandidate validateCandidate(TargetType requestedType,
                                                        RevisionCandidate candidate) {
        if (candidate == null || candidate.targetType() != requestedType
                || isBlank(candidate.title()) || isBlank(candidate.content())
                || isBlank(candidate.structuredJson()) || isBlank(candidate.summary())) {
            throw invalidCandidate(null);
        }

        try {
            JsonNode structured = MAPPER.readTree(candidate.structuredJson());
            if (structured == null || !structured.isObject()) {
                throw invalidCandidate(null);
            }
        } catch (JsonProcessingException e) {
            throw invalidCandidate(e);
        }

        return new RevisionCandidate(
                candidate.targetType(),
                candidate.title().trim(),
                candidate.content().trim(),
                candidate.structuredJson().trim(),
                candidate.summary().trim());
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static IllegalStateException invalidCandidate(Exception cause) {
        return new IllegalStateException("Agent 未返回有效修改候选", cause);
    }

    public enum TargetType {
        WORLD,
        CHARACTER,
        VOLUME,
        CHAPTER,
        PASSAGE
    }

    public record RevisionCandidate(
            TargetType targetType,
            String title,
            String content,
            String structuredJson,
            String summary) {
    }
}
