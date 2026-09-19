package com.writing.novel.llm.config;

/**
 * LLM API 协议类型。p43-01: 模型配置页面支持的三种协议。
 * 默认 OPENAI_CHAT_COMPLETIONS——与既有配置零行为差异。
 */
public enum LlmProtocol {
    /** OpenAI Chat Completions：POST {base}/chat/completions，Authorization: Bearer（现有默认） */
    OPENAI_CHAT_COMPLETIONS,
    /** OpenAI Responses API：POST {base}/responses，Authorization: Bearer */
    OPENAI_RESPONSES,
    /** Anthropic Messages API：POST {base}/v1/messages，x-api-key + anthropic-version */
    ANTHROPIC_MESSAGES
}
