package com.writing.novel.llm;

import com.writing.novel.llm.client.LlmClient;
import com.writing.novel.llm.config.ModelConfig;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * NCS-142：模型连通性探针——以候选配置发一次微型补全，供模型配置/模型库
 * 页面在保存或切换路由前验证连通与鉴权。探测强制单次尝试 + 短超时 +
 * 受限 maxTokens，不产生可观测成本；失败是正常结果（结构化返回，不抛 HTTP 错误）。
 *
 * 探针 prompt 硬编码豁免说明（AGENTS.md「Prompt 外置」）：本 prompt 是固定的
 * 机械验证语（"只回复一个字"），无变量、无题材差异，外置模板只会增加一跳间接；
 * NCS-160 review 结论：保留硬编码并在此注释豁免理由。
 */
public final class LlmProbe {

    private LlmProbe() {
    }

    /** NCS-160: 全字段防御性拷贝——run 不得突变调用方入参 */
    public static ModelConfig copyOf(ModelConfig src) {
        ModelConfig c = new ModelConfig();
        c.setBaseUrl(src.getBaseUrl());
        c.setApiKey(src.getApiKey());
        c.setModel(src.getModel());
        c.setProtocol(src.getProtocol());
        c.setTemperature(src.getTemperature());
        c.setMaxTokens(src.getMaxTokens());
        c.setStreaming(src.isStreaming());
        c.setContextWindow(src.getContextWindow());
        c.setRequestTimeoutSeconds(src.getRequestTimeoutSeconds());
        c.setSingleAttempt(src.isSingleAttempt());
        return c;
    }

    public static Map<String, Object> run(LlmClient client, ModelConfig candidate) {
        // NCS-160: 在拷贝上施加探测约束，调用方入参保持原值
        ModelConfig probe = copyOf(candidate);
        probe.setTemperature(0);
        // NCS-185: 64 tokens 对思考模型结构性必败（reasoning 普遍 100+ tokens）；512 仍属可忽略成本
        probe.setMaxTokens(Math.min(probe.getMaxTokens() <= 0 ? 32 : probe.getMaxTokens(), 512));
        probe.setRequestTimeoutSeconds(Math.min(Math.max(probe.getRequestTimeoutSeconds(), 1), 60));
        probe.setSingleAttempt(true);
        probe.setStreaming(false);
        long start = System.currentTimeMillis();
        try {
            LlmResponse response = client.complete(probe, LlmRequest.builder()
                    .taskType(LlmTaskType.GENERAL)
                    .systemPrompt("你是连通性探针。只回复一个字：通")
                    .userMessage("ping").maxTokens(probe.getMaxTokens()).temperature(0.0).build());
            String content = response.getContent() == null ? "" : response.getContent();
            String sample = content.substring(0, Math.min(content.length(), 40));
            Map<String, Object> ok = new LinkedHashMap<>();
            ok.put("ok", true);
            ok.put("model", candidate.getModel() == null ? "" : candidate.getModel());
            ok.put("latencyMs", System.currentTimeMillis() - start);
            ok.put("sample", sample);
            return ok;
        } catch (Exception e) {
            Map<String, Object> fail = new LinkedHashMap<>();
            fail.put("ok", false);
            fail.put("model", candidate.getModel() == null ? "" : candidate.getModel());
            fail.put("latencyMs", System.currentTimeMillis() - start);
            fail.put("error", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            return fail;
        }
    }
}
