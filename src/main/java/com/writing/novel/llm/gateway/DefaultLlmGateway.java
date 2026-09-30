package com.writing.novel.llm.gateway;

import com.writing.novel.llm.LlmGateway;
import com.writing.novel.llm.LlmProviderUnavailableException;
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

    /** NCS-002: 保守 token 估算——中文叙事文本按 1.3 字符/token 上折算（宁高估不低估） */
    static final double CHARS_PER_TOKEN = 1.3;
    /** NCS-002: 消息封装/模板开销的保守常量预留（tokens） */
    static final int ENVELOPE_OVERHEAD_TOKENS = 256;

    private final LlmProperties properties;
    private final LlmClient client;
    private final ProviderHealthTracker health;
    private final Clock clock;

    /** NCS-140：单次 complete() 逻辑调用的墙钟上限——测试可覆盖（先例：client.backoffUnitMs） */
    static volatile long maxLogicalCallMs = 45L * 60 * 1000;
    static void setMaxLogicalCallMs(long v) { maxLogicalCallMs = v; }

    public DefaultLlmGateway(LlmProperties properties, LlmClient client) {
        this(properties, client, Clock.systemUTC());
    }

    public DefaultLlmGateway(LlmProperties properties, LlmClient client, Clock clock) {
        this.properties = properties;
        this.client = client;
        this.health = new ProviderHealthTracker(clock, properties.getFallback().getCooldownSeconds());
        this.clock = clock;
    }

    @Override
    public LlmResponse complete(LlmRequest request) {
        checkCancelled();
        List<String> ids = properties.providerIdsForTask(request.getTaskType().name());
        if (!ids.isEmpty()) return completeWithProviders(request, ids);
        ModelConfig config = resolveConfig(request);
        try {
            guardFinalBudget(request, config);
            return client.complete(config, request);
        } catch (RequestBudgetExceededException budget) {
            // NCS-002/R11: 主路由超窗 → 尝试 defaultModel fallback（可能窗口更大），仍超窗才失败
            ModelConfig fallback = resolveFallbackConfig(request);
            if (fallback != null) {
                guardFinalBudget(request, fallback);
                return client.complete(fallback, request);
            }
            throw budget;
        } catch (RateLimitException e) {
            // p10-w1-05: 主模型限流 → 降级到 fallback（defaultModel），若配置了不同端点则重试一次
            ModelConfig fallback = resolveFallbackConfig(request);
            if (fallback != null) {
                guardFinalBudget(request, fallback);
                return client.complete(fallback, request);
            }
            throw e; // 无可用 fallback，向上抛限流异常
        }
    }

    private LlmResponse completeWithProviders(LlmRequest request, List<String> ids) {
        List<String> failures = new ArrayList<>();
        boolean anyCooldown = false; // NCS-156: 冷却跳过/失败进入冷却 → 瞬态标志
        String previous = null;
        LlmUnavailableException previousFailure = null;
        RequestBudgetExceededException budgetFailure = null;
        long deadlineStart = clock.millis();
        for (int index = 0; index < ids.size(); index++) {
            checkCancelled();
            // NCS-140（旅程三 F-6）：逻辑调用墙钟上限——无论路由组合/熔断窗口如何，
            // 单次 complete() 不得把同步工作权拖到小时级（实测曾 49 分钟）。
            long elapsed = clock.millis() - deadlineStart;
            if (index > 0 && elapsed > maxLogicalCallMs) {
                failures.add("logical-call-deadline: " + elapsed + "ms 超过上限，停止尝试剩余 "
                        + (ids.size() - index) + " 个 provider");
                break;
            }
            String id = ids.get(index);
            ProviderHealthTracker.Lease lease = health.acquire(id);
            if (lease == null) {
                failures.add(id + ": cooling until " + health.nextAttempt(id));
                anyCooldown = true;
                continue;
            }
            long start = System.nanoTime();
            try {
                ModelConfig config = resolveNamedConfig(request, id);
                // NCS-002: 发送前按该路由模型窗口核算；超窗不触达 provider，尝试下一路由（可能更大窗口）
                guardFinalBudget(request, config);
                if (previous != null) emitSwitch(request, previous, id, config, previousFailure, index + 1);
                LlmResponse response = client.complete(config, request);
                checkCancelled();
                if (response == null || response.getContent() == null || response.getContent().isBlank()) {
                    throw new LlmUnavailableException(LlmUnavailableException.Reason.EMPTY_RESPONSE, 0);
                }
                recordSuccess(request, id, config, lease);
                return withExecution(response, id, config);
            } catch (RequestBudgetExceededException budget) {
                // 非 provider 故障——不计入健康熔断；收集后继续尝试更大窗口路由
                budgetFailure = budget;
                failures.add(id + ": request-budget-exceeded");
            } catch (LlmUnavailableException e) {
                checkCancelled();
                recordFailure(request, id, lease, e, index + 1, start);
                failures.add(id + ": " + e.getReason() + ", retry at " + health.nextAttempt(id));
                anyCooldown = true;
                anyCooldown = true;
                previous = id; previousFailure = e;
            } finally {
                health.release(lease);
            }
        }
        if (budgetFailure != null) throw budgetFailure;
        throw exhausted(failures, anyCooldown);
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

    /** NCS-156: 类型化不可用异常——cooldown 标志供长任务重投分类读取，替代消息文本匹配 */
    private static LlmProviderUnavailableException exhausted(List<String> failures, boolean anyCooldown) {
        return new LlmProviderUnavailableException(
                "No available LLM provider: " + String.join("; ", failures), anyCooldown);
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
            try {
                guardFinalBudget(request, resolveConfig(request));
            } catch (RequestBudgetExceededException e) {
                handler.onError(e);
                return;
            }
            client.streamComplete(resolveConfig(request), request, handler);
            return;
        }
        streamWithProviders(request, handler, ids);
    }

    private void streamWithProviders(LlmRequest request, StreamHandler handler, List<String> ids) {
        List<String> failures = new ArrayList<>();
        boolean anyCooldown = false; // NCS-156: 冷却跳过/失败进入冷却 → 瞬态标志
        String previous = null;
        LlmUnavailableException previousFailure = null;
        RequestBudgetExceededException budgetFailure = null;
        for (int index = 0; index < ids.size(); index++) {
            if (Thread.currentThread().isInterrupted()) {
                handler.onError(new CancellationException("LLM request interrupted"));
                return;
            }
            String id = ids.get(index);
            ProviderHealthTracker.Lease lease = health.acquire(id);
            if (lease == null) {
                failures.add(id + ": cooling until " + health.nextAttempt(id));
                anyCooldown = true;
                continue;
            }
            StreamAttempt attempt = new StreamAttempt(handler);
            long start = System.nanoTime();
            try {
                try {
                    ModelConfig config = resolveNamedConfig(request, id);
                    // NCS-002: 流式路径同样在发送前核算；超窗不触达 provider
                    guardFinalBudget(request, config);
                    if (previous != null) emitSwitch(request, previous, id, config, previousFailure, index + 1);
                    client.streamComplete(config, request, attempt);
                } catch (RequestBudgetExceededException budget) {
                    budgetFailure = budget;
                    failures.add(id + ": request-budget-exceeded");
                    continue;
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
                anyCooldown = true;
                anyCooldown = true;
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
        if (budgetFailure != null) handler.onError(budgetFailure);
        else handler.onError(exhausted(failures, anyCooldown));
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
     * NCS-002（方案 §10/R11/F12）: 最终请求窗口核算。
     * 序列化输入（system+user+history）+ 输出预留（请求与配置较大者的 maxTokens）+ 安全余量（5%）
     * 必须不超过目标模型 contextWindow；超限记 REQUEST_BUDGET_EXCEEDED 事件并确定性失败——
     * 不静默截断、不触达 provider。contextWindow 未配置（<=0）时不设防（向后兼容）。
     * 估算采用保守折算（中文叙事 1.3 字符/token + 常量封装开销），宁高估不低估。
     */
    void guardFinalBudget(LlmRequest request, ModelConfig config) {
        int window = config.getContextWindow();
        if (window <= 0) return;
        int outputReserve = Math.max(0, config.getMaxTokens());
        int safety = Math.max(1, (int) (window * 0.05));
        long inputTokens = estimatedInputTokens(request);
        if (inputTokens + outputReserve + safety > window) {
            EventLog.emit("REQUEST_BUDGET_EXCEEDED", Map.of(
                    "taskType", request.getTaskType().name(),
                    "model", String.valueOf(config.getModel()),
                    "inputTokens", inputTokens,
                    "outputReserve", outputReserve,
                    "safetyMargin", safety,
                    "contextWindow", window));
            throw new RequestBudgetExceededException("最终请求超出模型窗口: 输入约 " + inputTokens
                    + " + 输出预留 " + outputReserve + " + 安全余量 " + safety + " > contextWindow " + window
                    + "（model=" + config.getModel() + "）——请缩小任务范围、拆分材料或改用更大窗口路由，不做静默截断");
        }
    }

    /** NCS-002: 序列化输入 token 保守估算（system+user+history，含封装开销常量） */
    static long estimatedInputTokens(LlmRequest request) {
        long chars = 0;
        if (request.getSystemPrompt() != null) chars += request.getSystemPrompt().length();
        if (request.getUserMessage() != null) chars += request.getUserMessage().length();
        if (request.getHistory() != null) {
            for (var msg : request.getHistory()) {
                chars += msg.content() == null ? 0 : msg.content().length();
            }
        }
        return (long) Math.ceil(chars / CHARS_PER_TOKEN) + ENVELOPE_OVERHEAD_TOKENS;
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
        // NCS-140（旅程三 F-6）：fallback 必须单次尝试——此前继承客户端默认
        // retryAttempts=3 × 30min callTimeout，限流降级路径最坏可挂 ~90 分钟
        // （记忆摘要实测单逻辑调用 49 分钟）。重试/熔断归网关所有。
        resolved.setSingleAttempt(true);
        return resolved;
    }

    private ModelConfig cloneConfig(ModelConfig src) {
        ModelConfig c = new ModelConfig();
        c.setBaseUrl(src.getBaseUrl());
        c.setApiKey(src.getApiKey());
        c.setModel(src.getModel());
        c.setProtocol(src.getProtocol());
        c.setTemperature(src.getTemperature());
        c.setMaxTokens(src.getMaxTokens());
        c.setStreaming(src.isStreaming());
        c.setContextWindow(src.getContextWindow());
        c.setStyleGuide(src.getStyleGuide());
        c.setThinkingEnabled(src.isThinkingEnabled());
        c.setThinkingParamStyle(src.getThinkingParamStyle());
        c.setInputPriceYuanPerKTokens(src.getInputPriceYuanPerKTokens());
        c.setOutputPriceYuanPerKTokens(src.getOutputPriceYuanPerKTokens());
        c.setRequestTimeoutSeconds(src.getRequestTimeoutSeconds());
        c.setSingleAttempt(src.isSingleAttempt());
        return c;
    }
}
