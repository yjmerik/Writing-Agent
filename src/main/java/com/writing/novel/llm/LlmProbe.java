package com.writing.novel.llm;

import com.writing.novel.llm.client.LlmClient;
import com.writing.novel.llm.config.ModelConfig;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * NCS-142：模型连通性探针——以候选配置发一次微型补全，供模型配置/模型库
 * 页面在保存或切换路由前验证连通与鉴权。探测强制单次尝试 + 短超时 +
 * 受限 maxTokens，不产生可观测成本；失败是正常结果（结构化返回，不抛 HTTP 错误）。
 */
public final class LlmProbe {

    private LlmProbe() {
    }

    public static Map<String, Object> run(LlmClient client, ModelConfig candidate) {
        final String model = candidate.getModel() == null ? "" : candidate.getModel();
        ModelConfig probe = candidate;
        probe.setTemperature(0);
        probe.setMaxTokens(Math.min(probe.getMaxTokens() <= 0 ? 32 : probe.getMaxTokens(), 64));
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
            ok.put("model", model);
            ok.put("latencyMs", System.currentTimeMillis() - start);
            ok.put("sample", sample);
            return ok;
        } catch (Exception e) {
            Map<String, Object> fail = new LinkedHashMap<>();
            fail.put("ok", false);
            fail.put("model", model);
            fail.put("latencyMs", System.currentTimeMillis() - start);
            fail.put("error", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            return fail;
        }
    }
}
