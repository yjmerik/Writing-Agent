package com.writing.novel.llm.config;

/**
 * 单个模型配置。对应 yml 中 llm.models.<TASK_TYPE> 下的配置。
 */
public class ModelConfig {

    private int requestTimeoutSeconds = 180;
    // Resolved by the gateway for named routes; legacy calls retain their retry policy.
    private boolean singleAttempt;
    /** NCS-129: 传输层重试次数上限（含首次）；0 = 用默认 3 */
    private int retryAttempts = 0;
    /** NCS-129: 退避基数毫秒（0 = 用默认）；退避 = 基数 × 2^attempt × (0.5~1.5 jitter) */
    private int backoffBaseMs = 0;
    public int getRequestTimeoutSeconds() { return requestTimeoutSeconds; }
    public void setRequestTimeoutSeconds(int value) { requestTimeoutSeconds = value; }
    public boolean isSingleAttempt() { return singleAttempt; }
    public void setSingleAttempt(boolean value) { singleAttempt = value; }
    public int getRetryAttempts() { return retryAttempts; }
    public void setRetryAttempts(int v) { retryAttempts = v; }
    public int getBackoffBaseMs() { return backoffBaseMs; }
    public void setBackoffBaseMs(int v) { backoffBaseMs = v; }

    /** NCS-129: 生效重试次数（含首次）——0/负值回退默认 3 */
    public int effectiveRetryAttempts() { return retryAttempts > 0 ? retryAttempts : 3; }

    /** NCS-129: 生效退避基数 */
    public int effectiveBackoffBaseMs() { return backoffBaseMs > 0 ? backoffBaseMs : 1000; }

    private String baseUrl;
    private String apiKey;
    private String model;
    /** p43-01: API 协议（chat/completions | responses | anthropic messages），默认 OpenAI 兼容 */
    private LlmProtocol protocol = LlmProtocol.OPENAI_CHAT_COMPLETIONS;
    private double temperature = 0.7;
    private int maxTokens = 4096;
    /** 模型上下文窗口（输入+输出，token）。0 表示未配置，回退默认预算（mc-p2-03） */
    private int contextWindow = 0;
    /** 是否启用流式输出 */
    private boolean streaming = true;
    /** 模型级风格指引（自动拼接到 systemPrompt 末尾，用于约束 LLM 创作倾向） */
    private String styleGuide;
    /** p10-w1-04: 输入定价（元/千 token），默认 0.001（DeepSeek 参考价） */
    private double inputPriceYuanPerKTokens = 0.001;
    /** p10-w1-04: 输出定价（元/千 token），默认 0.002（DeepSeek 参考价） */
    private double outputPriceYuanPerKTokens = 0.002;

    /** 思考模式开关（GLM 系列 thinking 参数）：false 时请求体注入 thinking.type=disabled，
     *  消除 reasoning_content 的分钟级延迟与输出 token 消耗（写作流水线 5000+ 调用必需） */
    private boolean thinkingEnabled = true;
    /** NCS-066: 关思考请求参数形态——AUTO 按端点推断（aliyuncs/dashscope→DASHSCOPE，其余→GLM），
     *  显式指定则不再推断。默认 AUTO 保持现状兼容。 */
    private ThinkingParamStyle thinkingParamStyle = ThinkingParamStyle.AUTO;

    /** NCS-066: 关思考参数的 provider 形态 */
    public enum ThinkingParamStyle { AUTO, GLM, DASHSCOPE }

    public ThinkingParamStyle getThinkingParamStyle() {
        return thinkingParamStyle == null ? ThinkingParamStyle.AUTO : thinkingParamStyle;
    }
    public void setThinkingParamStyle(ThinkingParamStyle thinkingParamStyle) { this.thinkingParamStyle = thinkingParamStyle; }

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public LlmProtocol getProtocol() { return protocol; }
    public void setProtocol(LlmProtocol protocol) { this.protocol = protocol == null ? LlmProtocol.OPENAI_CHAT_COMPLETIONS : protocol; }
    public double getTemperature() { return temperature; }
    public void setTemperature(double temperature) { this.temperature = temperature; }
    public int getMaxTokens() { return maxTokens; }
    public void setMaxTokens(int maxTokens) { this.maxTokens = maxTokens; }
    public int getContextWindow() { return contextWindow; }
    public void setContextWindow(int contextWindow) { this.contextWindow = contextWindow; }
    public boolean isStreaming() { return streaming; }
    public void setStreaming(boolean streaming) { this.streaming = streaming; }
    public String getStyleGuide() { return styleGuide; }
    public void setStyleGuide(String styleGuide) { this.styleGuide = styleGuide; }
    public double getInputPriceYuanPerKTokens() { return inputPriceYuanPerKTokens; }
    public void setInputPriceYuanPerKTokens(double v) { this.inputPriceYuanPerKTokens = v; }
    public double getOutputPriceYuanPerKTokens() { return outputPriceYuanPerKTokens; }

    public boolean isThinkingEnabled() { return thinkingEnabled; }

    public void setThinkingEnabled(boolean thinkingEnabled) { this.thinkingEnabled = thinkingEnabled; }
    public void setOutputPriceYuanPerKTokens(double v) { this.outputPriceYuanPerKTokens = v; }
}
