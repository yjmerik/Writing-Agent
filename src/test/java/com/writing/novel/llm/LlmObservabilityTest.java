package com.writing.novel.llm;

import com.writing.novel.llm.gateway.ObservedLlmGateway;
import com.writing.novel.telemetry.EventLog;
import com.writing.novel.telemetry.EventLog.Listener;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * NCS-130: LLM 可观测性增强——LLM_ERROR 带 correlationId/provider/model/prompt 指纹；
 * EventLog 异步批量写盘（不丢事件、调用线程不阻塞在串行 IO）。
 */
class LlmObservabilityTest {

    private final List<Map<String, Object>> events = new CopyOnWriteArrayList<>();
    private Listener listener;

    @BeforeEach
    void setUp() {
        events.clear();
        listener = events::add;
        EventLog.subscribe(listener);
    }

    @AfterEach
    void tearDown() {
        if (listener != null) EventLog.unsubscribe(listener);
    }

    private static LlmGateway failing(String message) {
        return new LlmGateway() {
            @Override public LlmResponse complete(LlmRequest req) { throw new RuntimeException(message); }
            @Override public void streamComplete(LlmRequest req, com.writing.novel.llm.client.StreamHandler h) {}
        };
    }

    @Test
    void 失败事件带correlationId与prompt指纹() {
        var gateway = new ObservedLlmGateway(failing("HTTP 500 boom"), null, null);
        assertThrows(RuntimeException.class, () -> gateway.complete(LlmRequest.builder()
                .taskType(LlmTaskType.GENERAL).systemPrompt("同样的系统提示").userMessage("同样的用户输入").build()));
        assertThrows(RuntimeException.class, () -> gateway.complete(LlmRequest.builder()
                .taskType(LlmTaskType.GENERAL).systemPrompt("同样的系统提示").userMessage("同样的用户输入").build()));

        var errors = events.stream().filter(e -> "LLM_ERROR".equals(e.get("event"))).toList();
        assertEquals(2, errors.size());
        var first = errors.get(0);
        var second = errors.get(1);
        assertNotNull(first.get("correlationId"), "失败事件必须带 correlationId");
        assertNotEquals(first.get("correlationId"), second.get("correlationId"), "每次调用独立 id");
        assertEquals(first.get("promptFingerprint"), second.get("promptFingerprint"),
                "同一 prompt 指纹一致（可聚类反复失败）");
        assertEquals(12, ((String) first.get("correlationId")).length());
        assertEquals("legacy", first.get("provider"), "未配置路由时 provider=legacy");
        assertEquals("unknown", first.get("model"));
        assertEquals("HTTP 500 boom", first.get("message"));
    }

    @Test
    void 成功路径不产生错误事件() {
        LlmGateway ok = new LlmGateway() {
            @Override public LlmResponse complete(LlmRequest req) { return new LlmResponse("ok", 1, 1); }
            @Override public void streamComplete(LlmRequest req, com.writing.novel.llm.client.StreamHandler h) {}
        };
        new ObservedLlmGateway(ok, null, null).complete(LlmRequest.builder()
                .taskType(LlmTaskType.GENERAL).systemPrompt("s").userMessage("u").build());
        assertTrue(events.stream().noneMatch(e -> "LLM_ERROR".equals(e.get("event"))));
    }

    @Test
    void 异步日志不丢事件_awaitFlush可见() {
        for (int i = 0; i < 200; i++) {
            EventLog.emit("OBSERVABILITY_PROBE", Map.of("seq", i));
        }
        EventLog.awaitFlush();
        long seen = events.stream()
                .filter(e -> "OBSERVABILITY_PROBE".equals(e.get("event"))).count();
        assertEquals(200, seen, "订阅者视角全量可见（异步不丢）");
    }
}
