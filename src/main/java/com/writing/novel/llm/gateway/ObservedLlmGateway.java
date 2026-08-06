package com.writing.novel.llm.gateway;

import com.writing.novel.llm.LlmGateway;
import com.writing.novel.llm.LlmRequest;
import com.writing.novel.llm.LlmResponse;
import com.writing.novel.llm.client.StreamHandler;
import com.writing.novel.telemetry.EventLog;

import java.util.Map;

/**
 * 可观测网关装饰器 — 为每次 LLM 调用计时并写结构化事件日志。
 * 成功 → LLM_CALL（耗时 + token 用量），失败 → LLM_ERROR（错误分类）。
 * 装饰链最外层，录制/回放命中同样被记录。
 */
public class ObservedLlmGateway implements LlmGateway {

    private final LlmGateway delegate;

    public ObservedLlmGateway(LlmGateway delegate) {
        this.delegate = delegate;
    }

    @Override
    public LlmResponse complete(LlmRequest request) {
        long start = System.currentTimeMillis();
        try {
            LlmResponse response = delegate.complete(request);
            EventLog.emit("LLM_CALL", Map.of(
                    "taskType", request.getTaskType().name(),
                    "durationMs", System.currentTimeMillis() - start,
                    "promptTokens", response.getPromptTokens(),
                    "completionTokens", response.getCompletionTokens()));
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
}
