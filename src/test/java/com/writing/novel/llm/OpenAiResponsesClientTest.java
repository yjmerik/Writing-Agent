package com.writing.novel.llm;

import com.writing.novel.llm.client.ChatMessage;
import com.writing.novel.llm.client.OpenAiResponsesClient;
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

class OpenAiResponsesClientTest {

    MockWebServer server;
    OpenAiResponsesClient client;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        client = new OpenAiResponsesClient();
        OpenAiResponsesClient.backoffUnitMs = 1L;
        OpenAiResponsesClient.rateLimitBackoffMs = 1L;
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    private ModelConfig config(boolean streaming) {
        ModelConfig cfg = new ModelConfig();
        cfg.setBaseUrl(server.url("/v1").toString().replaceAll("/$", ""));
        cfg.setApiKey("sk-resp-key");
        cfg.setModel("gpt-test");
        cfg.setTemperature(0.5);
        cfg.setMaxTokens(2048);
        cfg.setStreaming(streaming);
        cfg.setProtocol(LlmProtocol.OPENAI_RESPONSES);
        return cfg;
    }

    private LlmRequest request() {
        return LlmRequest.builder()
                .taskType(LlmTaskType.GENERAL)
                .systemPrompt("你是小说助手")
                .userMessage("继续写")
                .history(List.of(new ChatMessage("user", "开头")))
                .build();
    }

    @Test
    void 非流式调用_请求形状与响应解析符合Responses规范() throws Exception {
        server.enqueue(new MockResponse()
                .setBody("""
                    {"id":"resp_1","output":[{"type":"reasoning"},{"type":"message","content":[{"type":"output_text","text":"答案"}]}],
                     "usage":{"input_tokens":21,"output_tokens":9}}
                    """)
                .addHeader("Content-Type", "application/json"));

        var resp = client.complete(config(false), request());

        assertEquals("答案", resp.getContent());
        assertEquals(21, resp.getPromptTokens());
        assertEquals(9, resp.getCompletionTokens());

        RecordedRequest recorded = server.takeRequest();
        assertEquals("/v1/responses", recorded.getPath());
        assertEquals("Bearer sk-resp-key", recorded.getHeader("Authorization"));
        String body = recorded.getBody().readUtf8();
        assertTrue(body.contains("\"model\":\"gpt-test\""));
        assertTrue(body.contains("\"max_output_tokens\":2048"));
        assertTrue(body.contains("\"role\":\"system\""));
        assertTrue(body.contains("\"role\":\"user\""));
        assertTrue(body.contains("继续写"));
    }

    @Test
    void 流式配置时走SSE聚合并解析completed事件usage() {
        server.enqueue(new MockResponse()
                .setBody("""
                    data: {"type":"response.output_text.delta","delta":"第一章"}

                    data: {"type":"response.output_text.delta","delta":"开头"}

                    data: {"type":"response.completed","response":{"usage":{"input_tokens":33,"output_tokens":12}}}
                    """)
                .addHeader("Content-Type", "text/event-stream"));

        var resp = client.complete(config(true), request());

        assertEquals("第一章开头", resp.getContent());
        assertEquals(33, resp.getPromptTokens());
        assertEquals(12, resp.getCompletionTokens());
    }

    @Test
    void streamComplete逐token回调() {
        server.enqueue(new MockResponse()
                .setBody("""
                    data: {"type":"response.output_text.delta","delta":"X"}

                    data: {"type":"response.output_text.delta","delta":"Y"}

                    data: {"type":"response.completed"}
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
        assertEquals("XY", tokens.toString());
    }
}
