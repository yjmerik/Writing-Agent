package com.writing.novel.llm;

import com.writing.novel.llm.client.StreamHandler;

/**
 * LLM 网关 — 业务层（Agent）调用 LLM 的唯一入口。
 * 内部按 LlmRequest.taskType 路由到对应模型配置，屏蔽多模型差异。
 */
public interface LlmGateway {

    /**
     * 同步调用，等待完整响应。
     */
    LlmResponse complete(LlmRequest request);

    /**
     * 流式调用，逐 token 回调。
     */
    void streamComplete(LlmRequest request, StreamHandler handler);
}
