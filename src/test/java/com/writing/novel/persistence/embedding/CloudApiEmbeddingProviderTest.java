package com.writing.novel.persistence.embedding;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CloudApiEmbeddingProviderTest {

    MockWebServer server;
    CloudApiEmbeddingProvider provider;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        provider = new CloudApiEmbeddingProvider(
                server.url("/v1").toString().replaceAll("/$", ""),
                "sk-test",
                "text-embedding-v2"
        );
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    @Test
    void embed返回API返回的向量() throws Exception {
        String mockResponse = """
                {
                  "data": [
                    {"embedding": [0.1, 0.2, 0.3], "index": 0}
                  ],
                  "model": "text-embedding-v2",
                  "usage": {"prompt_tokens": 5, "total_tokens": 5}
                }
                """;
        server.enqueue(new MockResponse().setBody(mockResponse).addHeader("Content-Type", "application/json"));

        float[] vector = provider.embed("玄黄大陆");

        assertEquals(3, vector.length);
        assertEquals(0.1f, vector[0], 0.0001);
        assertEquals(0.2f, vector[1], 0.0001);
        assertEquals(0.3f, vector[2], 0.0001);
    }

    @Test
    void dimension从首次调用获取() throws Exception {
        String mockResponse = """
                {"data":[{"embedding":[1.0,2.0,3.0,4.0],"index":0}],"model":"m","usage":{"total_tokens":1}}
                """;
        server.enqueue(new MockResponse().setBody(mockResponse).addHeader("Content-Type", "application/json"));

        provider.embed("test");
        assertEquals(4, provider.dimension());
    }

    @Test
    void 请求携带正确的model与input() throws Exception {
        server.enqueue(new MockResponse()
                .setBody("""
                    {"data":[{"embedding":[0.5],"index":0}],"model":"text-embedding-v2","usage":{"total_tokens":1}}
                    """)
                .addHeader("Content-Type", "application/json"));

        provider.embed("测试文本");

        okhttp3.mockwebserver.RecordedRequest recorded = server.takeRequest();
        String body = recorded.getBody().readUtf8();
        assertTrue(body.contains("\"model\":\"text-embedding-v2\""));
        assertTrue(body.contains("测试文本"));
        assertEquals("Bearer sk-test", recorded.getHeader("Authorization"));
        assertTrue(recorded.getPath().endsWith("/embeddings"));
    }
}
