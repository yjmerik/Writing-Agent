package com.writing.novel.llm.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.*;
import com.writing.novel.llm.LlmTaskType;

/**
 * LLM 模型配置绑定。yml 中 llm.models.<TASK_TYPE> 映射到 ModelConfig。
 * 默认配置在 default-model 下，未配置的任务类型回退到默认。
 */
@Component
@ConfigurationProperties(prefix = "llm")
public class LlmProperties {

    /** key = 任务类型名（如 CHAPTER_WRITING），value = 该任务的模型配置 */
    private Map<String, ModelConfig> models = new HashMap<>();

    /** 默认模型配置，未显式配置的任务类型回退到此 */
    private ModelConfig defaultModel = new ModelConfig();

    private Map<String, ModelConfig> providers = new HashMap<>();
    private Routing routing = new Routing();
    private Fallback fallback = new Fallback();

    public Map<String, ModelConfig> getProviders() { return providers; }
    public void setProviders(Map<String, ModelConfig> providers) { this.providers = providers; }
    public Routing getRouting() { return routing; }
    public void setRouting(Routing routing) { this.routing = routing; }
    public Fallback getFallback() { return fallback; }
    public void setFallback(Fallback fallback) { this.fallback = fallback; }

    public static class Routing {
        private List<String> defaultProviders = new ArrayList<>();
        private Map<String, List<String>> tasks = new HashMap<>();
        public List<String> getDefaultProviders() { return defaultProviders; }
        public void setDefaultProviders(List<String> value) { defaultProviders = value; }
        public Map<String, List<String>> getTasks() { return tasks; }
        public void setTasks(Map<String, List<String>> value) { tasks = value; }
    }

    public static class Fallback {
        private long cooldownSeconds = 60;
        public long getCooldownSeconds() { return cooldownSeconds; }
        public void setCooldownSeconds(long value) { cooldownSeconds = value; }
    }

    public List<String> providerIdsForTask(String task) {
        return routing.tasks.getOrDefault(task, routing.defaultProviders);
    }

    /** NCS-108: 是否 legacy llm.models 形态（无 providers/routing）——弃用告警与兼容路径判定 */
    public boolean isLegacyRouting() {
        return routing.defaultProviders.isEmpty() && routing.tasks.isEmpty();
    }

    /** mc-p0-04: replay 模式标志——replay 时不校验 key */
    private boolean replayMode = false;

    public boolean isReplayMode() { return replayMode; }
    public void setReplayMode(boolean replayMode) { this.replayMode = replayMode; }
    public Map<String, ModelConfig> getModels() { return models; }
    public void setModels(Map<String, ModelConfig> models) { this.models = models; }
    public ModelConfig getDefaultModel() { return defaultModel; }
    public void setDefaultModel(ModelConfig defaultModel) { this.defaultModel = defaultModel; }

    /**
     * mc-p0-04: 启动时校验必填凭据——缺失则 fail-fast。
     * 语义：仅对远程（非本地）端点要求 api-key；本地端点（localhost/127.0.0.1 等）无需密钥。
     * 校验范围：defaultModel + 所有任务类型的模型配置，而不是只看默认模型。
     * replay 模式下跳过（录制回放不依赖真实 key）。
     */
    public void validateKeys() {
        if (replayMode) return;
        if (fallback.cooldownSeconds <= 0 || fallback.cooldownSeconds > 86400) {
            throw new IllegalStateException("llm.fallback.cooldown-seconds must be between 1 and 86400");
        }
        routing.tasks.forEach((task, ids) -> {
            try { LlmTaskType.valueOf(task); }
            catch (IllegalArgumentException e) { throw new IllegalStateException("Unknown LLM task: " + task); }
            if (ids == null || ids.isEmpty()) throw new IllegalStateException("Empty LLM route: " + task);
        });
        boolean legacy = isLegacyRouting();
        if (legacy) {
            // NCS-108: legacy llm.models 弃用——单模型直连无熔断/failover，迁移到 providers/routing
            System.getLogger(LlmProperties.class.getName())
                    .log(System.Logger.Level.WARNING,
                            "llm.models（legacy 单模型直连）已弃用：无熔断与 provider fallback。"
                                    + "请迁移到 llm.providers + llm.routing（见 docs/llm-provider-fallback.md）");
            if (defaultModel != null) requireKey("default-model", defaultModel);
            models.forEach(this::requireKey);
            return;
        }
        for (LlmTaskType task : LlmTaskType.values()) {
            List<String> ids = providerIdsForTask(task.name());
            if (ids.isEmpty()) {
                requireKey(task.name(), models.getOrDefault(task.name(), defaultModel));
                continue;
            }
            Set<String> seen = new HashSet<>();
            int primaryWindow = 0;
            int primaryInputSpace = 0;
            for (String id : ids) {
                if (id == null || !id.matches("[A-Za-z0-9_-]{1,64}") || !seen.add(id)) {
                    throw new IllegalStateException("Invalid or duplicate provider ID in route " + task);
                }
                ModelConfig cfg = providers.get(id);
                if (cfg == null) throw new IllegalStateException("Unknown LLM provider: " + id);
                validateProvider(id, cfg);
                if (primaryWindow == 0) {
                    primaryWindow = cfg.getContextWindow();
                    primaryInputSpace = cfg.getContextWindow() - cfg.getMaxTokens();
                }
                if (cfg.getContextWindow() < primaryWindow
                        || cfg.getContextWindow() - cfg.getMaxTokens() < primaryInputSpace) {
                    throw new IllegalStateException("Fallback context-window or input space smaller than primary: " + id);
                }
            }
        }
    }

    private void validateProvider(String id, ModelConfig cfg) {
        try {
            var uri = java.net.URI.create(cfg.getBaseUrl());
            if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                    || uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null
                    || uri.getFragment() != null) throw new IllegalArgumentException();
        } catch (RuntimeException e) {
            throw new IllegalStateException("Invalid provider base-url: " + id);
        }
        if (cfg.getModel() == null || cfg.getModel().isBlank() || cfg.getContextWindow() <= 0
                || cfg.getMaxTokens() <= 0 || cfg.getMaxTokens() >= cfg.getContextWindow()
                || cfg.getRequestTimeoutSeconds() <= 0 || cfg.getRequestTimeoutSeconds() > 86400
                || !Double.isFinite(cfg.getTemperature()) || cfg.getTemperature() < 0
                || !Double.isFinite(cfg.getInputPriceYuanPerKTokens()) || cfg.getInputPriceYuanPerKTokens() < 0
                || !Double.isFinite(cfg.getOutputPriceYuanPerKTokens()) || cfg.getOutputPriceYuanPerKTokens() < 0) {
            throw new IllegalStateException("Invalid model, window, tokens, timeout or price for provider: " + id);
        }
        if (!isLocalBaseUrl(cfg.getBaseUrl()) && (cfg.getApiKey() == null || cfg.getApiKey().isBlank())) {
            throw new IllegalStateException("Missing api-key for provider: " + id);
        }
    }

    private void requireKey(String task, ModelConfig cfg) {
        if (isLocalBaseUrl(cfg.getBaseUrl())) return; // 本地端点免密钥
        if (cfg.getApiKey() == null || cfg.getApiKey().isBlank()) {
            throw new IllegalStateException(
                "LLM api-key 未配置（任务类型 " + task + "，端点 " + cfg.getBaseUrl() + "）。" +
                "请设置环境变量 DEEPSEEK_API_KEY / LOCAL_LLM_API_KEY 等，或使用 replay 模式（llm.replay.mode=replay）。");
        }
    }

    /** 本地端点判定：localhost / 127.x / ::1 / 0.0.0.0 / *.local 免密钥 */
    public static boolean isLocalBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) return false;
        try {
            String host = java.net.URI.create(baseUrl).getHost();
            if (host == null) return false;
            String h = host.toLowerCase();
            return h.equals("localhost") || h.equals("::1") || h.equals("0.0.0.0")
                    || h.startsWith("127.") || h.startsWith("192.168.") || h.startsWith("10.")
                    || h.endsWith(".local");
        } catch (IllegalArgumentException e) {
            return false; // 无法解析的端点按远程处理，要求密钥
        }
    }

    /**
     * 按任务类型获取模型配置。若该类型未配置，回退到 defaultModel。
     */
    public ModelConfig forTaskType(String taskType) {
        List<String> ids = providerIdsForTask(taskType);
        if (!ids.isEmpty()) return providers.get(ids.get(0));
        ModelConfig cfg = models.get(taskType);
        return cfg != null ? cfg : defaultModel;
    }
}
