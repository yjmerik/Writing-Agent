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

    public OpenAiCompatibleClient() {
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(30))
                .readTimeout(Duration.ofMinutes(5))
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

        Request httpRequest = new Request.Builder()
                .url(url)
                .header("Authorization", "Bearer " + config.getApiKey())
                .header("Content-Type", "application/json")
                .post(RequestBody.create(body.toString(), MediaType.parse("application/json")))
                .build();

        try (Response response = httpClient.newCall(httpRequest).execute()) {
            if (!response.isSuccessful()) {
                throw new RuntimeException("LLM 调用失败: HTTP " + response.code()
                        + " " + response.body().string());
            }
            JsonNode root = MAPPER.readTree(response.body().string());
            String content = root.path("choices").path(0)
                    .path("message").path("content").asText();
            int promptTokens = root.path("usage").path("prompt_tokens").asInt();
            int completionTokens = root.path("usage").path("completion_tokens").asInt();
            return new LlmResponse(content, promptTokens, completionTokens);
        } catch (IOException e) {
            throw new RuntimeException("LLM 调用异常", e);
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
            sys.put("content", request.getSystemPrompt());
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
