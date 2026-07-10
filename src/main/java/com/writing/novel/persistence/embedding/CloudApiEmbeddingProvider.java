package com.writing.novel.persistence.embedding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.writing.novel.spi.EmbeddingProvider;
import okhttp3.*;

import java.io.IOException;
import java.time.Duration;

/**
 * 真实云 Embedding API 实现（OpenAI 兼容 /v1/embeddings）。
 * 兼容通义/DashScope/DeepSeek/OpenAI 的 embedding 端点。
 */
public class CloudApiEmbeddingProvider implements EmbeddingProvider {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final OkHttpClient httpClient;
    private volatile int dimension = -1;

    public CloudApiEmbeddingProvider(String baseUrl, String apiKey, String model) {
        this(baseUrl, apiKey, model, new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(30))
                .readTimeout(Duration.ofSeconds(60))
                .build());
    }

    public CloudApiEmbeddingProvider(String baseUrl, String apiKey, String model, OkHttpClient httpClient) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.httpClient = httpClient;
    }

    @Override
    public float[] embed(String text) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", model);
        body.put("input", text);

        Request request = new Request.Builder()
                .url(baseUrl + "/embeddings")
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .post(RequestBody.create(body.toString(), MediaType.parse("application/json")))
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new RuntimeException("Embedding 调用失败: HTTP " + response.code()
                        + " " + response.body().string());
            }
            JsonNode root = MAPPER.readTree(response.body().string());
            JsonNode embeddingNode = root.path("data").path(0).path("embedding");
            float[] vector = new float[embeddingNode.size()];
            for (int i = 0; i < vector.length; i++) {
                vector[i] = (float) embeddingNode.get(i).asDouble();
            }
            if (dimension < 0) {
                dimension = vector.length;
            }
            return vector;
        } catch (IOException e) {
            throw new RuntimeException("Embedding 调用异常", e);
        }
    }

    @Override
    public int dimension() {
        if (dimension < 0) {
            embed("dimension probe");
        }
        return dimension;
    }
}
