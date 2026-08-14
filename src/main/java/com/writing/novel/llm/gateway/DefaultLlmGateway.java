package com.writing.novel.llm.gateway;

import com.writing.novel.llm.LlmGateway;
import com.writing.novel.llm.LlmRequest;
import com.writing.novel.llm.LlmResponse;
import com.writing.novel.llm.client.LlmClient;
import com.writing.novel.llm.client.RateLimitException;
import com.writing.novel.llm.client.StreamHandler;
import com.writing.novel.llm.config.LlmProperties;
import com.writing.novel.llm.config.ModelConfig;

/**
 * 默认 LLM 网关实现。
 * 职责：按 taskType 查 ModelConfig → 克隆 → 合并请求级覆盖参数 → 委托 LlmClient 执行。
 * p10-w1-05: 主模型 429 限流时自动降级到 defaultModel（fallback）重试一次。
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
        try {
            return client.complete(config, request);
        } catch (RateLimitException e) {
            // p10-w1-05: 主模型限流 → 降级到 fallback（defaultModel），若配置了不同端点则重试一次
            ModelConfig fallback = resolveFallbackConfig(request);
            if (fallback != null) {
                return client.complete(fallback, request);
            }
            throw e; // 无可用 fallback，向上抛限流异常
        }
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
        // maxTokens 取请求值与配置值的较大者：
        // 推理模型（如 GLM-5.2）在 reasoning_content 上消耗大量 token，
        // Agent 写死的默认值（如 2000）可能不够，配置层（yml）可设更大值兜底。
        int reqMax = request.getMaxTokens();
        int cfgMax = base.getMaxTokens();
        if (reqMax > 0 && cfgMax > 0) {
            resolved.setMaxTokens(Math.max(reqMax, cfgMax));
        } else if (reqMax > 0) {
            resolved.setMaxTokens(reqMax);
        }

        return resolved;
    }

    /**
     * p10-w1-05: 解析 fallback 模型配置（defaultModel）。
     * 仅当 defaultModel 配置了与主模型不同的端点（baseUrl/model）时返回，避免降级到同一个限流模型。
     * 返回 null 表示无可用 fallback，调用方将向上抛 RateLimitException。
     */
    private ModelConfig resolveFallbackConfig(LlmRequest request) {
        ModelConfig def = properties.getDefaultModel();
        if (def == null || def.getBaseUrl() == null || def.getBaseUrl().isBlank()
                || def.getModel() == null || def.getModel().isBlank()) {
            return null;
        }
        ModelConfig primary = properties.forTaskType(request.getTaskType().name());
        // 与主模型相同则不降级（避免无限循环）
        if (primary != null && def.getBaseUrl().equals(primary.getBaseUrl())
                && def.getModel().equals(primary.getModel())) {
            return null;
        }
        ModelConfig resolved = cloneConfig(def);
        resolved.setTemperature(request.getTemperature());
        int reqMax = request.getMaxTokens();
        if (reqMax > 0) resolved.setMaxTokens(reqMax);
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
