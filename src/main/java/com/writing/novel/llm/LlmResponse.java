package com.writing.novel.llm;

/**
 * LLM 调用响应。包含生成正文与 token 用量统计。
 */
public class LlmResponse {

    private final String content;
    private final int promptTokens;
    private final int completionTokens;

    public LlmResponse(String content, int promptTokens, int completionTokens) {
        this.content = content;
        this.promptTokens = promptTokens;
        this.completionTokens = completionTokens;
    }

    public String getContent() { return content; }
    public int getPromptTokens() { return promptTokens; }
    public int getCompletionTokens() { return completionTokens; }
    public int getTotalTokens() { return promptTokens + completionTokens; }
}
