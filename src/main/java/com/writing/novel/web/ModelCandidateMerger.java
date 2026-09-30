package com.writing.novel.web;

import com.writing.novel.llm.config.LlmProtocol;
import com.writing.novel.llm.config.ModelConfig;

/**
 * NCS-160（review 整改）：模型配置候选合并的共享 helper——
 * ModelConfigController（路由覆盖）与 ModelLibraryController（模型库保存）
 * 此前各写一份逐字段 null-keep 合并与协议解析，措辞/条件易漂移；统一到此处。
 * 语义：请求字段为 null/空白时保留 base 值；apiKey 空白保留原值（不误清密钥）。
 */
final class ModelCandidateMerger {

    private ModelCandidateMerger() {
    }

    /** 合并请求字段到 base（base 须为调用方私有副本，不得是共享默认配置） */
    static void merge(ModelConfig base, String protocol, String baseUrl, String model,
                      String apiKey, Double temperature, Integer maxTokens, Integer contextWindow,
                      Boolean streaming, Integer requestTimeoutSeconds) {
        if (protocol != null && !protocol.isBlank()) base.setProtocol(parseProtocol(protocol));
        if (baseUrl != null) base.setBaseUrl(baseUrl.trim());
        if (model != null) base.setModel(model.trim());
        if (apiKey != null && !apiKey.isBlank()) base.setApiKey(apiKey.trim());
        if (temperature != null) base.setTemperature(temperature);
        if (maxTokens != null) base.setMaxTokens(maxTokens);
        if (contextWindow != null) base.setContextWindow(contextWindow);
        if (streaming != null) base.setStreaming(streaming);
        if (requestTimeoutSeconds != null) base.setRequestTimeoutSeconds(requestTimeoutSeconds);
    }

    static LlmProtocol parseProtocol(String value) {
        try {
            return LlmProtocol.valueOf(value.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("未知协议: " + value);
        }
    }
}
