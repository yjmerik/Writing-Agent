package com.writing.novel.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.writing.novel.telemetry.EventLog;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * p18-04: 项目事件总线——把 EventLog 的静态遥测事件实时路由到订阅前端（SSE）。
 *
 * 设计：EventLog.emit 在写 JSONL 之后通知监听器；本组件注册一个监听器，
 * 按 record.projectId 路由到该项目的 Sink（EventController 用 SseEmitter 适配）。
 * Sink 抛异常（连接断开）时移除该 Sink，不影响其他订阅者与写作主流程。
 */
@Component
public class SseEventBus {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 推送目标抽象（EventController 用 SseEmitter 适配；测试用录音 Sink） */
    public interface Sink {
        void send(String eventName, String json) throws Exception;
    }

    private final Map<String, List<Sink>> sinksByProject = new ConcurrentHashMap<>();

    /** 注册项目事件订阅；返回注销句柄 */
    public Runnable register(String projectId, Sink sink) {
        sinksByProject.computeIfAbsent(projectId, k -> new CopyOnWriteArrayList<>()).add(sink);
        return () -> sinksByProject.getOrDefault(projectId, List.of()).remove(sink);
    }

    /** 桥接 EventLog 静态事件流 → 本总线；返回解绑句柄 */
    public Runnable bridgeToEventLog() {
        EventLog.Listener listener = this::dispatch;
        EventLog.subscribe(listener);
        return () -> EventLog.unsubscribe(listener);
    }

    /** 路由一条事件：有 projectId 只发该项目；无 projectId 广播（全局事件） */
    void dispatch(Map<String, Object> record) {
        Object event = record.get("event");
        Object projectId = record.get("projectId");
        String eventName = event != null ? event.toString() : "EVENT";
        String json;
        try {
            json = MAPPER.writeValueAsString(record);
        } catch (Exception e) {
            return; // 序列化失败只影响推送，不影响主流程
        }
        if (projectId != null) {
            sendTo(projectId.toString(), eventName, json);
        } else {
            // 广播（EventContext 缺失 projectId 的全局事件）
            sinksByProject.keySet().forEach(pid -> sendTo(pid, eventName, json));
        }
    }

    private void sendTo(String projectId, String eventName, String json) {
        List<Sink> sinks = sinksByProject.get(projectId);
        if (sinks == null || sinks.isEmpty()) return;
        for (Sink sink : sinks) {
            try {
                sink.send(eventName, json);
            } catch (Exception e) {
                // 连接断开：移除坏 sink，不影响其他订阅者与主流程
                sinks.remove(sink);
            }
        }
    }
}
