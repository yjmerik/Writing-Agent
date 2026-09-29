package com.writing.novel.llm.gateway;

import com.writing.novel.llm.LlmGateway;
import com.writing.novel.llm.LlmRequest;
import com.writing.novel.llm.LlmResponse;
import com.writing.novel.llm.LlmResponse.Execution;
import com.writing.novel.llm.LlmTaskType;
import com.writing.novel.telemetry.EventLog;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * NCS-131: 语义缓存装饰网关——复用 RecordReplay 的归一化 prompt 指纹做生产缓存。
 *
 * <ul>
 *   <li>键 = (taskType, provider, model, 归一化 prompt 指纹, temperature)</li>
 *   <li>白名单任务启用（低温度确定性任务：CONTINUITY_CHECK/STYLE_GUARDIAN/EXTRACTION/MEMORY_SUMMARY）；
 *       CHAPTER_WRITING 等创意任务绝不缓存</li>
 *   <li>失效粒度 = projectId + chapterIndex（章稿推进即失效同章缓存条目）</li>
 *   <li>命中发 LLM_CACHE_HIT 事件（成本节约可见），不命中透传并写入</li>
 * </ul>
 * 上下文漂移防御：请求的 prompt 中含「上一章结尾/近期摘要」的任务（如读者评审）不在白名单内，
 * 指纹天然防错配；白名单任务的 prompt 只含确定性输入（正文+设定）。
 */
public class SemanticCacheLlmGateway implements LlmGateway {

    /** 确定性任务白名单——低温度、输入稳定，缓存命中即正确 */
    private static final List<LlmTaskType> CACHEABLE = List.of(
            LlmTaskType.CONTINUITY_CHECK, LlmTaskType.STYLE_GUARDIAN,
            LlmTaskType.EXTRACTION, LlmTaskType.MEMORY_SUMMARY);

    /** 归一化：prompt 内出现的 updatedAt 时间戳等易变元数据置为占位（防指纹无谓分叉） */
    private static final java.util.regex.Pattern UPDATED_AT_FIELD =
            java.util.regex.Pattern.compile("updatedAt.{0,4}\\d{4}-\\d{2}-\\d{2}");

    private record CachedResponse(String content, int promptTokens, int completionTokens,
                                   String finishReason, Execution execution) {}
    private record CacheEntry(CachedResponse response, String scopeKey) {}

    private final LlmGateway delegate;
    private final String provider;
    private final String model;
    private final int maxEntries;
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    public SemanticCacheLlmGateway(LlmGateway delegate, String provider, String model, int maxEntries) {
        this.delegate = delegate;
        this.provider = provider == null ? "default" : provider;
        this.model = model == null ? "unknown" : model;
        this.maxEntries = maxEntries <= 0 ? 2000 : maxEntries;
    }

    @Override
    public LlmResponse complete(LlmRequest request) {
        if (!CACHEABLE.contains(request.getTaskType())) {
            return delegate.complete(request);
        }
        String key = cacheKey(request);
        String scopeKey = scopeKey();
        CacheEntry hit = cache.get(key);
        if (hit != null && hit.scopeKey().equals(scopeKey)) {
            EventLog.emit("LLM_CACHE_HIT", Map.of(
                    "taskType", request.getTaskType().name(),
                    "provider", provider, "model", model,
                    "cacheKey", key, "cacheSize", cache.size()));
            var r = hit.response();
            return new LlmResponse(r.content(), r.promptTokens(), r.completionTokens(),
                    r.finishReason(), r.execution());
        }
        LlmResponse response = delegate.complete(request);
        if (cache.size() >= maxEntries) {
            cache.clear();   // 简单有界策略：满则整体重建（缓存为纯优化，丢失只影响成本）
        }
        cache.put(key, new CacheEntry(new CachedResponse(response.getContent(),
                response.getPromptTokens(), response.getCompletionTokens(),
                response.getFinishReason(), response.getExecution()), scopeKey));
        return response;
    }

    @Override
    public void streamComplete(LlmRequest request, com.writing.novel.llm.client.StreamHandler handler) {
        delegate.streamComplete(request, handler);
    }

    /** 缓存键：任务/provider/model/指纹/温度 */
    private String cacheKey(LlmRequest request) {
        return request.getTaskType().name() + "|" + provider + "|" + model + "|"
                + fingerprint(request) + "|" + request.getTemperature();
    }

    /** 失效域：projectId + chapterIndex（EventContext；章推进即整域失效） */
    private String scopeKey() {
        String pid = com.writing.novel.telemetry.EventContext.projectId();
        Integer chapter = com.writing.novel.telemetry.EventContext.chapter();
        return (pid == null ? "-" : pid) + ":" + (chapter == null ? "-" : chapter);
    }

    private String fingerprint(LlmRequest request) {
        StringBuilder sb = new StringBuilder();
        sb.append(request.getTaskType().name()).append('\u0000');
        sb.append(normalizeVolatileMetadata(request.getSystemPrompt())).append('\u0000');
        sb.append(request.getUserMessage() == null ? "" : request.getUserMessage());
        if (request.getHistory() != null) {
            for (var m : request.getHistory()) {
                sb.append('\u0000').append(m.role()).append(':')
                        .append(normalizeVolatileMetadata(m.content()));
            }
        }
        return sha256Hex(sb.toString()).substring(0, 16);
    }

    private static String normalizeVolatileMetadata(String text) {
        return UPDATED_AT_FIELD.matcher(text == null ? "" : text).replaceAll("updatedAt<volatile>");
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    int cacheSize() { return cache.size(); }
}
