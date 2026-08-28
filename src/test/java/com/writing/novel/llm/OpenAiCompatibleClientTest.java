package com.writing.novel.llm;

import com.writing.novel.llm.client.ChatMessage;
import com.writing.novel.llm.client.LlmClient;
import com.writing.novel.llm.client.OpenAiCompatibleClient;
import com.writing.novel.llm.config.ModelConfig;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import java.util.concurrent.TimeUnit;
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
        cfg.setStreaming(false); // 现有用例验证非流式路径（重试/审查语义）
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

    /** p16-e2e-fix: GLM 内容审查(1301)首次拦截时温和化 system prompt 原位重试，不消耗重试次数 */
    @Test
    void 内容审查首次拦截温和化重试成功() throws Exception {
        // 第一次：1301 审查拦截；第二次：成功
        server.enqueue(new MockResponse().setResponseCode(400)
                .setBody("{\"error\":{\"code\":\"1301\",\"message\":\"系统检测到敏感内容\"}}"));
        server.enqueue(new MockResponse().setResponseCode(200)
                .setBody("{\"choices\":[{\"message\":{\"content\":\"OK\"}}],\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":2}}"));
        var req = com.writing.novel.llm.LlmRequest.builder()
                .taskType(com.writing.novel.llm.LlmTaskType.CHARACTER_DESIGN)
                .systemPrompt("设计反派人物").userMessage("开始").build();
        var resp = client.complete(modelConfig(), req);
        assertEquals("OK", resp.getContent());
        assertEquals(2, server.getRequestCount(), "应恰好 2 次请求（拦截+温和化重试）");
        // 第二次请求（FIFO 后出）的 system 应含安全提示
        String firstBody = server.takeRequest(1, TimeUnit.SECONDS).getBody().readUtf8();
        String secondBody = server.takeRequest(1, TimeUnit.SECONDS).getBody().readUtf8();
        assertFalse(firstBody.contains("安全提示"), "首次请求不应含安全提示");
        assertTrue(secondBody.contains("安全提示") || secondBody.contains("克制"), "重试请求应注入温和化提示");
    }

    /** p16-e2e-fix: 温和化后仍被拦截才抛 ContentFilterException */
    @Test
    void 温和化重试仍拦截则抛ContentFilterException() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(400)
                .setBody("{\"error\":{\"code\":\"1301\",\"message\":\"敏感\"}}"));
        server.enqueue(new MockResponse().setResponseCode(400)
                .setBody("{\"error\":{\"code\":\"1301\",\"message\":\"仍敏感\"}}"));
        var req = com.writing.novel.llm.LlmRequest.builder()
                .taskType(com.writing.novel.llm.LlmTaskType.CHARACTER_DESIGN)
                .systemPrompt("设计人物").userMessage("开始").build();
        assertThrows(com.writing.novel.llm.client.ContentFilterException.class,
                () -> client.complete(modelConfig(), req));
        assertEquals(2, server.getRequestCount(), "温和化只重试一次，不无限循环");
    }



    /** p16-e2e: streaming=true 时 complete() 走 SSE 聚合，返回完整正文与 usage */
    @Test
    void 流式路径聚合完整正文与usage() throws Exception {
        String sse = "data: {\"choices\":[{\"delta\":{\"content\":\"你\"}}]}\n\n"
                + "data: {\"choices\":[{\"delta\":{\"content\":\"好\"}}],\"usage\":{\"prompt_tokens\":7,\"completion_tokens\":3}}\n\n"
                + "data: [DONE]\n\n";
        server.enqueue(new MockResponse().setBody(sse).addHeader("Content-Type", "text/event-stream"));
        var cfg = modelConfig();
        cfg.setStreaming(true);
        var req = com.writing.novel.llm.LlmRequest.builder()
                .taskType(com.writing.novel.llm.LlmTaskType.CHAPTER_WRITING)
                .systemPrompt("写作").userMessage("开始").build();
        var resp = client.complete(cfg, req);
        assertEquals("你好", resp.getContent());
        assertEquals(7, resp.getPromptTokens());
        assertEquals(3, resp.getCompletionTokens());
        var recorded = server.takeRequest(1, TimeUnit.SECONDS);
        String body = recorded.getBody().readUtf8();
        assertTrue(body.contains("\"stream\":true"), "流式请求体应 stream=true");
        assertTrue(body.contains("include_usage"), "流式请求应带 stream_options.include_usage");
    }
}
