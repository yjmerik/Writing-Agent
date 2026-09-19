package com.writing.novel.llm;

import com.writing.novel.llm.client.AnthropicMessagesClient;
import com.writing.novel.llm.client.OpenAiCompatibleClient;
import com.writing.novel.llm.client.OpenAiResponsesClient;
import com.writing.novel.llm.client.ProtocolRoutingLlmClient;
import com.writing.novel.llm.config.LlmProtocol;
import com.writing.novel.llm.config.ModelConfig;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProtocolRoutingLlmClientTest {

    MockWebServer server;
    ProtocolRoutingLlmClient client;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        // 按 path 分发对应协议格式的响应，验证分发器把请求送到了正确的协议端点
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                String path = request.getPath();
                if (path.endsWith("/chat/completions")) return new MockResponse()
                        .setBody("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"来自chat协议\"}}],\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1}}")
                        .addHeader("Content-Type", "application/json");
                if (path.endsWith("/responses")) return new MockResponse()
                        .setBody("{\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"来自responses协议\"}]}],\"usage\":{\"input_tokens\":2,\"output_tokens\":2}}")
                        .addHeader("Content-Type", "application/json");
                if (path.endsWith("/messages")) return new MockResponse()
                        .setBody("{\"content\":[{\"type\":\"text\",\"text\":\"来自anthropic协议\"}],\"usage\":{\"input_tokens\":3,\"output_tokens\":3}}")
                        .addHeader("Content-Type", "application/json");
                return new MockResponse().setResponseCode(404);
            }
        });
        server.start();
        client = new ProtocolRoutingLlmClient(new OpenAiCompatibleClient(),
                new OpenAiResponsesClient(), new AnthropicMessagesClient());
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    private ModelConfig config(LlmProtocol protocol) {
        ModelConfig cfg = new ModelConfig();
        cfg.setBaseUrl(server.url("/").toString().replaceAll("/$", ""));
        cfg.setApiKey("sk-x");
        cfg.setModel("m");
        cfg.setMaxTokens(64);
        cfg.setStreaming(false);
        cfg.setProtocol(protocol);
        return cfg;
    }

    private LlmRequest request() {
        return LlmRequest.builder().taskType(LlmTaskType.GENERAL)
                .userMessage("hi").build();
    }

    @Test
    void 默认协议分发到OpenAI兼容客户端() {
        var resp = client.complete(config(null), request());
        assertEquals("来自chat协议", resp.getContent());
    }

    @Test
    void chatCompletions协议分发到OpenAI兼容客户端() {
        var resp = client.complete(config(LlmProtocol.OPENAI_CHAT_COMPLETIONS), request());
        assertEquals("来自chat协议", resp.getContent());
    }

    @Test
    void responses协议分发到Responses客户端() {
        var resp = client.complete(config(LlmProtocol.OPENAI_RESPONSES), request());
        assertEquals("来自responses协议", resp.getContent());
    }

    @Test
    void anthropic协议分发到Messages客户端() {
        var resp = client.complete(config(LlmProtocol.ANTHROPIC_MESSAGES), request());
        assertEquals("来自anthropic协议", resp.getContent());
    }
}
