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
        try {
            LlmResponse response = delegate.complete(request);
            // p10-w1-04: 按 taskType 模型 pricing 真实计价（替代硬编码单一费率）
            double estimatedCostCny = estimateCost(request.getTaskType(),
                    response.getPromptTokens(), response.getCompletionTokens());
            EventLog.emit("LLM_CALL", Map.of(
                    "taskType", request.getTaskType().name(),
                    "durationMs", System.currentTimeMillis() - start,
                    "promptTokens", response.getPromptTokens(),
                    "completionTokens", response.getCompletionTokens(),
                    "estimatedCostCny", Math.round(estimatedCostCny * 10000) / 10000.0));
            // p10-w1-04: 累计到项目成本（经 EventContext ThreadLocal 归因 projectId）
            if (costBudgetMonitor != null) {
                costBudgetMonitor.add(EventContext.projectId(), estimatedCostCny);
            }
            return response;
        } catch (RuntimeException e) {
            EventLog.emit("LLM_ERROR", Map.of(
                    "taskType", request.getTaskType().name(),
                    "durationMs", System.currentTimeMillis() - start,
                    "errorClass", e.getClass().getSimpleName(),
                    "message", EventLog.truncate(e.getMessage(), 200)));
            throw e;
        }
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
