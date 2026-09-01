package com.writing.novel.web;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * p18-04: SSE 实时事件端点——前端 EventSource 订阅项目事件流。
 * 事件类型：CHAPTER_WRITTEN / CHAPTER_FAILED / QUALITY_REVIEW / QUALITY_CIRCUIT_BREAKER / LLM_ERROR 等。
 * 总线在应用启动时桥接 EventLog（见 onStartup），断开的 emitter 由总线自动移除。
 */
@RestController
@RequestMapping("/api/projects/{id}/events")
public class EventController {

    private static final long SSE_TIMEOUT_MS = 30 * 60 * 1000L; // 30 分钟：一次 write-all 会话

    private final SseEventBus bus;
    private final Runnable unbridge;

    public EventController(SseEventBus bus) {
        this.bus = bus;
        this.unbridge = bus.bridgeToEventLog(); // 应用生命周期内常驻
    }

    @GetMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable String id) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        SseEventBus.Sink sink = (eventName, json) ->
                emitter.send(SseEmitter.event().name(eventName).data(json, MediaType.APPLICATION_JSON));
        Runnable unregister = bus.register(id, sink);
        Runnable once = new Runnable() {
            volatile boolean done = false;
            @Override public synchronized void run() {
                if (!done) { done = true; unregister.run(); }
            }
        };
        emitter.onCompletion(once);
        emitter.onTimeout(() -> { once.run(); emitter.complete(); });
        emitter.onError(e -> once.run());
        try {
            emitter.send(SseEmitter.event().name("CONNECTED").data("{\"ok\":true}"));
        } catch (Exception ignored) { }
        return emitter;
    }
}
