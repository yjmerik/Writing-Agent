package com.writing.novel.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;

/**
 * NCS-105: 结构化输出中心化解析——统一「剥围栏→解析→带因修复重试一次→类型化失败→事件」，
 * 替换散布在各 Agent/Service 的 12+ 处手写解析（失败语义各自为政的根源）。
 *
 * <p>失败分类：
 * <ul>
 *   <li>{@code EMPTY}：空/纯空白响应</li>
 *   <li>{@code INVALID}：剥围栏后仍不是合法 JSON（含截断的半截 JSON）</li>
 * </ul>
 * 截断判定（finish_reason=length）由调用方结合 {@code LlmResponse.isTruncated()} 上报，
 * 本组件在异常信息中保留 Jackson 截断特征（Unexpected end-of-input）便于归因。
 */
public final class LlmJson {

    private LlmJson() {}

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public enum Failure { EMPTY, INVALID }

    /** 解析结果：node 与 failure 二选一（ok() 判定） */
    public record Parsed(JsonNode node, Failure failure, String reason) {
        public boolean ok() { return node != null && failure == null; }
    }

    /** 剥 ```json / ``` 围栏与首尾空白 */
    public static String stripFences(String raw) {
        if (raw == null) return "";
        String clean = raw.trim();
        if (clean.startsWith("```")) {
            clean = clean.replaceAll("^```[a-zA-Z]*\\s*", "").replaceAll("\\s*```$", "");
        }
        return clean.trim();
    }

    /** 解析（不重试）：失败返回类型化结果而非异常 */
    public static Parsed parse(String raw) {
        String clean = stripFences(raw);
        if (clean.isEmpty()) {
            return new Parsed(null, Failure.EMPTY, "响应为空");
        }
        try {
            JsonNode node = MAPPER.readTree(clean);
            if (node == null || node.isMissingNode()) {
                return new Parsed(null, Failure.INVALID, "解析结果为缺失节点");
            }
            return new Parsed(node, null, null);
        } catch (Exception e) {
            return new Parsed(null, Failure.INVALID, classify(e, clean));
        }
    }

    /** 解析（不重试）：失败抛带分类信息的 IllegalArgumentException（fail-loud 调用方用） */
    public static JsonNode parseOrThrow(String raw, String context) {
        Parsed parsed = parse(raw);
        if (parsed.ok()) return parsed.node();
        throw new IllegalArgumentException(
                (context == null || context.isBlank() ? "" : context + ": ")
                        + parsed.failure() + " — " + parsed.reason());
    }

    /**
     * 带因修复重试一次：首次解析失败 → 发 STRUCTURED_OUTPUT_RETRY 事件 → 用修复提示重发
     * → 仍失败返回首次失败（类型化）。成功返回重试后的解析结果。
     *
     * @param retryUserMessage 修复提示（告知上次输出无效及期望格式）
     */
    public static Parsed parseWithRetry(String raw, LlmGateway gateway, LlmRequest original,
                                        String retryUserMessage, String phase) {
        Parsed first = parse(raw);
        if (first.ok()) return first;
        com.writing.novel.telemetry.EventLog.emit("STRUCTURED_OUTPUT_RETRY", Map.of(
                "taskType", original.getTaskType().name(),
                "phase", phase == null ? "unspecified" : phase,
                "attempt", 1,
                "reason", com.writing.novel.telemetry.EventLog.truncate(first.reason(), 200)));
        var retryReq = LlmRequest.builder()
                .taskType(original.getTaskType())
                .systemPrompt(original.getSystemPrompt())
                .userMessage(original.getUserMessage() + "\n" + retryUserMessage)
                .temperature(original.getTemperature())
                .maxTokens(original.getMaxTokens())
                .build();
        LlmResponse retryResp = gateway.complete(retryReq);
        Parsed second = parse(retryResp.getContent());
        if (second.ok()) return second;
        if (retryResp.isTruncated()) {
            return new Parsed(null, Failure.INVALID, "重试后仍截断: " + second.reason());
        }
        return new Parsed(null, Failure.INVALID, "重试后仍无效: " + second.reason());
    }

    /** 终态失败事件（调用方放弃时上报，杜绝静默） */
    public static void emitFailure(String phase, String taskTypeName, Parsed parsed) {
        com.writing.novel.telemetry.EventLog.emit("STRUCTURED_OUTPUT_FAILURE", Map.of(
                "taskType", taskTypeName,
                "phase", phase,
                "failure", parsed.failure().name(),
                "reason", com.writing.novel.telemetry.EventLog.truncate(parsed.reason(), 200)));
    }

    private static String classify(Exception e, String clean) {
        String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        boolean truncated = msg.contains("Unexpected end-of-input")
                || msg.contains("end-of-input")
                || (clean.trim().startsWith("[") && !clean.trim().endsWith("]"))
                || (clean.trim().startsWith("{") && !clean.trim().endsWith("}"));
        return (truncated ? "疑似截断（未闭合容器）: " : "") + msg;
    }
}
