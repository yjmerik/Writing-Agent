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
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * p43-01: Anthropic Messages API 客户端。
 * POST {base}/v1/messages（base 已以 /v1 结尾则拼 /messages），鉴权 x-api-key + anthropic-version。
 * 非流式与流式（SSE 聚合/逐 token）均支持；429/5xx 指数退避重试，与 OpenAiCompatibleClient 语义对齐。
 */
public class AnthropicMessagesClient implements LlmClient {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AnthropicMessagesClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    public static final String ANTHROPIC_VERSION = "2023-06-01";

    /** 测试可调小的退避基数（避免长睡） */
    public static volatile long backoffUnitMs = 1000L;
    public static volatile long rateLimitBackoffMs = 30_000L;

    private final OkHttpClient httpClient;

    public AnthropicMessagesClient() {
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(30))
                .readTimeout(Duration.ofMinutes(30))
                .callTimeout(Duration.ofMinutes(30))
                .retryOnConnectionFailure(true)
                .connectionPool(new ConnectionPool(0, 1, java.util.concurrent.TimeUnit.MILLISECONDS))
                .build();
    }

    /** 测试用：可注入自定义 OkHttpClient */
    public AnthropicMessagesClient(OkHttpClient httpClient) {
        this.httpClient = httpClient;
    }

    /** base 以 /v1 结尾则不再重复拼接（兼容 base_url 带 /v1 与不带两种写法） */
    public static String messagesUrl(String baseUrl) {
        String base = baseUrl.replaceAll("/+$", "");
        return base.endsWith("/v1") ? base + "/messages" : base + "/v1/messages";
    }

    @Override
    public LlmResponse complete(ModelConfig config, LlmRequest request) {
        // 流式配置走 SSE 聚合（长生成保活），对上层透明
        if (config.isStreaming()) return completeViaStream(config, request);
        int attempt = 0;
        while (attempt < 3) {
            Request httpRequest = buildRequest(config, request, false);
            try (Response response = httpClient.newCall(httpRequest).execute()) {
                if (response.isSuccessful()) {
                    JsonNode root = MAPPER.readTree(response.body().string());
                    String content = extractContent(root);
                    if (content.isBlank() && attempt < 2) {
                        log.warn("Anthropic 空响应(尝试{}/3): {}，重试", attempt + 1, request.getTaskType());
                        sleepBackoff(attempt, false);
                        attempt++;
                        continue;
                    }
                    return new LlmResponse(content,
                            root.path("usage").path("input_tokens").asInt(),
                            root.path("usage").path("output_tokens").asInt());
                }
                if (response.code() == 429) {
                    if (attempt < 2) { response.close(); sleepBackoff(attempt, true); attempt++; continue; }
                    throw new RateLimitException("Anthropic 限流（429）重试耗尽");
                }
                if (response.code() >= 400 && response.code() < 500) {
                    throw new RuntimeException("Anthropic 调用失败: HTTP " + response.code()
                            + " " + response.body().string());
                }
                if (attempt < 2) { response.close(); sleepBackoff(attempt, false); attempt++; continue; }
                throw new RuntimeException("Anthropic 调用失败（重试耗尽）: HTTP " + response.code());
            } catch (IOException e) {
                if (attempt < 2) { sleepBackoff(attempt, false); attempt++; continue; }
                throw new RuntimeException("Anthropic 调用异常（重试耗尽）", e);
            }
        }
        throw new RuntimeException("Anthropic 调用失败：不应到达此处");
    }

    private LlmResponse completeViaStream(ModelConfig config, LlmRequest request) {
        int attempt = 0;
        while (attempt < 3) {
            Request httpRequest = buildRequest(config, request, true);
            try (Response response = httpClient.newCall(httpRequest).execute()) {
                if (!response.isSuccessful()) {
                    String errBody = response.body() != null ? response.body().string() : "";
                    if (response.code() == 429) {
                        if (attempt < 2) { sleepBackoff(attempt, true); attempt++; continue; }
                        throw new RateLimitException("Anthropic 限流（429）重试耗尽");
                    }
                    if (response.code() >= 400 && response.code() < 500) {
                        throw new RuntimeException("Anthropic 调用失败: HTTP " + response.code() + " " + errBody);
                    }
                    if (attempt < 2) { sleepBackoff(attempt, false); attempt++; continue; }
                    throw new RuntimeException("Anthropic 调用失败（重试耗尽）: HTTP " + response.code());
                }
                Aggregated result = readSse(response, null);
                if (result.content.isBlank() && attempt < 2) {
                    sleepBackoff(attempt, false);
                    attempt++;
                    continue;
                }
                return new LlmResponse(result.content, result.promptTokens, result.completionTokens);
            } catch (IOException e) {
                if (attempt < 2) { sleepBackoff(attempt, false); attempt++; continue; }
                throw new RuntimeException("Anthropic 调用异常（重试耗尽）", e);
            }
        }
        throw new RuntimeException("Anthropic 调用失败：不应到达此处");
    }

    @Override
    public void streamComplete(ModelConfig config, LlmRequest request, StreamHandler handler) {
        Request httpRequest = buildRequest(config, request, true);
        try (Response response = httpClient.newCall(httpRequest).execute()) {
            if (!response.isSuccessful()) {
                if (response.code() == 429) throw new RateLimitException("Anthropic 限流（429）");
                throw new RuntimeException("Anthropic 流式调用失败: HTTP " + response.code());
            }
            readSse(response, handler);
            handler.onComplete();
        } catch (Exception e) {
            handler.onError(e);
        }
    }

    private record Aggregated(String content, int promptTokens, int completionTokens) {}

    /** 解析 Anthropic SSE：content_block_delta 增量、message_start/message_delta 携带 usage */
    private Aggregated readSse(Response response, StreamHandler handler) throws IOException {
        StringBuilder content = new StringBuilder();
        int promptTokens = 0, completionTokens = 0;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(response.body().byteStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data:")) continue;
                String data = line.substring(5).trim();
                if (data.isEmpty()) continue;
                JsonNode event = MAPPER.readTree(data);
                String type = event.path("type").asText("");
                if ("content_block_delta".equals(type)) {
                    String text = event.path("delta").path("text").asText("");
                    if (!text.isEmpty()) {
                        content.append(text);
                        if (handler != null) handler.onToken(text);
                    }
                } else if ("message_start".equals(type)) {
                    promptTokens = event.path("message").path("usage").path("input_tokens").asInt(promptTokens);
                } else if ("message_delta".equals(type)) {
                    JsonNode usage = event.path("usage");
                    promptTokens = usage.path("input_tokens").asInt(promptTokens);
                    completionTokens = usage.path("output_tokens").asInt(completionTokens);
                } else if ("message_stop".equals(type)) {
                    break;
                } else if ("error".equals(type)) {
                    throw new RuntimeException("Anthropic 流式错误事件: "
                            + event.path("error").path("message").asText(data));
                }
            }
        }
        return new Aggregated(content.toString(), promptTokens, completionTokens);
    }

    private Request buildRequest(ModelConfig config, LlmRequest request, boolean stream) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", config.getModel());
        body.put("max_tokens", config.getMaxTokens());
        body.put("temperature", config.getTemperature());
        body.put("stream", stream);
        String system = request.getSystemPrompt() == null ? "" : request.getSystemPrompt();
        if (config.getStyleGuide() != null && !config.getStyleGuide().isBlank()) {
            system = system + "\n\n【模型风格约束】" + config.getStyleGuide();
        }
        if (!system.isEmpty()) body.put("system", system);

        ArrayNode messages = body.putArray("messages");
        for (ChatMessage msg : request.getHistory()) {
            messages.addObject().put("role", msg.role()).put("content", msg.content());
        }
        messages.addObject().put("role", "user").put("content", request.getUserMessage());

        return new Request.Builder()
                .url(messagesUrl(config.getBaseUrl()))
                .header("x-api-key", config.getApiKey() == null ? "" : config.getApiKey())
                .header("anthropic-version", ANTHROPIC_VERSION)
                .header("Content-Type", "application/json")
                .post(RequestBody.create(body.toString(), MediaType.parse("application/json")))
                .build();
    }

    /** Messages API 响应：content 数组中 type=text 的块拼接为正文 */
    static String extractContent(JsonNode root) {
        StringBuilder sb = new StringBuilder();
        for (JsonNode block : root.path("content")) {
            if ("text".equals(block.path("type").asText())) sb.append(block.path("text").asText(""));
        }
        return sb.toString();
    }

    private static void sleepBackoff(int attempt, boolean rateLimited) {
        long unit = rateLimited ? rateLimitBackoffMs : backoffUnitMs;
        try { Thread.sleep(unit * (attempt + 1)); } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
