package com.writing.novel.llm.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.writing.novel.llm.LlmRequest;
import com.writing.novel.llm.LlmResponse;
import com.writing.novel.llm.config.ModelConfig;
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
    /** p10-w1-05: 429 限流退避基数（更长：指数到分钟级） */
    static volatile long rateLimitBackoffMs = 30_000L;

    public OpenAiCompatibleClient() {
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(30))
                .readTimeout(Duration.ofMinutes(30))
                // glm-5.3-flash 长生成（20k max_tokens 非流式）网关侧偶发 >10 分钟排队；
                // e2e 实证 CHAPTER_WRITING 3×600s 整齐超时而同 prompt 独立进程 44s——网关排队方差大，
                // 超时必须给足：单次请求上限 30 分钟
                .callTimeout(Duration.ofMinutes(30))
                .retryOnConnectionFailure(true)      // 网络抖动自动重试
                // e2e 实证：复用 keep-alive 连接发送 >10 分钟的长响应请求会被对端重置
                // （StreamResetException: CANCEL），而新连接稳定成功——LLM 长生成必须每请求新连接
                .connectionPool(new okhttp3.ConnectionPool(0, 1, java.util.concurrent.TimeUnit.MILLISECONDS))
                .build();
    }

    /** 测试用：可注入自定义 OkHttpClient */
    public OpenAiCompatibleClient(OkHttpClient httpClient) {
        this.httpClient = httpClient;
    }

    @Override
    public LlmResponse complete(ModelConfig config, LlmRequest request) {
        ObjectNode body = buildRequestBody(config, request, false);
        String url = config.getBaseUrl() + "/chat/completions";

        // 429/5xx 重试（3次，指数退避）；内容审查(1301)首次温和化原位重试（不消耗次数）
        boolean softened = false;
        int attempt = 0;
        while (attempt < 3) {
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
                    String content = root.path("choices").path(0)
                            .path("message").path("content").asText();
                    int promptTokens = root.path("usage").path("prompt_tokens").asInt();
                    int completionTokens = root.path("usage").path("completion_tokens").asInt();
                    return new LlmResponse(content, promptTokens, completionTokens);
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
                            log.warn("内容审查拦截({}), 温和化 system prompt 后原位重试: {}",
                                    response.code(), request.getTaskType());
                            continue; // 不消耗重试次数
                        }
                        throw new ContentFilterException("内容审查拦截: " + errBody);
                    }
                    throw new RuntimeException("LLM 调用失败: HTTP " + response.code() + " " + errBody);
                }
                // 429/5xx 可重试
                if (attempt < 2) {
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
                if (attempt < 2) {
                    sleepBackoff(attempt, false);
                    attempt++;
                    continue;
                }
                throw new RuntimeException("LLM 调用异常（重试耗尽）", e);
            }
        }
        throw new RuntimeException("LLM 调用失败：不应到达此处");
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
    private void sleepBackoff(int attempt, boolean rateLimited) {
        long unit = rateLimited ? rateLimitBackoffMs : backoffUnitMs;
        try { Thread.sleep(unit * (attempt + 1)); } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void streamComplete(ModelConfig config, LlmRequest request, StreamHandler handler) {
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
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(response.body().byteStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("data: ")) {
                        String data = line.substring(6).trim();
                        if ("[DONE]".equals(data)) break;
                        JsonNode chunk = MAPPER.readTree(data);
                        String delta = chunk.path("choices").path(0)
                                .path("delta").path("content").asText("");
                        if (!delta.isEmpty()) {
                            handler.onToken(delta);
                        }
                    }
                }
            }
            handler.onComplete();
        } catch (Exception e) {
            handler.onError(e);
        }
    }

    /** 组装 OpenAI 兼容的请求体：messages = [system] + history + [user] */
    private ObjectNode buildRequestBody(ModelConfig config, LlmRequest request, boolean stream) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", config.getModel());
        body.put("temperature", config.getTemperature());
        body.put("max_tokens", config.getMaxTokens());
        body.put("stream", stream);
        // 思考模式开关（GLM thinking 参数）：关闭后无 reasoning_content，秒级返回；
        // 非 GLM 端点（DeepSeek/本地）不会设置 thinking-enabled: false，不注入该字段
        if (!config.isThinkingEnabled()) {
            body.putObject("thinking").put("type", "disabled");
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
