package com.writing.novel.llm.client;

import com.writing.novel.llm.LlmRequest;
import com.writing.novel.llm.LlmResponse;
import com.writing.novel.llm.config.LlmProtocol;
import com.writing.novel.llm.config.ModelConfig;

/**
 * p43-01: 按模型配置的 protocol 分发到对应协议客户端。
 * OPENAI_CHAT_COMPLETIONS（默认）委托既有 OpenAiCompatibleClient——存量配置零行为变化。
 */
public class ProtocolRoutingLlmClient implements LlmClient {

    private final OpenAiCompatibleClient chatCompletions;
    private final OpenAiResponsesClient responses;
    private final AnthropicMessagesClient anthropic;

    public ProtocolRoutingLlmClient() {
        this(new OpenAiCompatibleClient(), new OpenAiResponsesClient(), new AnthropicMessagesClient());
    }

    public ProtocolRoutingLlmClient(OpenAiCompatibleClient chatCompletions,
                                    OpenAiResponsesClient responses,
                                    AnthropicMessagesClient anthropic) {
        this.chatCompletions = chatCompletions;
        this.responses = responses;
        this.anthropic = anthropic;
    }

    @Override
    public LlmResponse complete(ModelConfig config, LlmRequest request) {
        return target(config).complete(config, request);
    }

    @Override
    public void streamComplete(ModelConfig config, LlmRequest request, StreamHandler handler) {
        target(config).streamComplete(config, request, handler);
    }

    private LlmClient target(ModelConfig config) {
        LlmProtocol protocol = config.getProtocol() == null ? LlmProtocol.OPENAI_CHAT_COMPLETIONS : config.getProtocol();
        return switch (protocol) {
            case OPENAI_CHAT_COMPLETIONS -> chatCompletions;
            case OPENAI_RESPONSES -> responses;
            case ANTHROPIC_MESSAGES -> anthropic;
        };
    }
}
