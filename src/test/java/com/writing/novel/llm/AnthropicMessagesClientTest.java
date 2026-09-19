package com.writing.novel.llm;

import com.writing.novel.llm.client.AnthropicMessagesClient;
import com.writing.novel.llm.client.ChatMessage;
import com.writing.novel.llm.client.RateLimitException;
import com.writing.novel.llm.client.StreamHandler;
import com.writing.novel.llm.config.LlmProtocol;
import com.writing.novel.llm.config.ModelConfig;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class AnthropicMessagesClientTest {

    MockWebServer server;
    AnthropicMessagesClient client;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        client = new AnthropicMessagesClient();
        AnthropicMessagesClient.backoffUnitMs = 1L;
        AnthropicMessagesClient.rateLimitBackoffMs = 1L;
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    private ModelConfig config(boolean streaming) {
        ModelConfig cfg = new ModelConfig();
        cfg.setBaseUrl(server.url("/").toString().replaceAll("/$", ""));
        cfg.setApiKey("sk-ant-key");
        cfg.setModel("claude-sonnet-test");
        cfg.setTemperature(0.7);
        cfg.setMaxTokens(1024);
        cfg.setStreaming(streaming);
        cfg.setProtocol(LlmProtocol.ANTHROPIC_MESSAGES);
        return cfg;
    }

    private LlmRequest request() {
        return LlmRequest.builder()
                .taskType(LlmTaskType.GENERAL)
                .systemPrompt("你是小说助手")
                .userMessage("写一段")
                .history(List.of(new ChatMessage("assistant", "前文")))
                .build();
    }

    @Test
    void 非流式调用_请求形状与响应解析符合Messages规范() throws Exception {
        server.enqueue(new MockResponse()
                .setBody("""
                    {"id":"msg_1","content":[{"type":"text","text":"第一段"},{"type":"text","text":"第二段"}],
                     "usage":{"input_tokens":11,"output_tokens":7}}
                    """)
                .addHeader("Content-Type", "application/json"));

        var resp = client.complete(config(false), request());

        assertEquals("第一段第二段", resp.getContent());
        assertEquals(11, resp.getPromptTokens());
        assertEquals(7, resp.getCompletionTokens());

        RecordedRequest recorded = server.takeRequest();
        assertEquals("/v1/messages", recorded.getPath());
        assertEquals("sk-ant-key", recorded.getHeader("x-api-key"));
        assertEquals("2023-06-01", recorded.getHeader("anthropic-version"));
        String body = recorded.getBody().readUtf8();
        assertTrue(body.contains("\"model\":\"claude-sonnet-test\""));
        assertTrue(body.contains("\"max_tokens\":1024"));
        assertTrue(body.contains("\"system\":\"你是小说助手\""));
        assertTrue(body.contains("\"role\":\"assistant\""));
        assertTrue(body.contains("\"role\":\"user\""));
        assertTrue(body.contains("写一段"));
        assertFalse(body.contains("Bearer"));
    }

    @Test
    void base以v1结尾时URL不重复拼接() {
        assertEquals("https://api.anthropic.com/v1/messages",
                AnthropicMessagesClient.messagesUrl("https://api.anthropic.com/v1"));
        assertEquals("https://api.anthropic.com/v1/messages",
                AnthropicMessagesClient.messagesUrl("https://api.anthropic.com"));
        assertEquals("https://proxy.example.com/v1/messages",
                AnthropicMessagesClient.messagesUrl("https://proxy.example.com/v1/"));
    }

    @Test
    void 流式配置时走SSE聚合并解析usage() {
        server.enqueue(new MockResponse()
                .setBody("""
                    event: message_start
                    data: {"type":"message_start","message":{"usage":{"input_tokens":13}}}

                    event: content_block_delta
                    data: {"type":"content_block_delta","delta":{"type":"text_delta","text":"流式"}}

                    event: content_block_delta
                    data: {"type":"content_block_delta","delta":{"type":"text_delta","text":"正文"}}

                    event: message_delta
                    data: {"type":"message_delta","usage":{"output_tokens":5}}

                    event: message_stop
                    data: {"type":"message_stop"}
                    """)
                .addHeader("Content-Type", "text/event-stream"));

        var resp = client.complete(config(true), request());

        assertEquals("流式正文", resp.getContent());
        assertEquals(13, resp.getPromptTokens());
        assertEquals(5, resp.getCompletionTokens());
    }

    @Test
    void streamComplete逐token回调() {
        server.enqueue(new MockResponse()
                .setBody("""
                    data: {"type":"content_block_delta","delta":{"type":"text_delta","text":"A"}}

                    data: {"type":"content_block_delta","delta":{"type":"text_delta","text":"B"}}

                    data: {"type":"message_stop"}
                    """)
                .addHeader("Content-Type", "text/event-stream"));

        StringBuilder tokens = new StringBuilder();
        AtomicReference<Throwable> error = new AtomicReference<>();
        client.streamComplete(config(true), request(), new StreamHandler() {
            public void onToken(String token) { tokens.append(token); }
            public void onComplete() { }
            public void onError(Throwable e) { error.set(e); }
        });

        assertNull(error.get());
        assertEquals("AB", tokens.toString());
    }

    @Test
    void 限流429重试耗尽抛RateLimitException() {
        server.enqueue(new MockResponse().setResponseCode(429).setBody("{}"));
        server.enqueue(new MockResponse().setResponseCode(429).setBody("{}"));
        server.enqueue(new MockResponse().setResponseCode(429).setBody("{}"));

        assertThrows(RateLimitException.class, () -> client.complete(config(false), request()));
        assertEquals(3, server.getRequestCount());
    }
}
