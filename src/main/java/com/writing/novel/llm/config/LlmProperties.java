package com.writing.novel.llm.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

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
        if (defaultModel != null) {
            requireKey("default-model", defaultModel);
        }
        models.forEach((task, cfg) -> requireKey(task, cfg));
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
        ModelConfig cfg = models.get(taskType);
        return cfg != null ? cfg : defaultModel;
    }
}
