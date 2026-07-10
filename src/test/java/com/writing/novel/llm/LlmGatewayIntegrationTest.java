package com.writing.novel.llm;

import com.writing.novel.llm.client.OpenAiCompatibleClient;
import com.writing.novel.llm.config.LlmProperties;
import com.writing.novel.llm.config.ModelConfig;
import com.writing.novel.llm.gateway.DefaultLlmGateway;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LlmGatewayIntegrationTest {

    MockWebServer server;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    @Test
    void 全链路_配置路由_到HTTP调用_返回响应() {
        LlmProperties props = new LlmProperties();
        ModelConfig cfg = new ModelConfig();
        cfg.setBaseUrl(server.url("/v1").toString().replaceAll("/$", ""));
        cfg.setApiKey("sk-integration");
        cfg.setModel("qwen-int");
        cfg.setMaxTokens(2000);
        cfg.setTemperature(0.8);
        props.getModels().put("CHAPTER_WRITING", cfg);

        server.enqueue(new MockResponse()
                .setBody("""
                    {"choices":[{"message":{"role":"assistant","content":"张三踏入青云宗山门，剑意凛然。"},"finish_reason":"stop"}],
                     "usage":{"prompt_tokens":120,"completion_tokens":350,"total_tokens":470}}
                    """)
                .addHeader("Content-Type", "application/json"));

        DefaultLlmGateway gateway = new DefaultLlmGateway(props, new OpenAiCompatibleClient());

        LlmRequest req = LlmRequest.builder()
                .taskType(LlmTaskType.CHAPTER_WRITING)
                .systemPrompt("你是网文写作助手")
                .userMessage("写张三拜入山门的第一章")
                .build();

        LlmResponse resp = gateway.complete(req);

        assertEquals("张三踏入青云宗山门，剑意凛然。", resp.getContent());
        assertEquals(120, resp.getPromptTokens());
        assertEquals(350, resp.getCompletionTokens());
        assertEquals(470, resp.getTotalTokens());
    }

    @Test
    void 不同任务类型路由到不同模型端点() throws Exception {
        LlmProperties props = new LlmProperties();

        ModelConfig chapterCfg = new ModelConfig();
        chapterCfg.setBaseUrl(server.url("/v1").toString().replaceAll("/$", ""));
        chapterCfg.setApiKey("key-a");
        chapterCfg.setModel("model-a");
        props.getModels().put("CHAPTER_WRITING", chapterCfg);

        ModelConfig checkerCfg = new ModelConfig();
        checkerCfg.setBaseUrl(server.url("/v1").toString().replaceAll("/$", ""));
        checkerCfg.setApiKey("key-b");
        checkerCfg.setModel("model-b");
        props.getModels().put("CONTINUITY_CHECK", checkerCfg);

        DefaultLlmGateway gateway = new DefaultLlmGateway(props, new OpenAiCompatibleClient());

        server.enqueue(new MockResponse()
                .setBody("""
                    {"choices":[{"message":{"role":"assistant","content":"chapter"},"finish_reason":"stop"}],
                     "usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}
                    """)
                .addHeader("Content-Type", "application/json"));
        gateway.complete(LlmRequest.builder()
                .taskType(LlmTaskType.CHAPTER_WRITING)
                .systemPrompt("s").userMessage("u").build());

        server.enqueue(new MockResponse()
                .setBody("""
                    {"choices":[{"message":{"role":"assistant","content":"check"},"finish_reason":"stop"}],
                     "usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}
                    """)
                .addHeader("Content-Type", "application/json"));
        gateway.complete(LlmRequest.builder()
                .taskType(LlmTaskType.CONTINUITY_CHECK)
                .systemPrompt("s").userMessage("u").build());

        okhttp3.mockwebserver.RecordedRequest req1 = server.takeRequest();
        String body1 = req1.getBody().readUtf8();
        assertTrue(body1.contains("\"model\":\"model-a\""));

        okhttp3.mockwebserver.RecordedRequest req2 = server.takeRequest();
        String body2 = req2.getBody().readUtf8();
        assertTrue(body2.contains("\"model\":\"model-b\""));
    }
}
