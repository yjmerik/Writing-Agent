package com.writing.novel.llm.client;

/** OpenAI 兼容格式的 chat 消息（role + content） */
public record ChatMessage(String role, String content) {}
