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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Generates detached, target-scoped content revision candidates.
 * This Agent deliberately has no repository dependency: persistence belongs to
 * the source-versioned preview/apply boundary.
 *
 * p37-11: 候选可携带逐项落实结果（directiveResults）——instructions 中以 [D-x revN]
 * 结构化编号行登记作者要求时，候选必须恰好覆盖每个已知 ID：APPLIED_SYSTEM 需正文引用，
 * UNMET 需原因；未知/重复/遗漏 ID 拒绝。无登记要求时结果为空（兼容旧调用）。
 */
@Component
public class ContentRevisionAgent {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    /** p37-11: instructions 中的结构化编号要求行格式：[D-1 rev2] 要求内容。 */
    private static final Pattern KNOWN_DIRECTIVE = Pattern.compile("\\[([^\\]\\s]+)\\s+rev(\\d+)\\]");

    private final LlmGateway gateway;
    private final PromptTemplateService promptService;

    public ContentRevisionAgent(LlmGateway gateway, PromptTemplateService promptService) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.promptService = Objects.requireNonNull(promptService, "promptService");
    }

    public RevisionCandidate preview(TargetType type, String currentJson, String instructions) {
        return preview(type, currentJson, instructions, extractKnownDirectives(instructions));
    }

    /** p37-11: 显式传入已知意见集合（优先于 instructions 内嵌编号行）。 */
    public RevisionCandidate preview(TargetType type, String currentJson, String instructions,
                                     List<KnownDirective> knownDirectives) {
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

        // NCS-093: 截断 fail-fast——失控长输出被 max_tokens 截断时（第二轮旅程 52k tokens/9.4min），
        // 立即给出行动指引，而不是让调用方在解析失败的泛化错误里空等
        var response = gateway.complete(request);
        if (response.isTruncated()) {
            throw new IllegalStateException(
                    "修订候选输出被 max_tokens 截断（finish_reason=length）——请缩小修改范围或按段落分次修改后重试");
        }
        String content = response.getContent();
        if (content == null || content.isBlank()) {
            throw invalidCandidate(null);
        }

        RevisionCandidate candidate;
        try {
            candidate = MAPPER.readValue(content, RevisionCandidate.class);
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw invalidCandidate(e);
        }
        return validateCandidate(type, candidate, knownDirectives);
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
            JsonNode current = com.writing.novel.llm.LlmJson.parseOrThrow(currentJson, "currentJson");
            if (!current.isObject()) {
                throw new IllegalArgumentException("currentJson 必须是单个 JSON 对象");
            }
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("currentJson 不是有效 JSON", e);
        }
    }

    private static RevisionCandidate validateCandidate(TargetType requestedType,
                                                       RevisionCandidate candidate,
                                                       List<KnownDirective> knownDirectives) {
        if (candidate == null || candidate.targetType() != requestedType
                || isBlank(candidate.title()) || isBlank(candidate.content())
                || isBlank(candidate.structuredJson()) || isBlank(candidate.summary())) {
            throw invalidCandidate(null);
        }

        try {
            JsonNode structured = com.writing.novel.llm.LlmJson.parseOrThrow(candidate.structuredJson(), "候选结构化 JSON");
            if (!structured.isObject()) {
                throw invalidCandidate(null);
            }
        } catch (IllegalArgumentException e) {
            throw invalidCandidate(e);
        }

        List<DirectiveResult> validatedResults =
                validateDirectiveResults(candidate.directiveResults(), knownDirectives);
        return new RevisionCandidate(
                candidate.targetType(),
                candidate.title().trim(),
                candidate.content().trim(),
                candidate.structuredJson().trim(),
                candidate.summary().trim(),
                validatedResults);
    }

    /** p37-11: 提取 instructions 中的结构化编号要求行：[D-1 rev2] 要求内容。 */
    static List<KnownDirective> extractKnownDirectives(String instructions) {
        List<KnownDirective> known = new ArrayList<>();
        if (instructions == null) return known;
        Set<String> seen = new java.util.HashSet<>();
        Matcher matcher = KNOWN_DIRECTIVE.matcher(instructions);
        while (matcher.find()) {
            String id = matcher.group(1);
            if (!seen.add(id)) {
                throw new IllegalArgumentException("instructions 中重复的要求编号: " + id);
            }
            known.add(new KnownDirective(id, Integer.parseInt(matcher.group(2))));
        }
        return known;
    }

    /** p37-11: 有 N 项登记要求则结果必须恰好覆盖 N 个已知 ID；未知/重复/空UNMET原因/APPLIED无quote 拒绝。 */
    static List<DirectiveResult> validateDirectiveResults(
            List<DirectiveResult> results, List<KnownDirective> knownDirectives) {
        List<DirectiveResult> input = results == null ? List.of() : results;
        if (knownDirectives.isEmpty()) {
            return List.of(); // 旧调用无要求：结果清空，兼容旧调用
        }
        Map<String, DirectiveResult> byId = new LinkedHashMap<>();
        for (DirectiveResult r : input) {
            boolean knownId = knownDirectives.stream().anyMatch(k -> k.directiveId().equals(r.directiveId()));
            if (!knownId) {
                throw new IllegalStateException("候选返回未知的要求编号: " + r.directiveId());
            }
            if (byId.put(r.directiveId(), r) != null) {
                throw new IllegalStateException("候选重复返回要求编号: " + r.directiveId());
            }
            boolean applied = "APPLIED_SYSTEM".equals(r.status());
            boolean unmet = "UNMET".equals(r.status());
            if (!applied && !unmet) {
                throw new IllegalStateException("非法落实状态: " + r.status());
            }
            if (applied && isBlank(r.quote())) {
                throw new IllegalStateException("APPLIED_SYSTEM 必须携带正文引用");
            }
            if (unmet && isBlank(r.reason())) {
                throw new IllegalStateException("UNMET 必须说明未落实原因");
            }
        }
        for (KnownDirective known : knownDirectives) {
            DirectiveResult r = byId.get(known.directiveId());
            if (r == null) {
                throw new IllegalStateException("候选遗漏要求编号: " + known.directiveId()
                        + "（未实现必须给出 UNMET 与原因）");
            }
            if (r.directiveRevision() != known.revision()) {
                throw new IllegalStateException("要求 revision 不匹配: " + known.directiveId());
            }
        }
        return List.copyOf(byId.values());
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

    /** p37-11: 登记要求的已知身份（ID + revision）。 */
    public record KnownDirective(String directiveId, int revision) {
    }

    public record RevisionCandidate(
            TargetType targetType,
            String title,
            String content,
            String structuredJson,
            String summary,
            List<DirectiveResult> directiveResults) {

        /** 兼容旧调用：无作者要求时结果为空。 */
        public RevisionCandidate(TargetType targetType, String title, String content,
                                 String structuredJson, String summary) {
            this(targetType, title, content, structuredJson, summary, List.of());
        }
    }

    /** p37-11: 单条落实结果：status 仅允许 APPLIED_SYSTEM（须有 quote）或 UNMET（须有 reason）。 */
    public record DirectiveResult(
            String directiveId,
            int directiveRevision,
            String status,
            String quote,
            String anchor,
            String reason) {
    }
}
