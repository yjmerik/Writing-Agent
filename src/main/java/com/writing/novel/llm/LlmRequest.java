package com.writing.novel.llm;

import com.writing.novel.llm.client.ChatMessage;

import java.util.ArrayList;
import java.util.List;

/**
 * LLM 调用请求。Builder 模式构建。
 * taskType 决定路由；systemPrompt + userMessage + history 组成完整上下文。
 */
public class LlmRequest {

    private final LlmTaskType taskType;
    private final String systemPrompt;
    private final String userMessage;
    private final List<ChatMessage> history;
    private final int maxTokens;
    private final double temperature;

    private LlmRequest(Builder b) {
        this.taskType = b.taskType;
        this.systemPrompt = b.systemPrompt;
        this.userMessage = b.userMessage;
        this.history = b.history;
        this.maxTokens = b.maxTokens;
        this.temperature = b.temperature;
    }

    public static Builder builder() { return new Builder(); }

    public LlmTaskType getTaskType() { return taskType; }
    public String getSystemPrompt() { return systemPrompt; }
    public String getUserMessage() { return userMessage; }
    public List<ChatMessage> getHistory() { return history; }
    public int getMaxTokens() { return maxTokens; }
    public double getTemperature() { return temperature; }

    public static class Builder {
        private LlmTaskType taskType = LlmTaskType.GENERAL;
        private String systemPrompt;
        private String userMessage;
        private List<ChatMessage> history = new ArrayList<>();
        // 0 作为「未显式设置」的哨兵值；网关据此决定是否用请求值覆盖配置默认值。
        private int maxTokens = 0;
        private double temperature = 0.7;

        public Builder taskType(LlmTaskType t) { this.taskType = t; return this; }
        public Builder systemPrompt(String s) { this.systemPrompt = s; return this; }
        public Builder userMessage(String u) { this.userMessage = u; return this; }
        public Builder history(List<ChatMessage> h) { this.history = h; return this; }
        public Builder maxTokens(int m) { this.maxTokens = m; return this; }
        public Builder temperature(double t) { this.temperature = t; return this; }

        public LlmRequest build() {
            return new LlmRequest(this);
        }
    }
}
