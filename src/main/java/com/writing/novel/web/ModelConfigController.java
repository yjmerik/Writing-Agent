package com.writing.novel.web;

import com.writing.novel.llm.config.LlmModelConfigStore;
import com.writing.novel.llm.config.LlmProperties;
import com.writing.novel.llm.config.LlmProtocol;
import com.writing.novel.llm.config.ModelConfig;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * p43-01: 模型配置页面 REST API。
 * 展示 DEFAULT + 8 任务类型的有效模型配置（yml 基线 + 页面覆盖层），支持新增/修改/删除覆盖。
 * api_key 只返回掩码；PUT 空 api_key 表示保留原值。
 */
@RestController
@RequestMapping("/api/llm/models")
public class ModelConfigController {

    /** 展示顺序：默认模型 + 全部任务类型（NCS-066 起含 EXTRACTION） */
    private static final LinkedHashMap<String, String> SCOPES = new LinkedHashMap<>();
    static {
        SCOPES.put(LlmModelConfigStore.DEFAULT_SCOPE, "默认模型");
        SCOPES.put("WORLDBUILDING", "世界观构建");
        SCOPES.put("CHARACTER_DESIGN", "人物设计");
        SCOPES.put("PLOTTING", "情节规划");
        SCOPES.put("CHAPTER_WRITING", "章节写作");
        SCOPES.put("CONTINUITY_CHECK", "连续性检查");
        SCOPES.put("STYLE_GUARDIAN", "风格守护");
        SCOPES.put("MEMORY_SUMMARY", "记忆摘要");
        SCOPES.put("GENERAL", "通用任务");
        SCOPES.put("EXTRACTION", "提取任务");
    }

    private final LlmModelConfigStore store;

    public ModelConfigController(LlmModelConfigStore store) {
        this.store = store;
    }

    public record EntryView(String scope, String label, String source, String protocol, String baseUrl,
                            String model, String apiKeyMasked, boolean hasApiKey, double temperature,
                            int maxTokens, int contextWindow, boolean streaming, int requestTimeoutSeconds) {}

    public record ProtocolView(String value, String label) {}

    public record Overview(List<EntryView> entries, List<ProtocolView> protocols,
                           List<Map<String, String>> addableScopes) {}

    record UpdateRequest(String protocol, String baseUrl, String apiKey, String model,
                         Double temperature, Integer maxTokens, Integer contextWindow,
                         Boolean streaming, Integer requestTimeoutSeconds) {}

    /** 配置总览：所有范围的有效配置 + 可新增的范围（未显式配置的任务类型） */
    @GetMapping
    public Overview overview() {
        List<EntryView> entries = new ArrayList<>();
        List<Map<String, String>> addable = new ArrayList<>();
        SCOPES.forEach((scope, label) -> {
            entries.add(entryView(scope, label));
            if (!LlmModelConfigStore.DEFAULT_SCOPE.equals(scope)
                    && !"PAGE".equals(sourceOf(scope)) && !"YML".equals(sourceOf(scope))) {
                addable.add(Map.of("scope", scope, "label", label));
            }
        });
        List<ProtocolView> protocols = List.of(
                new ProtocolView(LlmProtocol.OPENAI_CHAT_COMPLETIONS.name(), "OpenAI Chat Completions"),
                new ProtocolView(LlmProtocol.OPENAI_RESPONSES.name(), "OpenAI Responses API"),
                new ProtocolView(LlmProtocol.ANTHROPIC_MESSAGES.name(), "Anthropic Messages API"));
        return new Overview(entries, protocols, addable);
    }

    /** 新增/修改覆盖：未提供的字段保留当前生效值；空 api_key 保留原值 */
    @PutMapping("/{scope}")
    public EntryView update(@PathVariable String scope, @RequestBody UpdateRequest body) {
        requireValidScope(scope);
        ModelConfig current = store.effectiveForScope(scope);
        ModelConfig merged = LlmModelConfigStore.copy(current);
        if (body.protocol() != null && !body.protocol().isBlank()) {
            merged.setProtocol(parseProtocol(body.protocol()));
        }
        if (body.baseUrl() != null) merged.setBaseUrl(body.baseUrl().trim());
        if (body.model() != null) merged.setModel(body.model().trim());
        // api_key：空值或原样掩码 = 保留现有 key
        if (body.apiKey() != null && !body.apiKey().isBlank()
                && !body.apiKey().equals(mask(current.getApiKey()))) {
            merged.setApiKey(body.apiKey().trim());
        }
        if (body.temperature() != null) merged.setTemperature(body.temperature());
        if (body.maxTokens() != null) merged.setMaxTokens(body.maxTokens());
        if (body.contextWindow() != null) merged.setContextWindow(body.contextWindow());
        if (body.streaming() != null) merged.setStreaming(body.streaming());
        if (body.requestTimeoutSeconds() != null) merged.setRequestTimeoutSeconds(body.requestTimeoutSeconds());
        validate(merged);
        store.save(scope, merged);
        return entryView(scope, SCOPES.get(scope));
    }

    /** 删除覆盖：回退 yml 基线（未显式配置的任务类型恢复跟随默认） */
    @DeleteMapping("/{scope}")
    public EntryView delete(@PathVariable String scope) {
        requireValidScope(scope);
        store.delete(scope);
        return entryView(scope, SCOPES.get(scope));
    }

    private EntryView entryView(String scope, String label) {
        ModelConfig cfg = store.effectiveForScope(scope);
        String masked = mask(cfg.getApiKey());
        return new EntryView(scope, label, sourceOf(scope),
                cfg.getProtocol() == null ? LlmProtocol.OPENAI_CHAT_COMPLETIONS.name() : cfg.getProtocol().name(),
                cfg.getBaseUrl(), cfg.getModel(), masked, !masked.isEmpty(), cfg.getTemperature(),
                cfg.getMaxTokens(), cfg.getContextWindow(), cfg.isStreaming(), cfg.getRequestTimeoutSeconds());
    }

    /** PAGE=页面覆盖（可删除）；YML=配置文件显式配置；FALLBACK=未显式配置、跟随默认 */
    private String sourceOf(String scope) {
        if (store.hasOverride(scope)) return "PAGE";
        if (LlmModelConfigStore.DEFAULT_SCOPE.equals(scope)) return "YML";
        return store.hasBaseline(scope) ? "YML" : "FALLBACK";
    }

    private static void requireValidScope(String scope) {
        if (!LlmModelConfigStore.isValidScope(scope)) {
            throw new IllegalArgumentException("未知的配置范围: " + scope + "（有效值: DEFAULT 或任务类型名）");
        }
    }

    private static LlmProtocol parseProtocol(String value) {
        try {
            return LlmProtocol.valueOf(value.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("未知协议: " + value);
        }
    }

    /** 与 LlmProperties.validateProvider 同语义的入参校验（页面保存路径） */
    private static void validate(ModelConfig cfg) {
        try {
            var uri = java.net.URI.create(cfg.getBaseUrl() == null ? "" : cfg.getBaseUrl());
            if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                    || uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null
                    || uri.getFragment() != null) throw new IllegalArgumentException();
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("base_url 必须是合法的 http(s) 地址: " + cfg.getBaseUrl());
        }
        if (cfg.getModel() == null || cfg.getModel().isBlank()) {
            throw new IllegalArgumentException("model_id 不能为空");
        }
        if (!Double.isFinite(cfg.getTemperature()) || cfg.getTemperature() < 0 || cfg.getTemperature() > 2) {
            throw new IllegalArgumentException("temperature 必须在 0~2 之间");
        }
        if (cfg.getMaxTokens() <= 0) throw new IllegalArgumentException("max_tokens 必须大于 0");
        if (cfg.getContextWindow() < 0
                || (cfg.getContextWindow() > 0 && cfg.getContextWindow() <= cfg.getMaxTokens())) {
            throw new IllegalArgumentException("context_window 必须大于 max_tokens（0 表示未配置）");
        }
        if (cfg.getRequestTimeoutSeconds() <= 0 || cfg.getRequestTimeoutSeconds() > 86400) {
            throw new IllegalArgumentException("request_timeout_seconds 必须在 1~86400 之间");
        }
        if (!LlmProperties.isLocalBaseUrl(cfg.getBaseUrl())
                && (cfg.getApiKey() == null || cfg.getApiKey().isBlank())) {
            throw new IllegalArgumentException("远程端点必须配置 API Key（本地/内网端点可留空）");
        }
    }

    /** api_key 掩码：仅前 4 后 4 可见；过短直接全遮（ASCII 省略号，避免编码歧义） */
    static String mask(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) return "";
        if (apiKey.length() <= 8) return "****";
        return apiKey.substring(0, 4) + "..." + apiKey.substring(apiKey.length() - 4);
    }
}
