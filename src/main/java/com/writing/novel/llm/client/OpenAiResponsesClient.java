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
 * p43-01: OpenAI Responses API 客户端。
 * POST {base}/responses，Authorization: Bearer；输入为 input 消息数组，输出上限为 max_output_tokens。
 * 流式事件：response.output_text.delta 增量、response.completed 携带 usage 终止。
 */
public class OpenAiResponsesClient implements LlmClient {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(OpenAiResponsesClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 测试可调小的退避基数（避免长睡） */
    public static volatile long backoffUnitMs = 1000L;
    public static volatile long rateLimitBackoffMs = 30_000L;

    private final OkHttpClient httpClient;

    public OpenAiResponsesClient() {
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(30))
                .readTimeout(Duration.ofMinutes(30))
                .callTimeout(Duration.ofMinutes(30))
                .retryOnConnectionFailure(true)
                .connectionPool(new ConnectionPool(0, 1, java.util.concurrent.TimeUnit.MILLISECONDS))
                .build();
    }

    /** 测试用：可注入自定义 OkHttpClient */
    public OpenAiResponsesClient(OkHttpClient httpClient) {
        this.httpClient = httpClient;
    }

    @Override
    public LlmResponse complete(ModelConfig config, LlmRequest request) {
        if (config.isStreaming()) return completeViaStream(config, request);
        int attempt = 0;
        while (attempt < 3) {
            Request httpRequest = buildRequest(config, request, false);
            try (Response response = httpClient.newCall(httpRequest).execute()) {
                if (response.isSuccessful()) {
                    JsonNode root = MAPPER.readTree(response.body().string());
                    if (root.has("error")) throw new RuntimeException("Responses API 错误: " + root.path("error"));
                    String content = extractText(root);
                    if (content.isBlank() && attempt < 2) {
                        log.warn("Responses API 空响应(尝试{}/3): {}，重试", attempt + 1, request.getTaskType());
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
                    throw new RateLimitException("Responses API 限流（429）重试耗尽");
                }
                if (response.code() >= 400 && response.code() < 500) {
                    throw new RuntimeException("Responses API 调用失败: HTTP " + response.code()
                            + " " + response.body().string());
                }
                if (attempt < 2) { response.close(); sleepBackoff(attempt, false); attempt++; continue; }
                throw new RuntimeException("Responses API 调用失败（重试耗尽）: HTTP " + response.code());
            } catch (IOException e) {
                if (attempt < 2) { sleepBackoff(attempt, false); attempt++; continue; }
                throw new RuntimeException("Responses API 调用异常（重试耗尽）", e);
            }
        }
        throw new RuntimeException("Responses API 调用失败：不应到达此处");
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
                        throw new RateLimitException("Responses API 限流（429）重试耗尽");
                    }
                    if (response.code() >= 400 && response.code() < 500) {
                        throw new RuntimeException("Responses API 调用失败: HTTP " + response.code() + " " + errBody);
                    }
                    if (attempt < 2) { sleepBackoff(attempt, false); attempt++; continue; }
                    throw new RuntimeException("Responses API 调用失败（重试耗尽）: HTTP " + response.code());
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
                throw new RuntimeException("Responses API 调用异常（重试耗尽）", e);
            }
        }
        throw new RuntimeException("Responses API 调用失败：不应到达此处");
    }

    @Override
    public void streamComplete(ModelConfig config, LlmRequest request, StreamHandler handler) {
        Request httpRequest = buildRequest(config, request, true);
        try (Response response = httpClient.newCall(httpRequest).execute()) {
            if (!response.isSuccessful()) {
                if (response.code() == 429) throw new RateLimitException("Responses API 限流（429）");
                throw new RuntimeException("Responses API 流式调用失败: HTTP " + response.code());
            }
            readSse(response, handler);
            handler.onComplete();
        } catch (Exception e) {
            handler.onError(e);
        }
    }

    private record Aggregated(String content, int promptTokens, int completionTokens) {}

    /** 解析 Responses API SSE：response.output_text.delta 增量；response.completed 携带 usage 并终止 */
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
                if ("[DONE]".equals(data)) break;
                JsonNode event = MAPPER.readTree(data);
                String type = event.path("type").asText("");
                if ("response.output_text.delta".equals(type)) {
                    String text = event.path("delta").asText("");
                    if (!text.isEmpty()) {
                        content.append(text);
                        if (handler != null) handler.onToken(text);
                    }
                } else if ("response.completed".equals(type)) {
                    JsonNode usage = event.path("response").path("usage");
                    promptTokens = usage.path("input_tokens").asInt(promptTokens);
                    completionTokens = usage.path("output_tokens").asInt(completionTokens);
                    break;
                } else if ("error".equals(type) || "response.failed".equals(type)) {
                    throw new RuntimeException("Responses API 流式错误事件: " + data);
                }
            }
        }
        return new Aggregated(content.toString(), promptTokens, completionTokens);
    }

    private Request buildRequest(ModelConfig config, LlmRequest request, boolean stream) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", config.getModel());
        body.put("max_output_tokens", config.getMaxTokens());
        body.put("temperature", config.getTemperature());
        body.put("stream", stream);

        ArrayNode input = body.putArray("input");
        if (request.getSystemPrompt() != null && !request.getSystemPrompt().isEmpty()) {
            String systemContent = request.getSystemPrompt();
            if (config.getStyleGuide() != null && !config.getStyleGuide().isBlank()) {
                systemContent = systemContent + "\n\n【模型风格约束】" + config.getStyleGuide();
            }
            input.addObject().put("role", "system").put("content", systemContent);
        }
        for (ChatMessage msg : request.getHistory()) {
            input.addObject().put("role", msg.role()).put("content", msg.content());
        }
        input.addObject().put("role", "user").put("content", request.getUserMessage());

        return new Request.Builder()
                .url(config.getBaseUrl().replaceAll("/+$", "") + "/responses")
                .header("Authorization", "Bearer " + (config.getApiKey() == null ? "" : config.getApiKey()))
                .header("Content-Type", "application/json")
                .post(RequestBody.create(body.toString(), MediaType.parse("application/json")))
                .build();
    }

    /** Responses API 响应：output[] 中 type=message 的 content[] 里 type=output_text 的文本拼接 */
    static String extractText(JsonNode root) {
        StringBuilder sb = new StringBuilder();
        for (JsonNode item : root.path("output")) {
            if (!"message".equals(item.path("type").asText())) continue;
            for (JsonNode part : item.path("content")) {
                if ("output_text".equals(part.path("type").asText())) sb.append(part.path("text").asText(""));
            }
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
