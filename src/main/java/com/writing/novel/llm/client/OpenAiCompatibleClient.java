package com.writing.novel.llm.client;

import java.util.concurrent.ThreadLocalRandom;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.writing.novel.llm.LlmRequest;
import com.writing.novel.llm.LlmResponse;
import com.writing.novel.llm.config.ModelConfig;
import com.writing.novel.llm.config.ModelConfig.ThinkingParamStyle;
import okhttp3.*;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.time.Duration;

/**
 * OpenAI 兼容格式的 LLM 客户端。
 * 调用 POST {baseUrl}/chat/completions，兼容通义/DashScope/DeepSeek/OpenAI 等。
 */
public class OpenAiCompatibleClient implements LlmClient {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(OpenAiCompatibleClient.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final OkHttpClient httpClient;

    /** p10-w1-05: 退避时长（可被测试调小避免长睡）——5xx/网络 基数 */
    static volatile long backoffUnitMs = 1000L;

    /** 测试钩子：调整退避基数 */
    public static void setBackoffUnitMs(long v) { backoffUnitMs = v; }
    /** p10-w1-05: 429 限流退避基数（更长：指数到分钟级） */
    static volatile long rateLimitBackoffMs = 30_000L;

    public OpenAiCompatibleClient() {
        // NCS-174: 三超时可经系统属性覆盖（novel.llm.connect/read/call-timeout-seconds）——
        // 运维 escape hatch。默认值不变：30 分钟兜底是 p10-w1 实证的 glm 网关排队方差对策；
        // 生产 named-provider 路径实际走 singleAttempt，按 provider request-timeout-seconds 生效。
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(propSeconds("novel.llm.connect-timeout-seconds", 30)))
                .readTimeout(Duration.ofSeconds(propSeconds("novel.llm.read-timeout-seconds", 1800)))
                // glm-5.3-flash 长生成（20k max_tokens 非流式）网关侧偶发 >10 分钟排队；
                // e2e 实证 CHAPTER_WRITING 3×600s 整齐超时而同 prompt 独立进程 44s——网关排队方差大，
                // 超时必须给足：单次请求上限 30 分钟
                .callTimeout(Duration.ofSeconds(propSeconds("novel.llm.call-timeout-seconds", 1800)))
                .retryOnConnectionFailure(true)      // 网络抖动自动重试
                // e2e 实证：复用 keep-alive 连接发送 >10 分钟的长响应请求会被对端重置
                // （StreamResetException: CANCEL），而新连接稳定成功——LLM 长生成必须每请求新连接
                .connectionPool(new okhttp3.ConnectionPool(0, 1, java.util.concurrent.TimeUnit.MILLISECONDS))
                .build();
    }

    /** NCS-174: 秒级系统属性解析——非法/缺失回落默认，不阻断构造 */
    private static long propSeconds(String key, long defaultSeconds) {
        String raw = System.getProperty(key);
        if (raw == null || raw.isBlank()) return defaultSeconds;
        try {
            long v = Long.parseLong(raw.trim());
            return v > 0 ? v : defaultSeconds;
        } catch (NumberFormatException e) {
            log.warn("LLM 超时属性 {} 非法值 '{}'，回落默认 {}s", key, raw, defaultSeconds);
            return defaultSeconds;
        }
    }

    /** NCS-174: 包内测试访问器——断言默认构造超时装配 */
    OkHttpClient httpClient() {
        return httpClient;
    }

    /** 测试用：可注入自定义 OkHttpClient */
    public OpenAiCompatibleClient(OkHttpClient httpClient) {
        this.httpClient = httpClient;
    }

    @Override
    public LlmResponse complete(ModelConfig config, LlmRequest request) {
        if (config.isSingleAttempt()) return singleAttempt(config, request, config.isStreaming(), null);
        // p16-e2e: streaming 配置为 true 时走 SSE 聚合——长生成（>5 分钟）非流式响应会被
        // 网关/中间层 StreamReset CANCEL（e2e 实证 23 点后 100% 复现），流式逐 token 心跳保活；
        // 对上层透明（聚合完整 content 返回），fixture 录制在 gateway 层不受影响
        if (config.isStreaming()) {
            return completeViaStream(config, request);
        }
        ObjectNode body = buildRequestBody(config, request, false);
        String url = config.getBaseUrl() + "/chat/completions";

        // 429/5xx 重试（3次，指数退避）；内容审查(1301)首次温和化原位重试（不消耗次数）
        boolean softened = false;
        int attempt = 0;
        final int maxAttempts = config.effectiveRetryAttempts();   // NCS-129: 按任务配置
        while (attempt < maxAttempts) {
            Request httpRequest = new Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer " + config.getApiKey())
                    .header("Content-Type", "application/json")
                    .post(RequestBody.create(body.toString(), MediaType.parse("application/json")))
                    .build();

            long attemptStart = System.currentTimeMillis();
            try (Response response = httpClient.newCall(httpRequest).execute()) {
                if (response.isSuccessful()) {
                    JsonNode root = MAPPER.readTree(response.body().string());
                    JsonNode choice = root.path("choices").path(0);
                    String content = choice.path("message").path("content").asText();
                    int promptTokens = root.path("usage").path("prompt_tokens").asInt();
                    int completionTokens = root.path("usage").path("completion_tokens").asInt();
                    // NCS-065: 透传 finish_reason——length 截断对调用方可见
                    String finishReason = choice.path("finish_reason").asText("");
                    // p16-replay-fix: 空响应是服务端故障（瞬时空响应会覆盖非空 fixture、
                    // 使后处理产出漂移）——视为可重试失败，与 ChapterWriter 空内容重试同语义
                    if (content.isBlank() && attempt < maxAttempts - 1) {
                        log.warn("LLM 空响应(尝试{}/{}): {}，重试", attempt + 1, maxAttempts, request.getTaskType());
                        sleepBackoff(attempt, false);
                        attempt++;
                        continue;
                    }
                    return new LlmResponse(content, promptTokens, completionTokens, finishReason, null);
                }
                // 400/401/403 等客户端错误
                if (response.code() >= 400 && response.code() < 500 && response.code() != 429) {
                    String errBody = response.body().string();
                    // 内容审查（GLM 1301 等）：首次注入温和化提示原位重试一次（GLM 审查比 DeepSeek 敏感，
                    // 构思期的冲突/复仇设定易触发 level-1 误拦；温和化后通常可过）
                    if (errBody.contains("1301") || errBody.contains("content_filter")
                            || errBody.contains("Content Filter") || errBody.contains("contentFilter")) {
                        if (!softened) {
                            softened = true;
                            appendSafetyNote(body);
                            // NCS-135: 传输层审查温和化显式事件化——对上层与
                            // fixture 指纹可预期（此前偷偷改写 prompt 无任何信号）
                            com.writing.novel.telemetry.EventLog.emit("CONTENT_SOFTENED", java.util.Map.of(
                                    "taskType", request.getTaskType().name(),
                                    "httpStatus", response.code(),
                                    "phase", "non-stream"));
                            log.warn("内容审查拦截({}), 温和化 system prompt 后原位重试: {}",
                                    response.code(), request.getTaskType());
                            continue; // 不消耗重试次数
                        }
                        throw new ContentFilterException("内容审查拦截: " + errBody);
                    }
                    throw new RuntimeException("LLM 调用失败: HTTP " + response.code() + " " + errBody);
                }
                // 429/5xx 可重试
                if (attempt < maxAttempts - 1) {
                    boolean rateLimited = response.code() == 429;
                    response.close();
                    sleepBackoff(attempt, rateLimited);
                    attempt++;
                    continue;
                }
                // p10-w1-05: 429 重试耗尽 → RateLimitException（上层降级到 fallback 模型，不计永久失败）
                if (response.code() == 429) {
                    throw new RateLimitException("LLM 限流（429）重试耗尽: " + response.code());
                }
                throw new RuntimeException("LLM 调用失败（重试耗尽）: HTTP " + response.code()
                        + " " + response.body().string());
            } catch (IOException e) {
                long elapsed = System.currentTimeMillis() - attemptStart;
                log.warn("LLM 调用 IOException(尝试{}/3, {}ms): {} — {}: {}",
                        attempt + 1, elapsed, request.getTaskType(),
                        e.getClass().getSimpleName(), e.getMessage());
                if (attempt < maxAttempts - 1) {
                    sleepBackoff(attempt, false);
                    attempt++;
                    continue;
                }
                throw new RuntimeException("LLM 调用异常（重试耗尽）", e);
            }
        }
        throw new RuntimeException("LLM 调用失败：不应到达此处");
    }

    /**
     * p16-e2e: 流式聚合版 complete——SSE 逐 token 接收，聚合完整正文后以 LlmResponse 返回。
     * 保留非流式路径的重试语义（429 退避/5xx 重试/内容审查温和化），429/错误以 SSE 错误事件或 HTTP 状态呈现。
     */
    private LlmResponse completeViaStream(ModelConfig config, LlmRequest request) {
        int attempt = 0;
        while (attempt < 3) {
            ObjectNode body = buildRequestBody(config, request, true);
            body.putObject("stream_options").put("include_usage", true);
            String url = config.getBaseUrl() + "/chat/completions";
            Request httpRequest = new Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer " + config.getApiKey())
                    .header("Content-Type", "application/json")
                    .post(RequestBody.create(body.toString(), MediaType.parse("application/json")))
                    .build();
            long attemptStart = System.currentTimeMillis();
            try (Response response = httpClient.newCall(httpRequest).execute()) {
                if (!response.isSuccessful()) {
                    String errBody = response.body() != null ? response.body().string() : "";
                    // 429：退避重试；耗尽抛 RateLimitException
                    if (response.code() == 429) {
                        if (attempt < 2) { sleepBackoff(attempt, true); attempt++; continue; }
                        throw new RateLimitException("LLM 限流（429）重试耗尽: 429");
                    }
                    // 内容审查：温和化一次
                    if ((errBody.contains("1301") || errBody.contains("content_filter")
                            || errBody.contains("contentFilter")) && attempt < 2) {
                        appendSafetyNoteBody(body);
                        attempt++;
                        com.writing.novel.telemetry.EventLog.emit("CONTENT_SOFTENED", java.util.Map.of(
                                "taskType", request.getTaskType().name(),
                                "httpStatus", response.code(),
                                "phase", "stream"));
                        log.warn("内容审查拦截（流式），温和化后重试: {}", request.getTaskType());
                        continue;
                    }
                    if (response.code() >= 400 && response.code() < 500) {
                        throw new RuntimeException("LLM 调用失败: HTTP " + response.code() + " " + errBody);
                    }
                    if (attempt < 2) { sleepBackoff(attempt, false); attempt++; continue; }
                    throw new RuntimeException("LLM 调用失败（重试耗尽）: HTTP " + response.code());
                }
                StringBuilder content = new StringBuilder();
                int promptTokens = 0, completionTokens = 0;
                // NCS-065: 终止校验——流必须见到 [DONE] 或非空 finish_reason 才算成功；
                // 被干净掐断的流（EOF 无异常）此前会把半截内容当成功返回（真典收割单字符 "[" 根因）
                boolean terminated = false;
                String finishReason = "";
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                        response.body().byteStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.startsWith("data: ")) {
                            String data = line.substring(6).trim();
                            if ("[DONE]".equals(data)) { terminated = true; break; }
                            JsonNode chunk = MAPPER.readTree(data);
                            JsonNode delta = chunk.path("choices").path(0).path("delta");
                            content.append(delta.path("content").asText(""));
                            String finish = chunk.path("choices").path(0).path("finish_reason").asText("");
                            if (!finish.isEmpty()) { terminated = true; finishReason = finish; }
                            // usage 可能在最后一个 chunk（stream_options）或 [DONE] 帧携带
                            JsonNode usage = chunk.path("usage");
                            if (usage.isObject()) {
                                promptTokens = usage.path("prompt_tokens").asInt(promptTokens);
                                completionTokens = usage.path("completion_tokens").asInt(completionTokens);
                            }
                        }
                    }
                }
                // NCS-065: 未终止流与空聚合同语义——可重试的服务端故障，重试耗尽必须显式失败
                if (!terminated) {
                    if (attempt < 2) {
                        log.warn("LLM 流式未正常终止(尝试{}/3, 已收{}字符): {}，重试",
                                attempt + 1, content.length(), request.getTaskType());
                        sleepBackoff(attempt, false);
                        attempt++;
                        continue;
                    }
                    throw new RuntimeException("LLM 流式未正常终止（无 [DONE]/finish_reason 即 EOF）: "
                            + request.getTaskType());
                }
                // p16-replay-fix: 流式空聚合同样重试——瞬时空响应是服务端故障
                if (content.isEmpty() && attempt < 2) {
                    log.warn("LLM 流式空聚合(尝试{}/3): {}，重试", attempt + 1, request.getTaskType());
                    sleepBackoff(attempt, false);
                    attempt++;
                    continue;
                }
                if ("length".equals(finishReason)) {
                    log.warn("LLM 流式输出被 max_tokens 截断: {} (completionTokens={})",
                            request.getTaskType(), completionTokens);
                }
                return new LlmResponse(content.toString(), promptTokens, completionTokens, finishReason, null);
            } catch (java.io.IOException e) {
                long elapsed = System.currentTimeMillis() - attemptStart;
                log.warn("LLM 流式调用 IOException(尝试{}/3, {}ms): {} — {}: {}",
                        attempt + 1, elapsed, request.getTaskType(),
                        e.getClass().getSimpleName(), e.getMessage());
                if (attempt < 2) { sleepBackoff(attempt, false); attempt++; continue; }
                throw new RuntimeException("LLM 调用异常（重试耗尽）", e);
            }
        }
        throw new RuntimeException("LLM 调用失败：不应到达此处");
    }

    /** appendSafetyNote 的 body 参数版（流式路径的 body 是局部变量） */
    private void appendSafetyNoteBody(ObjectNode body) {
        appendSafetyNote(body);
    }

    /** 内容审查温和化：向 system 消息追加安全表达提示（原位修改请求体） */
    private void appendSafetyNote(ObjectNode body) {
        JsonNode messages = body.get("messages");
        if (messages != null && messages.isArray() && !messages.isEmpty()) {
            JsonNode first = messages.get(0);
            if (first.isObject() && "system".equals(first.path("role").asText())) {
                ((com.fasterxml.jackson.databind.node.ObjectNode) first).put("content",
                        first.path("content").asText()
                                + "\n\n【安全提示】请全程使用克制、含蓄的表达：冲突与死亡用侧面烘托而非直白描写，"
                                + "避免血腥细节、自残教唆与任何敏感现实指涉。保持文学化叙述。");
            }
        }
    }

    /**
     * p10-w1-05: 分类型指数退避。
     * 限流(429)：rateLimitBackoffMs * (attempt+1)，更长（指数到分钟级）；
     * 5xx/网络：backoffUnitMs * (attempt+1)。
     */
    /**
     * NCS-129: 指数退避 + 全抖动 jitter——旧线性退避（unit×(attempt+1)）在 429 风暴下
     * 多线程同步重放；jitter 打散，基数可按任务配置。
     */
    private void sleepBackoff(int attempt, boolean rateLimited) {
        long unit = rateLimited ? rateLimitBackoffMs : backoffUnitMs;
        long exponential = unit * (1L << Math.min(attempt, 10));
        long jitter = (long) (exponential * 0.5 * ThreadLocalRandom.current().nextDouble());
        long sleep = exponential - jitter;   // [50%, 100%) of exponential
        try { Thread.sleep(Math.max(1, sleep)); } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void streamComplete(ModelConfig config, LlmRequest request, StreamHandler handler) {
        if (config.isSingleAttempt()) {
            LlmResponse result;
            try {
                result = singleAttempt(config, request, true, handler);
            } catch (CallbackFailure e) {
                throw e.original;
            } catch (RuntimeException e) {
                handler.onError(e);
                return;
            }
            handler.onComplete(result);
            return;
        }
        ObjectNode body = buildRequestBody(config, request, true);
        String url = config.getBaseUrl() + "/chat/completions";

        Request httpRequest = new Request.Builder()
                .url(url)
                .header("Authorization", "Bearer " + config.getApiKey())
                .header("Content-Type", "application/json")
                .post(RequestBody.create(body.toString(), MediaType.parse("application/json")))
                .build();

        try (Response response = httpClient.newCall(httpRequest).execute()) {
            if (!response.isSuccessful()) {
                throw new RuntimeException("LLM 流式调用失败: HTTP " + response.code());
            }
            // NCS-065: 终止校验——未见 [DONE]/finish_reason 即 EOF 的流不得回调 onComplete
            boolean terminated = false;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(response.body().byteStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("data: ")) {
                        // NCS-135: 共享帧解析（与聚合路径同源，防双实现漂移）
                        SseChunk chunk = parseSseData(line.substring(6).trim());
                        if (chunk.doneMarker()) { terminated = true; break; }
                        if (chunk.terminated()) terminated = true;
                        if (chunk.delta() != null && !chunk.delta().isEmpty()) {
                            handler.onToken(chunk.delta());
                        }
                    }
                }
            }
            if (!terminated) {
                throw new RuntimeException("LLM 流式未正常终止（无 [DONE]/finish_reason 即 EOF）: "
                        + request.getTaskType());
            }
            handler.onComplete();
        } catch (Exception e) {
            handler.onError(e);
        }
    }

    /**
     * NCS-135: 共享 SSE 帧记录——聚合 completeViaStream 与回调 streamComplete
     * 此前各自维护解析逻辑易漂移；统一帧结构由各实现消费。
     */
    public record SseChunk(String delta, String finishReason, Integer promptTokens, Integer completionTokens,
                    boolean doneMarker) {
        public boolean terminated() {
            return doneMarker || (finishReason != null && !finishReason.isEmpty());
        }
    }

    public static SseChunk parseSseData(String data) {
        if ("[DONE]".equals(data)) return new SseChunk(null, null, null, null, true);
        JsonNode chunk;
        try {
            chunk = MAPPER.readTree(data);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new RuntimeException(e);
        }
        String delta = chunk.path("choices").path(0).path("delta").path("content").asText("");
        String finish = chunk.path("choices").path(0).path("finish_reason").asText("");
        JsonNode usage = chunk.path("usage");
        Integer pt = usage.isObject() && usage.hasNonNull("prompt_tokens")
                ? usage.path("prompt_tokens").asInt() : null;
        Integer ct = usage.isObject() && usage.hasNonNull("completion_tokens")
                ? usage.path("completion_tokens").asInt() : null;
        return new SseChunk(delta, finish, pt, ct, false);
    }

    /** Named providers have one bounded attempt; retry/cooldown is owned by the gateway. */
    private LlmResponse singleAttempt(ModelConfig config, LlmRequest request, boolean stream, StreamHandler handler) {
        checkCancelled();
        ObjectNode body = buildRequestBody(config, request, stream);
        if (stream) body.putObject("stream_options").put("include_usage", true);
        Request httpRequest = new Request.Builder()
                .url(config.getBaseUrl().replaceAll("/+$", "") + "/chat/completions")
                .header("Authorization", "Bearer " + config.getApiKey())
                .post(RequestBody.create(body.toString(), MediaType.parse("application/json"))).build();
        var transport = httpClient.newBuilder()
                .callTimeout(Duration.ofSeconds(config.getRequestTimeoutSeconds()))
                .readTimeout(Duration.ofSeconds(config.getRequestTimeoutSeconds()))
                .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
                // OkHttp follows 503 + Retry-After:0 even when connection retries are disabled.
                // The provider gateway owns retry policy; suppress that hidden follow-up.
                .addNetworkInterceptor(chain -> {
                    Response result = chain.proceed(chain.request());
                    return result.code() == 503 ? result.newBuilder().removeHeader("Retry-After").build() : result;
                }).build();
        try (Response response = transport.newCall(httpRequest).execute()) {
            if (!response.isSuccessful()) {
                String errorBody = response.body() == null ? "" : response.body().string();
                JsonNode error = MAPPER.createObjectNode();
                try { error = MAPPER.readTree(errorBody); } catch (IOException ignored) { }
                throw remoteError(response.code(), error);
            }
            if (response.body() == null) throw unavailable(LlmUnavailableException.Reason.EMPTY_RESPONSE, 0);
            if (!stream) {
                JsonNode root = MAPPER.readTree(response.body().string());
                if (root == null) throw unavailable(LlmUnavailableException.Reason.EMPTY_RESPONSE, 0);
                if (root.has("error")) throw remoteError(200, root);
                JsonNode choice = root.path("choices").path(0);
                if ("content_filter".equals(choice.path("finish_reason").asText())) {
                    throw new ContentFilterException("LLM content filtered");
                }
                String content = choice.path("message").path("content").asText("");
                if (content.isBlank()) throw unavailable(LlmUnavailableException.Reason.EMPTY_RESPONSE, 0);
                checkCancelled();
                // NCS-065: 透传 finish_reason（length = 输出截断）
                return new LlmResponse(content, root.path("usage").path("prompt_tokens").asInt(),
                        root.path("usage").path("completion_tokens").asInt(),
                        choice.path("finish_reason").asText(""), null);
            }
            StringBuilder content = new StringBuilder();
            int promptTokens = 0, completionTokens = 0;
            boolean terminated = false;
            String finishReason = "";
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    response.body().byteStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    checkCancelled();
                    if (!line.startsWith("data:")) continue;
                    String data = line.substring(5).trim();
                    if (data.isEmpty()) continue;
                    if ("[DONE]".equals(data)) { terminated = true; break; }
                    JsonNode chunk = MAPPER.readTree(data);
                    if (chunk.has("error")) throw remoteError(200, chunk);
                    JsonNode choice = chunk.path("choices").path(0);
                    String finish = choice.path("finish_reason").asText("");
                    if ("content_filter".equals(finish)) throw new ContentFilterException("LLM content filtered");
                    if (!finish.isEmpty()) { terminated = true; finishReason = finish; }
                    String token = choice.path("delta").path("content").asText("");
                    content.append(token);
                    if (handler != null && !token.isEmpty()) {
                        try { handler.onToken(token); }
                        catch (RuntimeException e) { throw new CallbackFailure(e); }
                    }
                    JsonNode usage = chunk.path("usage");
                    promptTokens = usage.path("prompt_tokens").asInt(promptTokens);
                    completionTokens = usage.path("completion_tokens").asInt(completionTokens);
                }
            }
            checkCancelled();
            if (!terminated) throw unavailable(LlmUnavailableException.Reason.PROTOCOL, 0);
            if (content.toString().isBlank()) throw unavailable(LlmUnavailableException.Reason.EMPTY_RESPONSE, 0);
            return new LlmResponse(content.toString(), promptTokens, completionTokens, finishReason, null);
        } catch (IOException e) {
            checkCancelled();
            // Do not attach the raw exception: transport diagnostics can include request URLs.
            var reason = e instanceof com.fasterxml.jackson.core.JsonProcessingException
                    ? LlmUnavailableException.Reason.PROTOCOL
                    : e instanceof java.io.InterruptedIOException
                    ? LlmUnavailableException.Reason.TIMEOUT : LlmUnavailableException.Reason.NETWORK;
            throw unavailable(reason, 0);
        }
    }

    private static void checkCancelled() {
        if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException("LLM request interrupted");
    }

    private static final class CallbackFailure extends RuntimeException {
        final RuntimeException original;
        CallbackFailure(RuntimeException original) { this.original = original; }
    }

    private static LlmUnavailableException unavailable(LlmUnavailableException.Reason reason, int status) {
        return new LlmUnavailableException(reason, status);
    }

    private static RuntimeException remoteError(int status, JsonNode body) {
        JsonNode error = body == null ? MAPPER.createObjectNode() : body.path("error");
        String code = error.path("code").asText(error.path("type").asText("")).toLowerCase(java.util.Locale.ROOT);
        String type = error.path("type").asText("").toLowerCase(java.util.Locale.ROOT);
        var codes = java.util.Set.of("content_filter", "contentfilter", "1301", "data_inspection_failed", "content_policy_violation");
        if (codes.contains(code) || codes.contains(type)) return new ContentFilterException("LLM content filtered");
        if (code.equals("context_length_exceeded")) {
            return new IllegalArgumentException("LLM request rejected (HTTP " + status + ")");
        }
        if (java.util.Set.of("insufficient_quota", "quota_exceeded", "insufficient_balance", "arrearage", "balance_not_enough").contains(code)) {
            return unavailable(LlmUnavailableException.Reason.QUOTA, status);
        }
        if (code.equals("rate_limit_exceeded") || type.equals("rate_limit_error")) return unavailable(LlmUnavailableException.Reason.RATE_LIMIT, status);
        int effectiveStatus = error.path("status").asInt(error.path("code").asInt(status));
        if (effectiveStatus == 401 || effectiveStatus == 403) return unavailable(LlmUnavailableException.Reason.AUTH, effectiveStatus);
        if (effectiveStatus == 402) return unavailable(LlmUnavailableException.Reason.QUOTA, effectiveStatus);
        if (effectiveStatus == 404) return unavailable(LlmUnavailableException.Reason.ENDPOINT, effectiveStatus);
        if (effectiveStatus == 408) return unavailable(LlmUnavailableException.Reason.TIMEOUT, effectiveStatus);
        if (effectiveStatus == 429) return unavailable(LlmUnavailableException.Reason.RATE_LIMIT, effectiveStatus);
        if (effectiveStatus >= 500) return unavailable(LlmUnavailableException.Reason.SERVER, effectiveStatus);
        if (code.equals("invalid_request_error") || type.equals("invalid_request_error")) {
            return new IllegalArgumentException("LLM request rejected (HTTP " + status + ")");
        }
        if (status == 200) return unavailable(LlmUnavailableException.Reason.PROTOCOL, 0);
        return new IllegalArgumentException("LLM request rejected (HTTP " + status + ")");
    }

    /** NCS-066: 解析关思考参数形态——显式指定优先，AUTO 按端点推断 */
    public static ThinkingParamStyle resolveThinkingStyle(ModelConfig config) {
        if (config.getThinkingParamStyle() != ThinkingParamStyle.AUTO) return config.getThinkingParamStyle();
        String baseUrl = config.getBaseUrl() == null ? "" : config.getBaseUrl().toLowerCase(java.util.Locale.ROOT);
        return baseUrl.contains("aliyuncs.com") || baseUrl.contains("dashscope")
                ? ThinkingParamStyle.DASHSCOPE : ThinkingParamStyle.GLM;
    }

    /** 组装 OpenAI 兼容的请求体：messages = [system] + history + [user] */
    private ObjectNode buildRequestBody(ModelConfig config, LlmRequest request, boolean stream) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", config.getModel());
        body.put("temperature", config.getTemperature());
        body.put("max_tokens", config.getMaxTokens());
        body.put("stream", stream);
        // 思考模式开关：关闭后无 reasoning_content，秒级返回且不占输出预算。
        // NCS-066: 参数形态按端点适配——GLM 系 thinking.type=disabled；DashScope 兼容端点
        // （qwen3 思考模型）enable_thinking:false；未关闭时不注入任何字段
        if (!config.isThinkingEnabled()) {
            ThinkingParamStyle style = resolveThinkingStyle(config);
            if (style == ModelConfig.ThinkingParamStyle.DASHSCOPE) {
                body.put("enable_thinking", false);
            } else {
                body.putObject("thinking").put("type", "disabled");
            }
        }

        ArrayNode messages = body.putArray("messages");
        if (request.getSystemPrompt() != null && !request.getSystemPrompt().isEmpty()) {
            ObjectNode sys = messages.addObject();
            sys.put("role", "system");
            // 如果模型配置了 styleGuide，拼接到 systemPrompt 末尾
            String systemContent = request.getSystemPrompt();
            if (config.getStyleGuide() != null && !config.getStyleGuide().isBlank()) {
                systemContent = systemContent + "\n\n【模型风格约束】" + config.getStyleGuide();
            }
            sys.put("content", systemContent);
        }
        for (ChatMessage msg : request.getHistory()) {
            ObjectNode m = messages.addObject();
            m.put("role", msg.role());
            m.put("content", msg.content());
        }
        ObjectNode user = messages.addObject();
        user.put("role", "user");
        user.put("content", request.getUserMessage());

        return body;
    }
}
