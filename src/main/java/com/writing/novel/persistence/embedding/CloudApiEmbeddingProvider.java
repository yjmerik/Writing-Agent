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
        return embedBatch(java.util.List.of(text)).get(0);
    }

    /** D2: 批量嵌入——input 传 array，按 response index 字段排序返回 */
    @Override
    public java.util.List<float[]> embedBatch(java.util.List<String> texts) {
        if (texts.isEmpty()) return java.util.List.of();
        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", model);
        var arr = body.putArray("input");
        for (String t : texts) arr.add(t);

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
            int n = root.path("data").size();
            java.util.Map<Integer, float[]> byIndex = new java.util.TreeMap<>();
            for (int i = 0; i < n; i++) {
                JsonNode item = root.path("data").get(i);
                JsonNode emb = item.path("embedding");
                float[] vector = new float[emb.size()];
                for (int j = 0; j < vector.length; j++) vector[j] = (float) emb.get(j).asDouble();
                byIndex.put(item.path("index").asInt(i), vector);
                if (dimension < 0) dimension = vector.length;
            }
            java.util.List<float[]> result = new java.util.ArrayList<>();
            for (int i = 0; i < texts.size(); i++) {
                float[] v = byIndex.get(i);
                if (v == null) throw new RuntimeException("Embedding 响应缺少 index=" + i);
                result.add(v);
            }
            return result;
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
