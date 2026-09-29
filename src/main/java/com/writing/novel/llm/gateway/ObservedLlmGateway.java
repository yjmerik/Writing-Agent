package com.writing.novel.llm.gateway;

import com.writing.novel.llm.LlmGateway;
import com.writing.novel.llm.LlmRequest;
import com.writing.novel.llm.LlmResponse;
import com.writing.novel.llm.client.StreamHandler;
import com.writing.novel.llm.config.LlmProperties;
import com.writing.novel.llm.config.ModelConfig;
import com.writing.novel.telemetry.CostBudgetMonitor;
import com.writing.novel.telemetry.EventLog;
import com.writing.novel.telemetry.EventContext;

import java.util.Map;

/**
 * 可观测网关装饰器 — 为每次 LLM 调用计时并写结构化事件日志。
 * 成功 → LLM_CALL（耗时 + token 用量），失败 → LLM_ERROR（错误分类）。
 * 装饰链最外层，录制/回放命中同样被记录。
 */
public class ObservedLlmGateway implements LlmGateway {

    private final LlmGateway delegate;
    /** p10-w1-04: 按 taskType 模型 pricing 真实计价；null 时回退默认 0.001/0.002 */
    private final LlmProperties llmProperties;
    /** p10-w1-04: 按项目累计成本供预算熔断；null 时不累计（向后兼容） */
    private final CostBudgetMonitor costBudgetMonitor;

    public ObservedLlmGateway(LlmGateway delegate) {
        this(delegate, null, null);
    }

    /** p10-w1-04: 生产装配用——按模型真实计价 + 累计到 CostBudgetMonitor */
    public ObservedLlmGateway(LlmGateway delegate, LlmProperties llmProperties, CostBudgetMonitor costBudgetMonitor) {
        this.delegate = delegate;
        this.llmProperties = llmProperties;
        this.costBudgetMonitor = costBudgetMonitor;
    }

    @Override
    public LlmResponse complete(LlmRequest request) {
        long start = System.currentTimeMillis();
        // NCS-130: 逻辑调用 correlationId——同一请求的成功/失败/重试事件可关联
        String correlationId = java.util.UUID.randomUUID().toString().substring(0, 12);
        String promptFingerprint = promptFingerprint(request);
        String provider = resolveProvider(request);
        String model = resolveModel(request);
        try {
            LlmResponse response = delegate.complete(request);
            recordResponse(request, response, start, false);
            return response;
        } catch (RuntimeException e) {
            EventLog.emit("LLM_ERROR", Map.of(
                    "taskType", request.getTaskType().name(),
                    "correlationId", correlationId,
                    "provider", provider,
                    "model", model,
                    "promptFingerprint", promptFingerprint,
                    "durationMs", System.currentTimeMillis() - start,
                    "errorClass", e.getClass().getSimpleName(),
                    "message", EventLog.truncate(e.getMessage(), 200)));
            throw e;
        }
    }

    /** NCS-130: prompt SHA-256 前 12 位——"同一 prompt 反复失败"可聚类 */
    private static String promptFingerprint(LlmRequest request) {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            String normalized = (request.getSystemPrompt() == null ? "" : request.getSystemPrompt())
                    + "\u0001" + (request.getUserMessage() == null ? "" : request.getUserMessage());
            byte[] hash = digest.digest(normalized.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 6; i++) sb.append(String.format("%02x", hash[i]));
            return sb.toString();
        } catch (Exception e) {
            return "unavailable";
        }
    }

    private String resolveProvider(LlmRequest request) {
        try {
            if (llmProperties != null) {
                var ids = llmProperties.providerIdsForTask(request.getTaskType().name());
                if (!ids.isEmpty()) return ids.get(0);
            }
        } catch (Exception ignored) {}
        return "legacy";
    }

    private String resolveModel(LlmRequest request) {
        try {
            if (llmProperties != null) {
                var cfg = llmProperties.forTaskType(request.getTaskType().name());
                if (cfg != null && cfg.getModel() != null) return cfg.getModel();
            }
        } catch (Exception ignored) {}
        return "unknown";
    }

    @Override
    public void streamComplete(LlmRequest request, StreamHandler handler) {
        long start = System.currentTimeMillis();
        delegate.streamComplete(request, new StreamHandler() {
            @Override
            public void onToken(String token) {
                handler.onToken(token);
            }

            @Override
            public void onComplete() {
                EventLog.emit("LLM_CALL", Map.of(
                        "taskType", request.getTaskType().name(),
                        "durationMs", System.currentTimeMillis() - start,
                        "streaming", true));
                handler.onComplete();
            }

            @Override
            public void onComplete(LlmResponse response) {
                recordResponse(request, response, start, true);
                handler.onComplete(response);
            }

            @Override
            public void onError(Throwable error) {
                EventLog.emit("LLM_ERROR", Map.of(
                        "taskType", request.getTaskType().name(),
                        "durationMs", System.currentTimeMillis() - start,
                        "streaming", true,
                        "errorClass", error.getClass().getSimpleName(),
                        "message", EventLog.truncate(error.getMessage(), 200)));
                handler.onError(error);
            }
        });
    }

    private void recordResponse(LlmRequest request, LlmResponse response, long start, boolean streaming) {
        LlmResponse.Execution execution = response.getExecution();
        double cost = execution == null
                ? estimateCost(request.getTaskType(), response.getPromptTokens(), response.getCompletionTokens())
                : response.getPromptTokens() / 1000.0 * execution.inputPrice()
                    + response.getCompletionTokens() / 1000.0 * execution.outputPrice();
        var fields = new java.util.LinkedHashMap<String, Object>();
        fields.put("taskType", request.getTaskType().name());
        fields.put("durationMs", System.currentTimeMillis() - start);
        fields.put("promptTokens", response.getPromptTokens());
        fields.put("completionTokens", response.getCompletionTokens());
        fields.put("estimatedCostCny", Math.round(cost * 10000) / 10000.0);
        // NCS-065: 截断可观测——finish_reason=length 独立事件 + CALL 事件携带信号
        if (!"stop".equals(response.getFinishReason())) {
            fields.put("finishReason", response.getFinishReason());
        }
        if (streaming) fields.put("streaming", true);
        if (execution != null) {
            fields.put("provider", execution.providerId());
            fields.put("model", execution.model());
        }
        EventLog.emit("LLM_CALL", fields);
        if (response.isTruncated()) {
            EventLog.emit("LLM_TRUNCATED", Map.of(
                    "taskType", request.getTaskType().name(),
                    "finishReason", response.getFinishReason(),
                    "completionTokens", response.getCompletionTokens(),
                    "durationMs", System.currentTimeMillis() - start));
        }
        if (costBudgetMonitor != null) costBudgetMonitor.add(EventContext.projectId(), cost);
    }

    /**
     * p10-w1-04: 按 taskType 对应模型的 pricing 估算成本（人民币）。
     * llmProperties==null（旧构造器）或模型未配 pricing 时回退默认 0.001/0.002。
     */
    private double estimateCost(com.writing.novel.llm.LlmTaskType taskType, int promptTokens, int completionTokens) {
        if (promptTokens <= 0 && completionTokens <= 0) return 0;
        double inPrice = 0.001;
        double outPrice = 0.002;
        if (llmProperties != null) {
            ModelConfig cfg = llmProperties.forTaskType(taskType.name());
            if (cfg != null) {
                inPrice = cfg.getInputPriceYuanPerKTokens();
                outPrice = cfg.getOutputPriceYuanPerKTokens();
            }
        }
        return (promptTokens / 1000.0) * inPrice + (completionTokens / 1000.0) * outPrice;
    }
}
