package com.writing.novel.llm.client;

import com.writing.novel.llm.LlmRequest;
import com.writing.novel.llm.LlmResponse;
import com.writing.novel.llm.config.ModelConfig;

/**
 * LLM HTTP 调用 SPI。屏蔽具体的 HTTP 协议与厂商差异。
 * MVP 实现：OpenAiCompatibleClient（OpenAI 兼容格式）。
 */
public interface LlmClient {

    /**
     * 非流式（同步）调用。等待完整响应后返回。
     */
    LlmResponse complete(ModelConfig config, LlmRequest request);

    /**
     * 流式调用。逐 token 回调 StreamHandler。
     */
    void streamComplete(ModelConfig config, LlmRequest request, StreamHandler handler);
}
