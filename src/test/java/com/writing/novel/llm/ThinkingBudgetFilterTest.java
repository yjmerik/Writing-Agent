package com.writing.novel.llm;

import com.writing.novel.llm.client.LlmUnavailableException;
import com.writing.novel.llm.client.LlmClient;
import com.writing.novel.llm.client.OpenAiCompatibleClient;
import com.writing.novel.llm.client.StreamHandler;
import com.writing.novel.llm.config.LlmProperties;
import com.writing.novel.llm.config.ModelConfig;
import com.writing.novel.llm.gateway.DefaultLlmGateway;
import com.writing.novel.llm.LlmProviderUnavailableException;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.*;

/** NCS-185：思考模型吃满输出预算（finish=length+content空+reasoning非空）的精准识别与预算升级重试 */
class ThinkingBudgetFilterTest {

    MockWebServer server;
    OpenAiCompatibleClient client;

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

    private ModelConfig modelConfig(boolean streaming) {
        ModelConfig cfg = new ModelConfig();
        cfg.setBaseUrl(server.url("/v1").toString().replaceAll("/$", ""));
        cfg.setApiKey("sk-test");
        cfg.setModel("glm-test");
        cfg.setTemperature(0.7);
        cfg.setMaxTokens(64);
        cfg.setContextWindow(64000);
        cfg.setStreaming(streaming);
        return cfg;
    }

    private static final LlmRequest REQUEST = LlmRequest.builder()
            .systemPrompt("你是连通性探针。只回复一个字：通").userMessage("ping")
            .maxTokens(64).temperature(0.0).build();

    @Test
    void 非流式思考吃满预算识别为THINKING_BUDGET而非EMPTY_RESPONSE() {
        String body = """
                {"choices":[{"message":{"content":"","reasoning_content":"一步步推理……"},
                  "finish_reason":"length"}],
                 "usage":{"prompt_tokens":20,"completion_tokens":64,"total_tokens":84}}
                """;
        server.enqueue(new MockResponse().setBody(body).addHeader("Content-Type", "application/json"));
        var e = assertThrows(LlmUnavailableException.class, () -> client.complete(modelConfig(false), REQUEST));
        assertEquals(LlmUnavailableException.Reason.THINKING_BUDGET, e.getReason());
    }

    @Test
    void 流式思考吃满预算识别为THINKING_BUDGET() {
        StringBuilder sse = new StringBuilder();
        sse.append("data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"推理A\"}}]}\n\n");
        sse.append("data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"推理B\"}}]}\n\n");
        sse.append("data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"length\"}]}\n\n");
        sse.append("data: [DONE]\n\n");
        server.enqueue(new MockResponse().setBody(sse.toString())
                .addHeader("Content-Type", "text/event-stream"));
        ModelConfig cfg = modelConfig(true);
        cfg.setSingleAttempt(true); // 生产网关 named-provider 路径恒为 singleAttempt
        AtomicReference<Throwable> delivered = new AtomicReference<>();
        client.streamComplete(cfg, REQUEST, new StreamHandler() {
            public void onToken(String token) { }
            public void onComplete() { }
            public void onError(Throwable error) { delivered.set(error); }
        });
        assertTrue(delivered.get() instanceof LlmUnavailableException,
                "应上抛 LlmUnavailableException，实际: " + delivered.get());
        assertEquals(LlmUnavailableException.Reason.THINKING_BUDGET,
                ((LlmUnavailableException) delivered.get()).getReason());
    }

    @Test
    void 非思考场景空响应保持EMPTY_RESPONSE语义() {
        String body = """
                {"choices":[{"message":{"content":""},"finish_reason":"stop"}],
                 "usage":{"prompt_tokens":20,"completion_tokens":0,"total_tokens":20}}
                """;
        server.enqueue(new MockResponse().setBody(body).addHeader("Content-Type", "application/json"));
        ModelConfig cfg = modelConfig(false);
        cfg.setSingleAttempt(true); // 探针语义：无内部重试（重试路径最终返回空内容，由网关判空）
        var e = assertThrows(LlmUnavailableException.class, () -> client.complete(cfg, REQUEST));
        assertEquals(LlmUnavailableException.Reason.EMPTY_RESPONSE, e.getReason());
    }

    // ---- 网关：THINKING_BUDGET 同 provider ×2 升级重试，不计熔断 ----

    static LlmProperties properties() {
        var p = LlmProviderConfigurationTest.bind(LlmProviderConfigurationTest.config());
        p.getProviders().get("a").setMaxTokens(1000);
        p.getProviders().get("a").setContextWindow(64000);
        p.getRouting().setDefaultProviders(List.of("a"));
        return p;
    }

    static LlmClient gatewayClient(BiFunction<ModelConfig, LlmRequest, LlmResponse> action) {
        return new LlmClient() {
            public LlmResponse complete(ModelConfig c, LlmRequest r) { return action.apply(c, r); }
            public void streamComplete(ModelConfig c, LlmRequest r, StreamHandler h) {
                throw new UnsupportedOperationException();
            }
        };
    }

    static LlmUnavailableException thinkingBudget() {
        return new LlmUnavailableException(LlmUnavailableException.Reason.THINKING_BUDGET, 0);
    }

    @Test
    void 网关思考预算同provider升级重试成功且预算翻倍() {
        var seen = new ArrayList<Integer>();
        var g = new DefaultLlmGateway(properties(), gatewayClient((c, r) -> {
            seen.add(c.getMaxTokens());
            if (seen.size() == 1) throw thinkingBudget();
            return new LlmResponse("通", 1, 1);
        }));
        assertEquals("通", g.complete(REQUEST).getContent());
        assertEquals(List.of(1000, 2000), seen, "第二次尝试必须以 ×2 预算重发同 provider");
    }

    @Test
    void 网关思考预算升级耗尽后带原因失败并进入保护性冷却() {
        AtomicInteger attempts = new AtomicInteger();
        var seen = new ArrayList<Integer>();
        var g = new DefaultLlmGateway(properties(), gatewayClient((c, r) -> {
            attempts.incrementAndGet();
            seen.add(c.getMaxTokens());
            throw thinkingBudget();
        }));
        var e = assertThrows(LlmProviderUnavailableException.class, () -> g.complete(REQUEST));
        assertTrue(e.getMessage().contains("THINKING_BUDGET"), "穷尽信息须携带原因: " + e.getMessage());
        assertEquals(List.of(1000, 2000, 4000), seen, "同 provider 至多 2 次升级（×4 封顶）");
        // 升级耗尽 = 原有失败路径：recordFailure → 冷却（防同参失败风暴），下次调用快速失败
        assertThrows(LlmProviderUnavailableException.class, () -> g.complete(REQUEST));
        assertEquals(3, attempts.get(), "冷却期内不得再触达 provider");
    }

    @Test
    void 网关思考预算升级受窗口余量封顶() {
        var p = properties();
        p.getProviders().get("a").setContextWindow(3000);
        var seen = new ArrayList<Integer>();
        var g = new DefaultLlmGateway(p, gatewayClient((c, r) -> {
            seen.add(c.getMaxTokens());
            throw thinkingBudget();
        }));
        assertThrows(LlmProviderUnavailableException.class, () -> g.complete(REQUEST));
        assertTrue(seen.get(1) < 2000, "升级不得超过窗口余量: " + seen);
        assertTrue(seen.get(1) > 1000, "升级后仍须大于原预算: " + seen);
    }

    @Test
    void 流式未发射content时升级重试成功() {
        AtomicInteger streams = new AtomicInteger();
        LlmClient streaming = new LlmClient() {
            public LlmResponse complete(ModelConfig c, LlmRequest r) {
                throw new UnsupportedOperationException();
            }
            public void streamComplete(ModelConfig c, LlmRequest r, StreamHandler h) {
                if (streams.incrementAndGet() == 1) throw thinkingBudget();
                h.onToken("通");
                h.onComplete();
            }
        };
        StringBuilder out = new StringBuilder();
        var g = new DefaultLlmGateway(properties(), streaming);
        g.streamComplete(REQUEST, new StreamHandler() {
            public void onToken(String token) { out.append(token); }
            public void onComplete() { }
            public void onError(Throwable error) { fail("不应失败: " + error); }
        });
        assertEquals("通", out.toString());
        assertEquals(2, streams.get());
    }

    @Test
    void 流式已发射content后思考预算异常不再升级直接上抛() {
        AtomicInteger streams = new AtomicInteger();
        AtomicReference<Throwable> delivered = new AtomicReference<>();
        LlmClient streaming = new LlmClient() {
            public LlmResponse complete(ModelConfig c, LlmRequest r) {
                throw new UnsupportedOperationException();
            }
            public void streamComplete(ModelConfig c, LlmRequest r, StreamHandler h) {
                streams.incrementAndGet();
                h.onToken("已有");
                throw thinkingBudget();
            }
        };
        var g = new DefaultLlmGateway(properties(), streaming);
        g.streamComplete(REQUEST, new StreamHandler() {
            public void onToken(String token) { }
            public void onComplete() { }
            public void onError(Throwable error) { delivered.set(error); }
        });
        assertEquals(1, streams.get(), "已向 handler 发射过 content，不得升级重试");
        assertTrue(delivered.get() instanceof LlmUnavailableException
                && ((LlmUnavailableException) delivered.get()).getReason()
                        == LlmUnavailableException.Reason.THINKING_BUDGET);
    }
}
