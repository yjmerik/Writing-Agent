package com.writing.novel.llm;

import com.writing.novel.llm.client.LlmClient;
import com.writing.novel.llm.client.StreamHandler;
import com.writing.novel.llm.config.LlmProperties;
import com.writing.novel.llm.config.ModelConfig;
import com.writing.novel.llm.gateway.DefaultLlmGateway;
import com.writing.novel.llm.gateway.RequestBudgetExceededException;
import com.writing.novel.telemetry.EventLog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * NCS-002（方案 §10/R11/F12）：最终请求窗口核算。
 * 发送前按目标模型 contextWindow/maxTokens 核算序列化输入+输出预留+安全余量；
 * 超限请求不触达客户端、不静默截断——记 REQUEST_BUDGET_EXCEEDED 事件并明确失败；
 * provider 切换（含 fallback）后对新模型重新核算（小窗路由拒绝后可落到大窗路由）。
 */
class FinalRequestBudgetTest {

    private EventLog.Listener listener;
    private final List<String> events = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        listener = e -> events.add(String.valueOf(e.get("event")));
        EventLog.subscribe(listener);
    }

    @AfterEach
    void tearDown() {
        EventLog.unsubscribe(listener);
    }

    private boolean saw(String event) {
        return events.stream().anyMatch(event::equals);
    }

    /** 两 provider 路由（a→b），窗口可独立设定；沿用 LlmProviderConfigurationTest 绑定模式 */
    private static LlmProperties properties(int windowA, int windowB) {
        var m = new java.util.HashMap<String, Object>();
        m.put("llm.providers.a.base-url", "https://example.org/v1");
        m.put("llm.providers.a.api-key", "key-a");
        m.put("llm.providers.a.model", "model-a");
        m.put("llm.providers.a.context-window", windowA);
        m.put("llm.providers.a.max-tokens", 4000);
        m.put("llm.providers.b.base-url", "https://example.org/v1");
        m.put("llm.providers.b.api-key", "key-b");
        m.put("llm.providers.b.model", "model-b");
        m.put("llm.providers.b.context-window", windowB);
        m.put("llm.providers.b.max-tokens", 4000);
        m.put("llm.routing.default-providers[0]", "a");
        m.put("llm.routing.default-providers[1]", "b");
        return LlmProviderConfigurationTest.bind(m);
    }

    /** 生成约 n 字符的中文正文（估算 tokens ≈ n / 1.3，向上取整） */
    private static String text(int chars) {
        return "密信焚毁后的长段正文。".repeat(chars / 9 + 1);
    }

    private static LlmRequest request(String systemPrompt, String userMessage) {
        return LlmRequest.builder()
                .taskType(LlmTaskType.GENERAL)
                .systemPrompt(systemPrompt)
                .userMessage(userMessage)
                .maxTokens(4000)
                .temperature(0.5)
                .build();
    }

    @Test
    void 超窗请求被拒_不触达客户端_不静默截断() {
        List<String> called = new ArrayList<>();
        var gateway = new DefaultLlmGateway(properties(8000, 8000), new LlmClient() {
            public LlmResponse complete(ModelConfig c, LlmRequest r) {
                called.add(c.getModel());
                return new LlmResponse("should-not-happen", 1, 1);
            }
            public void streamComplete(ModelConfig c, LlmRequest r, StreamHandler h) {
                throw new UnsupportedOperationException();
            }
        });
        // 输入约 6000 tokens + 输出 4000 + 安全余量 400 > 8000
        var e = assertThrows(RequestBudgetExceededException.class,
                () -> gateway.complete(request(text(7000), text(1000))));
        assertTrue(e.getMessage().contains("8000"), "失败信息应包含窗口大小");
        assertTrue(called.isEmpty(), "超窗请求不得触达任何 provider");
        assertTrue(saw("REQUEST_BUDGET_EXCEEDED"), "必须留 REQUEST_BUDGET_EXCEEDED 事件");
    }

    @Test
    void 窗口内请求正常通过() {
        List<String> called = new ArrayList<>();
        var gateway = new DefaultLlmGateway(properties(16000, 16000), new LlmClient() {
            public LlmResponse complete(ModelConfig c, LlmRequest r) {
                called.add(c.getModel());
                return new LlmResponse("ok", 1, 1);
            }
            public void streamComplete(ModelConfig c, LlmRequest r, StreamHandler h) {
                throw new UnsupportedOperationException();
            }
        });
        LlmResponse resp = gateway.complete(request(text(3000), text(1000)));
        assertEquals("ok", resp.getContent());
        assertEquals(List.of("model-a"), called);
        assertFalse(saw("REQUEST_BUDGET_EXCEEDED"));
    }

    @Test
    void 小窗路由拒绝后落到大窗路由_切换后重新核算() {
        List<String> called = new ArrayList<>();
        var gateway = new DefaultLlmGateway(properties(8000, 32000), new LlmClient() {
            public LlmResponse complete(ModelConfig c, LlmRequest r) {
                called.add(c.getModel());
                return new LlmResponse("from-b", 1, 1);
            }
            public void streamComplete(ModelConfig c, LlmRequest r, StreamHandler h) {
                throw new UnsupportedOperationException();
            }
        });
        // 输入约 5000 tokens：a 窗 8000 拒绝（5000+4000+400>8000），b 窗 32000 通过
        LlmResponse resp = gateway.complete(request(text(5000), text(1500)));
        assertEquals("from-b", resp.getContent(), "应落到更大窗口的 b 路由");
        assertEquals(List.of("model-b"), called, "小窗路由不得触达客户端");
        assertTrue(saw("REQUEST_BUDGET_EXCEEDED"));
    }

    @Test
    void 全部路由超窗时失败明确() {
        List<String> called = new ArrayList<>();
        var gateway = new DefaultLlmGateway(properties(8000, 9000), new LlmClient() {
            public LlmResponse complete(ModelConfig c, LlmRequest r) {
                called.add(c.getModel());
                return new LlmResponse("no", 1, 1);
            }
            public void streamComplete(ModelConfig c, LlmRequest r, StreamHandler h) {
                throw new UnsupportedOperationException();
            }
        });
        assertThrows(RequestBudgetExceededException.class,
                () -> gateway.complete(request(text(7000), text(1000))));
        assertTrue(called.isEmpty());
        assertTrue(saw("REQUEST_BUDGET_EXCEEDED"));
    }

    @Test
    void 未配置窗口不设防_向后兼容() {
        // legacy 单模型路径：default-model 未配置 context-window（=0）时不设防
        var m = new java.util.HashMap<String, Object>();
        m.put("llm.default-model.base-url", "https://legacy.example.org/v1");
        m.put("llm.default-model.api-key", "legacy-key");
        m.put("llm.default-model.model", "legacy-model");
        var p = LlmProviderConfigurationTest.bind(m);
        List<String> called = new ArrayList<>();
        var gateway = new DefaultLlmGateway(p, new LlmClient() {
            public LlmResponse complete(ModelConfig c, LlmRequest r) {
                called.add(c.getModel());
                return new LlmResponse("legacy-ok", 1, 1);
            }
            public void streamComplete(ModelConfig c, LlmRequest r, StreamHandler h) {
                throw new UnsupportedOperationException();
            }
        });
        assertEquals("legacy-ok", gateway.complete(request(text(60000), text(20000))).getContent());
        assertEquals(List.of("legacy-model"), called);
        assertFalse(saw("REQUEST_BUDGET_EXCEEDED"));
    }

    @Test
    void 流式路径超窗走onError_不触达客户端() {
        List<String> called = new ArrayList<>();
        var gateway = new DefaultLlmGateway(properties(8000, 8000), new LlmClient() {
            public LlmResponse complete(ModelConfig c, LlmRequest r) {
                throw new UnsupportedOperationException();
            }
            public void streamComplete(ModelConfig c, LlmRequest r, StreamHandler h) {
                called.add(c.getModel());
                h.onComplete();
            }
        });
        var failure = new ArrayList<Throwable>();
        gateway.streamComplete(request(text(7000), text(1000)), new StreamHandler() {
            public void onToken(String t) {}
            public void onComplete() { fail("超窗流式请求不应完成"); }
            public void onComplete(LlmResponse resp) { fail("超窗流式请求不应完成"); }
            public void onError(Throwable e) { failure.add(e); }
        });
        assertEquals(1, failure.size(), "必须恰好一次 onError");
        assertTrue(failure.get(0) instanceof RequestBudgetExceededException);
        assertTrue(called.isEmpty(), "超窗流式请求不得触达 provider");
        assertTrue(saw("REQUEST_BUDGET_EXCEEDED"));
    }

    @Test
    void 输出预留取请求与配置较大值_不因请求覆盖变小() {
        // resolveConfig 取 max(请求 maxTokens, 配置 maxTokens)=4000；窗口 8000 时输入约 5000 仍应拒绝
        List<String> called = new ArrayList<>();
        var gateway = new DefaultLlmGateway(properties(8000, 8000), new LlmClient() {
            public LlmResponse complete(ModelConfig c, LlmRequest r) {
                called.add(c.getModel());
                return new LlmResponse("no", 1, 1);
            }
            public void streamComplete(ModelConfig c, LlmRequest r, StreamHandler h) {
                throw new UnsupportedOperationException();
            }
        });
        assertThrows(RequestBudgetExceededException.class,
                () -> gateway.complete(request(text(5000), text(1500))));
        assertTrue(called.isEmpty());
    }
}
