package com.writing.novel.llm;

/**
 * LLM 调用响应。包含生成正文与 token 用量统计。
 * NCS-065: 携带 finishReason——"length" 表示输出被 max_tokens 截断，调用方可据此分类处理；
 * 旧构造路径与回放 fixture 缺省为 "stop"。
 */
public class LlmResponse {

    /** Pricing snapshot contains no endpoint, credentials or request payload. */
    public record Execution(String providerId, String model, double inputPrice, double outputPrice) {}
    private final Execution execution;
    public Execution getExecution() { return execution; }
    public LlmResponse withExecution(Execution execution) {
        return new LlmResponse(content, promptTokens, completionTokens, finishReason, execution);
    }

    private final String content;
    private final int promptTokens;
    private final int completionTokens;
    private final String finishReason;

    public LlmResponse(String content, int promptTokens, int completionTokens) {
        this(content, promptTokens, completionTokens, "stop", null);
    }

    public LlmResponse(String content, int promptTokens, int completionTokens, Execution execution) {
        this(content, promptTokens, completionTokens, "stop", execution);
    }

    public LlmResponse(String content, int promptTokens, int completionTokens,
                       String finishReason, Execution execution) {
        this.execution = execution;
        this.content = content;
        this.promptTokens = promptTokens;
        this.completionTokens = completionTokens;
        this.finishReason = finishReason == null || finishReason.isBlank() ? "stop" : finishReason;
    }

    public String getContent() { return content; }
    public int getPromptTokens() { return promptTokens; }
    public int getCompletionTokens() { return completionTokens; }
    public int getTotalTokens() { return promptTokens + completionTokens; }

    /** "stop" 正常结束；"length" 输出截断；其余透传 provider 语义（如 content_filter） */
    public String getFinishReason() { return finishReason; }
    public boolean isTruncated() { return "length".equals(finishReason); }
}
