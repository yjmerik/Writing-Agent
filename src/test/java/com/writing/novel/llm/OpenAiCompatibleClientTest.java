package com.writing.novel.llm;

import com.writing.novel.llm.client.ChatMessage;
import com.writing.novel.llm.client.LlmClient;
import com.writing.novel.llm.client.OpenAiCompatibleClient;
import com.writing.novel.llm.config.ModelConfig;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OpenAiCompatibleClientTest {

    MockWebServer server;
    LlmClient client;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        client = new OpenAiCompatibleClient();
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    private ModelConfig modelConfig() {
        ModelConfig cfg = new ModelConfig();
        cfg.setBaseUrl(server.url("/v1").toString().replaceAll("/$", ""));
        cfg.setApiKey("sk-test");
        cfg.setModel("qwen-test");
        cfg.setTemperature(0.7);
        cfg.setMaxTokens(100);
        return cfg;
    }

    @Test
    void 非流式调用返回生成内容与token统计() throws Exception {
        String mockResponse = """
                {
                  "choices": [
                    {"message": {"role": "assistant", "content": "生成的正文内容"}, "finish_reason": "stop"}
                  ],
                  "usage": {"prompt_tokens": 50, "completion_tokens": 30, "total_tokens": 80}
                }
                """;
        server.enqueue(new MockResponse().setBody(mockResponse).addHeader("Content-Type", "application/json"));

        LlmRequest req = LlmRequest.builder()
                .systemPrompt("你是助手")
                .userMessage("写点东西")
                .history(List.of())
                .build();

        LlmResponse resp = client.complete(modelConfig(), req);

        assertEquals("生成的正文内容", resp.getContent());
        assertEquals(50, resp.getPromptTokens());
        assertEquals(30, resp.getCompletionTokens());
    }

    @Test
    void 请求体包含正确的消息序列() throws Exception {
        server.enqueue(new MockResponse()
                .setBody("""
                    {"choices":[{"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}],
                     "usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}
                    """)
                .addHeader("Content-Type", "application/json"));

        LlmRequest req = LlmRequest.builder()
                .systemPrompt("系统提示")
                .userMessage("用户消息")
                .history(List.of(new ChatMessage("assistant", "历史回复")))
                .build();

        client.complete(modelConfig(), req);

        okhttp3.mockwebserver.RecordedRequest recorded = server.takeRequest();
        assertNotNull(recorded);
        String body = recorded.getBody().readUtf8();
        assertTrue(body.contains("\"role\":\"system\""));
        assertTrue(body.contains("系统提示"));
        assertTrue(body.contains("\"role\":\"assistant\""));
        assertTrue(body.contains("历史回复"));
        assertTrue(body.contains("\"role\":\"user\""));
        assertTrue(body.contains("用户消息"));
        assertEquals("Bearer sk-test", recorded.getHeader("Authorization"));
    }
}
