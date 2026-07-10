package com.writing.novel.llm.config;

/**
 * 单个模型配置。对应 yml 中 llm.models.<TASK_TYPE> 下的配置。
 */
public class ModelConfig {

    private String baseUrl;
    private String apiKey;
    private String model;
    private double temperature = 0.7;
    private int maxTokens = 4096;
    /** 是否启用流式输出 */
    private boolean streaming = true;

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public double getTemperature() { return temperature; }
    public void setTemperature(double temperature) { this.temperature = temperature; }
    public int getMaxTokens() { return maxTokens; }
    public void setMaxTokens(int maxTokens) { this.maxTokens = maxTokens; }
    public boolean isStreaming() { return streaming; }
    public void setStreaming(boolean streaming) { this.streaming = streaming; }
}
