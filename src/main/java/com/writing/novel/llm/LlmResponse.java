package com.writing.novel.llm;

/**
 * LLM 调用响应。包含生成正文与 token 用量统计。
 */
public class LlmResponse {

    /** Pricing snapshot contains no endpoint, credentials or request payload. */
    public record Execution(String providerId, String model, double inputPrice, double outputPrice) {}
    private final Execution execution;
    public Execution getExecution() { return execution; }
    public LlmResponse withExecution(Execution execution) {
        return new LlmResponse(content, promptTokens, completionTokens, execution);
    }

    private final String content;
    private final int promptTokens;
    private final int completionTokens;

    public LlmResponse(String content, int promptTokens, int completionTokens) {
        this(content, promptTokens, completionTokens, null);
    }

    public LlmResponse(String content, int promptTokens, int completionTokens, Execution execution) {
        this.execution = execution;
        this.content = content;
        this.promptTokens = promptTokens;
        this.completionTokens = completionTokens;
    }

    public String getContent() { return content; }
    public int getPromptTokens() { return promptTokens; }
    public int getCompletionTokens() { return completionTokens; }
    public int getTotalTokens() { return promptTokens + completionTokens; }
}
