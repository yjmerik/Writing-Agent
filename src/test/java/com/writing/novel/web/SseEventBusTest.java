package com.writing.novel.web;

import com.writing.novel.telemetry.EventContext;
import com.writing.novel.telemetry.EventLog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * p18-04: SSE 实时进度推送——事件订阅总线 + 按项目路由。
 * 原状：遥测事件只落 JSONL 文件，无推送通道；write-all 期间前端只看到 loading。
 */
class SseEventBusTest {

    private final List<Runnable> cleanups = new ArrayList<>();

    @BeforeEach
    void cleanEventContext() {
        // NCS-091: 「无 projectId 广播」依赖当前线程无 EventContext 残留——
        // 套件中其他测试可能在线程上泄漏 projectId，这里显式清理保证密封性
        EventContext.clear();
    }

    @AfterEach
    void tearDown() {
        cleanups.forEach(Runnable::run);
    }

    static class RecordingSink implements SseEventBus.Sink {
        final List<String> received = new ArrayList<>();
        public void send(String eventName, String json) { received.add(eventName + "|" + json); }
    }

    @Test
    void 订阅者收到本项目的章节事件_其他项目不收到() throws Exception {
        Path events = Files.createTempDirectory("sse").resolve("events.jsonl");
        System.setProperty(EventLog.PATH_PROPERTY, events.toString());

        SseEventBus bus = new SseEventBus();
        // 桥接：EventLog 静态事件 → 总线
        Runnable unbridge = bus.bridgeToEventLog();
        cleanups.add(unbridge);

        RecordingSink projA = new RecordingSink();
        Runnable unreg = bus.register("proj-a", projA);
        cleanups.add(unreg);

        EventLog.emit("CHAPTER_WRITTEN", Map.of("projectId", "proj-a", "chapterIndex", 3, "wordCount", 3200));
        EventLog.emit("CHAPTER_WRITTEN", Map.of("projectId", "proj-b", "chapterIndex", 9, "wordCount", 2800));

        assertEquals(1, projA.received.size(), "只应收到本项目事件");
        assertTrue(projA.received.get(0).startsWith("CHAPTER_WRITTEN|"));
        assertTrue(projA.received.get(0).contains("\"chapterIndex\":3"));

        // 注销后不再收到
        unreg.run();
        EventLog.emit("CHAPTER_WRITTEN", Map.of("projectId", "proj-a", "chapterIndex", 4));
        assertEquals(1, projA.received.size(), "注销后不应再收到事件");
    }

    @Test
    void sink抛异常不影响总线_坏sink被移除() throws Exception {
        Path events = Files.createTempDirectory("sse2").resolve("events.jsonl");
        System.setProperty(EventLog.PATH_PROPERTY, events.toString());

        SseEventBus bus = new SseEventBus();
        cleanups.add(bus.bridgeToEventLog());

        SseEventBus.Sink broken = (name, json) -> { throw new RuntimeException("连接已断"); };
        RecordingSink healthy = new RecordingSink();
        bus.register("proj-x", broken);
        cleanups.add(bus.register("proj-x", healthy));

        EventLog.emit("CHAPTER_WRITTEN", Map.of("projectId", "proj-x", "chapterIndex", 1));

        assertEquals(1, healthy.received.size(), "坏 sink 异常不应影响健康 sink");
        EventLog.emit("CHAPTER_WRITTEN", Map.of("projectId", "proj-x", "chapterIndex", 2));
        assertEquals(2, healthy.received.size(), "坏 sink 已被移除，健康 sink 持续接收");
    }

    @Test
    void 无projectId的事件广播给全部订阅者() throws Exception {
        Path events = Files.createTempDirectory("sse3").resolve("events.jsonl");
        System.setProperty(EventLog.PATH_PROPERTY, events.toString());

        SseEventBus bus = new SseEventBus();
        cleanups.add(bus.bridgeToEventLog());

        RecordingSink s1 = new RecordingSink();
        RecordingSink s2 = new RecordingSink();
        cleanups.add(bus.register("p1", s1));
        cleanups.add(bus.register("p2", s2));

        EventLog.emit("QUALITY_REVIEW", Map.of("chapterIndex", 5, "score", 8));

        assertEquals(1, s1.received.size());
        assertEquals(1, s2.received.size(), "全局事件（无 projectId）应广播");
    }
}
