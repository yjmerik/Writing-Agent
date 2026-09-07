package com.writing.novel.llm.gateway;

import com.writing.novel.llm.LlmGateway;
import com.writing.novel.llm.LlmRequest;
import com.writing.novel.llm.LlmResponse;
import com.writing.novel.llm.client.LlmClient;
import com.writing.novel.llm.client.LlmUnavailableException;
import com.writing.novel.telemetry.EventLog;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.CancellationException;
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
    private final ProviderHealthTracker health;

    public DefaultLlmGateway(LlmProperties properties, LlmClient client) {
        this(properties, client, Clock.systemUTC());
    }

    public DefaultLlmGateway(LlmProperties properties, LlmClient client, Clock clock) {
        this.properties = properties;
        this.client = client;
        this.health = new ProviderHealthTracker(clock, properties.getFallback().getCooldownSeconds());
    }

    @Override
    public LlmResponse complete(LlmRequest request) {
        checkCancelled();
        List<String> ids = properties.providerIdsForTask(request.getTaskType().name());
        if (!ids.isEmpty()) return completeWithProviders(request, ids);
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

    private LlmResponse completeWithProviders(LlmRequest request, List<String> ids) {
        List<String> failures = new ArrayList<>();
        String previous = null;
        LlmUnavailableException previousFailure = null;
        for (int index = 0; index < ids.size(); index++) {
            checkCancelled();
            String id = ids.get(index);
            ProviderHealthTracker.Lease lease = health.acquire(id);
            if (lease == null) {
                failures.add(id + ": cooling until " + health.nextAttempt(id));
                continue;
            }
            long start = System.nanoTime();
            try {
                ModelConfig config = resolveNamedConfig(request, id);
                if (previous != null) emitSwitch(request, previous, id, config, previousFailure, index + 1);
                LlmResponse response = client.complete(config, request);
                checkCancelled();
                if (response == null || response.getContent() == null || response.getContent().isBlank()) {
                    throw new LlmUnavailableException(LlmUnavailableException.Reason.EMPTY_RESPONSE, 0);
                }
                recordSuccess(request, id, config, lease);
                return withExecution(response, id, config);
            } catch (LlmUnavailableException e) {
                checkCancelled();
                recordFailure(request, id, lease, e, index + 1, start);
                failures.add(id + ": " + e.getReason() + ", retry at " + health.nextAttempt(id));
                previous = id; previousFailure = e;
            } finally {
                health.release(lease);
            }
        }
        throw exhausted(failures);
    }

    private static LlmResponse withExecution(LlmResponse response, String id, ModelConfig config) {
        return response.withExecution(new LlmResponse.Execution(id, config.getModel(),
                config.getInputPriceYuanPerKTokens(), config.getOutputPriceYuanPerKTokens()));
    }

    private ModelConfig resolveNamedConfig(LlmRequest request, String id) {
        ModelConfig base = properties.getProviders().get(id);
        ModelConfig config = resolveConfig(request, base);
        config.setSingleAttempt(true);
        if (config.getMaxTokens() >= config.getContextWindow()) {
            throw new IllegalArgumentException("LLM requested output exceeds provider context-window: " + id);
        }
        return config;
    }

    private void recordFailure(LlmRequest request, String id, ProviderHealthTracker.Lease lease,
                               LlmUnavailableException error, int attempt, long start) {
        health.fail(lease);
        EventLog.emit("LLM_PROVIDER_UNAVAILABLE", Map.of(
                "taskType", request.getTaskType().name(), "provider", id,
                "model", properties.getProviders().get(id).getModel(),
                "reason", error.getReason().name(), "status", error.getStatus(),
                "attempt", attempt, "durationMs", (System.nanoTime() - start) / 1_000_000,
                "retryAt", health.nextAttempt(id)));
    }

    private void recordSuccess(LlmRequest request, String id, ModelConfig config, ProviderHealthTracker.Lease lease) {
        if (health.succeed(lease)) EventLog.emit("LLM_PROVIDER_RECOVERED", Map.of(
                "taskType", request.getTaskType().name(), "provider", id, "model", config.getModel()));
    }

    private void emitSwitch(LlmRequest request, String from, String to, ModelConfig config,
                            LlmUnavailableException error, int attempt) {
        EventLog.emit("LLM_FALLBACK", Map.of("taskType", request.getTaskType().name(),
                "from", from, "to", to, "model", config.getModel(), "attempt", attempt,
                "reason", error.getReason().name(), "status", error.getStatus()));
    }

    private static IllegalStateException exhausted(List<String> failures) {
        return new IllegalStateException("No available LLM provider: " + String.join("; ", failures));
    }

    private static void checkCancelled() {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("LLM request interrupted");
    }

    @Override
    public void streamComplete(LlmRequest request, StreamHandler handler) {
        if (Thread.currentThread().isInterrupted()) {
            handler.onError(new CancellationException("LLM request interrupted"));
            return;
        }
        List<String> ids = properties.providerIdsForTask(request.getTaskType().name());
        if (ids.isEmpty()) {
            client.streamComplete(resolveConfig(request), request, handler);
            return;
        }
        streamWithProviders(request, handler, ids);
    }

    private void streamWithProviders(LlmRequest request, StreamHandler handler, List<String> ids) {
        List<String> failures = new ArrayList<>();
        String previous = null;
        LlmUnavailableException previousFailure = null;
        for (int index = 0; index < ids.size(); index++) {
            if (Thread.currentThread().isInterrupted()) {
                handler.onError(new CancellationException("LLM request interrupted"));
                return;
            }
            String id = ids.get(index);
            ProviderHealthTracker.Lease lease = health.acquire(id);
            if (lease == null) {
                failures.add(id + ": cooling until " + health.nextAttempt(id));
                continue;
            }
            StreamAttempt attempt = new StreamAttempt(handler);
            long start = System.nanoTime();
            try {
                try {
                    ModelConfig config = resolveNamedConfig(request, id);
                    if (previous != null) emitSwitch(request, previous, id, config, previousFailure, index + 1);
                    client.streamComplete(config, request, attempt);
                } catch (RuntimeException e) {
                    attempt.onError(e);
                }
                if (attempt.callbackFailure != null) throw attempt.callbackFailure;
                if (Thread.currentThread().isInterrupted()) {
                    attempt.failure = new CancellationException("LLM request interrupted");
                }
                if (!attempt.terminal && attempt.failure == null) {
                    attempt.failure = new LlmUnavailableException(LlmUnavailableException.Reason.PROTOCOL, 0);
                }
                if (attempt.failure instanceof LlmUnavailableException e) {
                    recordFailure(request, id, lease, e, index + 1, start);
                    failures.add(id + ": " + e.getReason() + ", retry at " + health.nextAttempt(id));
                    if (!attempt.emitted) {
                        previous = id; previousFailure = e;
                        continue;
                    }
                } else if (attempt.failure == null) {
                    recordSuccess(request, id, properties.getProviders().get(id), lease);
                }
            } finally {
                health.release(lease);
            }
            // Invoke caller terminal callbacks outside the failover catch boundary.
            if (attempt.failure != null) handler.onError(attempt.failure);
            else handler.onComplete(withExecution(attempt.response != null ? attempt.response
                    : new LlmResponse(attempt.content.toString(), 0, 0), id, properties.getProviders().get(id)));
            return;
        }
        handler.onError(exhausted(failures));
    }

    /** Client callbacks are synchronous; capture terminal events until the attempt returns. */
    private static final class StreamAttempt implements StreamHandler {
        private final StreamHandler downstream;
        boolean terminal;
        boolean emitted;
        Throwable failure;
        LlmResponse response;
        final StringBuilder content = new StringBuilder();
        RuntimeException callbackFailure;
        StreamAttempt(StreamHandler downstream) { this.downstream = downstream; }
        public void onToken(String token) {
            if (terminal || callbackFailure != null || token == null || token.isEmpty()) return;
            emitted = true;
            content.append(token);
            try { downstream.onToken(token); }
            catch (RuntimeException e) { callbackFailure = e; throw e; }
        }
        public void onComplete() {
            if (!terminal && callbackFailure == null) terminal = true;
        }
        public void onComplete(LlmResponse response) {
            if (!terminal && callbackFailure == null) {
                this.response = response;
                onComplete();
            }
        }
        public void onError(Throwable e) {
            if (!terminal && callbackFailure == null) {
                terminal = true;
                failure = e;
            }
        }
    }

    /**
     * 解析模型配置：按任务类型查 → 克隆 → 合并请求级覆盖（temperature/maxTokens）。
     * 克隆避免修改共享的配置实例。
     */
    private ModelConfig resolveConfig(LlmRequest request) {
        return resolveConfig(request, properties.forTaskType(request.getTaskType().name()));
    }

    private ModelConfig resolveConfig(LlmRequest request, ModelConfig base) {
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
        c.setContextWindow(src.getContextWindow());
        c.setStyleGuide(src.getStyleGuide());
        c.setThinkingEnabled(src.isThinkingEnabled());
        c.setInputPriceYuanPerKTokens(src.getInputPriceYuanPerKTokens());
        c.setOutputPriceYuanPerKTokens(src.getOutputPriceYuanPerKTokens());
        c.setRequestTimeoutSeconds(src.getRequestTimeoutSeconds());
        c.setSingleAttempt(src.isSingleAttempt());
        return c;
    }
}
