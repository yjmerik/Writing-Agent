package com.writing.novel.llm.gateway;

import com.writing.novel.llm.LlmGateway;
import com.writing.novel.llm.LlmRequest;
import com.writing.novel.llm.LlmResponse;
import com.writing.novel.llm.client.LlmClient;
import com.writing.novel.llm.client.StreamHandler;
import com.writing.novel.llm.config.LlmProperties;
import com.writing.novel.llm.config.ModelConfig;

/**
 * 默认 LLM 网关实现。
 * 职责：按 taskType 查 ModelConfig → 克隆 → 合并请求级覆盖参数 → 委托 LlmClient 执行。
 */
public class DefaultLlmGateway implements LlmGateway {

    private final LlmProperties properties;
    private final LlmClient client;

    public DefaultLlmGateway(LlmProperties properties, LlmClient client) {
        this.properties = properties;
        this.client = client;
    }

    @Override
    public LlmResponse complete(LlmRequest request) {
        ModelConfig config = resolveConfig(request);
        return client.complete(config, request);
    }

    @Override
    public void streamComplete(LlmRequest request, StreamHandler handler) {
        ModelConfig config = resolveConfig(request);
        client.streamComplete(config, request, handler);
    }

    /**
     * 解析模型配置：按任务类型查 → 克隆 → 合并请求级覆盖（temperature/maxTokens）。
     * 克隆避免修改共享的配置实例。
     */
    private ModelConfig resolveConfig(LlmRequest request) {
        ModelConfig base = properties.forTaskType(request.getTaskType().name());
        ModelConfig resolved = cloneConfig(base);

        // 始终用请求值覆盖（请求值即最终意图）
        resolved.setTemperature(request.getTemperature());
        if (request.getMaxTokens() > 0) {
            resolved.setMaxTokens(request.getMaxTokens());
        }

        return resolved;
    }

    private ModelConfig cloneConfig(ModelConfig src) {
        ModelConfig c = new ModelConfig();
        c.setBaseUrl(src.getBaseUrl());
        c.setApiKey(src.getApiKey());
        c.setModel(src.getModel());
        c.setTemperature(src.getTemperature());
        c.setMaxTokens(src.getMaxTokens());
        c.setStreaming(src.isStreaming());
        return c;
    }
}
