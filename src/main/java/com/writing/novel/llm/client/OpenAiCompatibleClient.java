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

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final OkHttpClient httpClient;

    /** p10-w1-05: 退避时长（可被测试调小避免长睡）——5xx/网络 基数 */
    static volatile long backoffUnitMs = 1000L;
    /** p10-w1-05: 429 限流退避基数（更长：指数到分钟级） */
    static volatile long rateLimitBackoffMs = 5000L;

    public OpenAiCompatibleClient() {
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(30))
                .readTimeout(Duration.ofMinutes(5))
                .callTimeout(Duration.ofMinutes(3)) // 单次请求最长 3 分钟
                .retryOnConnectionFailure(true)      // 网络抖动自动重试
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

        // 429/5xx 重试（3次，指数退避）
        for (int attempt = 0; attempt < 3; attempt++) {
            Request httpRequest = new Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer " + config.getApiKey())
                    .header("Content-Type", "application/json")
                    .post(RequestBody.create(body.toString(), MediaType.parse("application/json")))
                    .build();

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
                    // 内容审查错误抛专用异常（可恢复，上层可用温和 prompt 重试）
                    if (errBody.contains("1301") || errBody.contains("content_filter")
                            || errBody.contains("Content Filter") || errBody.contains("contentFilter")) {
                        throw new ContentFilterException("内容审查拦截: " + errBody);
                    }
                    throw new RuntimeException("LLM 调用失败: HTTP " + response.code() + " " + errBody);
                }
                // 429/5xx 可重试
                if (attempt < 2) {
                    boolean rateLimited = response.code() == 429;
                    response.close();
                    sleepBackoff(attempt, rateLimited);
                    continue;
                }
                // p10-w1-05: 429 重试耗尽 → RateLimitException（上层降级到 fallback 模型，不计永久失败）
                if (response.code() == 429) {
                    throw new RateLimitException("LLM 限流（429）重试耗尽: " + response.code());
                }
                throw new RuntimeException("LLM 调用失败（重试耗尽）: HTTP " + response.code()
                        + " " + response.body().string());
            } catch (IOException e) {
                if (attempt < 2) {
                    sleepBackoff(attempt, false);
                    continue;
                }
                throw new RuntimeException("LLM 调用异常（重试耗尽）", e);
            }
        }
        throw new RuntimeException("LLM 调用失败：不应到达此处");
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
