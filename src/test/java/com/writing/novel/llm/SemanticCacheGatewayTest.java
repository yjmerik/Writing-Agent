package com.writing.novel.llm;

import com.writing.novel.llm.gateway.SemanticCacheLlmGateway;
import com.writing.novel.telemetry.EventLog;
import com.writing.novel.telemetry.EventContext;
import com.writing.novel.telemetry.EventLog.Listener;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * NCS-131: 语义缓存网关——白名单任务同 prompt 命中缓存零下游调用；
 * 章稿推进（scope 变化）失效；创意任务绝不缓存。
 */
class SemanticCacheGatewayTest {

    private final List<Map<String, Object>> events = new CopyOnWriteArrayList<>();
    private Listener listener;

    @BeforeEach
    void setUp() {
        events.clear();
        listener = events::add;
        EventLog.subscribe(listener);
        EventContext.setProjectId("cache-proj");
        EventContext.setChapter(3);
    }

    @AfterEach
    void tearDown() {
        EventContext.clear();
        if (listener != null) EventLog.unsubscribe(listener);
    }

    @Test
    void 白名单任务_同prompt命中缓存() {
        AtomicInteger downstream = new AtomicInteger();
        LlmGateway counting = new LlmGateway() {
            @Override public LlmResponse complete(LlmRequest req) {
                downstream.incrementAndGet();
                return new LlmResponse("检查通过", 10, 5);
            }
            @Override public void streamComplete(LlmRequest req, com.writing.novel.llm.client.StreamHandler h) {}
        };
        var cache = new SemanticCacheLlmGateway(counting, "qwen-flash-check", "qwen3.8-flash", 100);

        var req = LlmRequest.builder().taskType(LlmTaskType.CONTINUITY_CHECK)
                .systemPrompt("检查以下正文").userMessage("同一段正文").temperature(0.2).build();
        cache.complete(req);
        cache.complete(req);
        cache.complete(req);

        assertEquals(1, downstream.get(), "同 prompt 只透传一次");
        long hits = events.stream().filter(e -> "LLM_CACHE_HIT".equals(e.get("event"))).count();
        assertEquals(2, hits, "两次命中发 LLM_CACHE_HIT");
        assertTrue(events.stream().filter(e -> "LLM_CACHE_HIT".equals(e.get("event")))
                .allMatch(e -> "qwen-flash-check".equals(e.get("provider"))));
    }

    @Test
    void 章稿推进_缓存失效重新透传() {
        AtomicInteger downstream = new AtomicInteger();
        LlmGateway counting = new LlmGateway() {
            @Override public LlmResponse complete(LlmRequest req) {
                downstream.incrementAndGet();
                return new LlmResponse("第" + downstream.get() + "次结果", 10, 5);
            }
            @Override public void streamComplete(LlmRequest req, com.writing.novel.llm.client.StreamHandler h) {}
        };
        var cache = new SemanticCacheLlmGateway(counting, "p", "m", 100);
        var req = LlmRequest.builder().taskType(LlmTaskType.STYLE_GUARDIAN)
                .systemPrompt("风格检查").userMessage("同一段正文").temperature(0.3).build();

        assertEquals("第1次结果", cache.complete(req).getContent());
        assertEquals("第1次结果", cache.complete(req).getContent(), "同章命中");
        EventContext.setChapter(4);   // 章稿推进
        assertEquals("第2次结果", cache.complete(req).getContent(), "scope 变化即失效重新透传");
        assertEquals(2, downstream.get());
    }

    @Test
    void 创意任务不缓存() {
        AtomicInteger downstream = new AtomicInteger();
        LlmGateway counting = new LlmGateway() {
            @Override public LlmResponse complete(LlmRequest req) {
                downstream.incrementAndGet();
                return new LlmResponse("创作结果", 100, 500);
            }
            @Override public void streamComplete(LlmRequest req, com.writing.novel.llm.client.StreamHandler h) {}
        };
        var cache = new SemanticCacheLlmGateway(counting, "p", "m", 100);
        var req = LlmRequest.builder().taskType(LlmTaskType.CHAPTER_WRITING)
                .systemPrompt("写正文").userMessage("同一个大纲").temperature(0.8).build();
        cache.complete(req);
        cache.complete(req);
        assertEquals(2, downstream.get(), "CHAPTER_WRITING 每次透传（绝不缓存）");
        assertTrue(events.stream().noneMatch(e -> "LLM_CACHE_HIT".equals(e.get("event"))));
    }
}
