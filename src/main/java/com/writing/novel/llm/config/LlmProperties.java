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

    public Map<String, ModelConfig> getModels() { return models; }
    public void setModels(Map<String, ModelConfig> models) { this.models = models; }
    public ModelConfig getDefaultModel() { return defaultModel; }
    public void setDefaultModel(ModelConfig defaultModel) { this.defaultModel = defaultModel; }

    /**
     * 按任务类型获取模型配置。若该类型未配置，回退到 defaultModel。
     */
    public ModelConfig forTaskType(String taskType) {
        ModelConfig cfg = models.get(taskType);
        return cfg != null ? cfg : defaultModel;
    }
}
