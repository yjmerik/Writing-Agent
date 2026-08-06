package com.writing.novel.telemetry;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 结构化事件日志 — 以 JSON Lines 追加写入 data/logs/events.jsonl。
 * 供 scripts/diagnose.sh 等工具消费，实现"识别问题日志"环节的机器可读产物。
 *
 * 设计为静态工具（与 slf4j Logger 同等地位），避免向编排器/网关构造器加参。
 * 写入失败只记 warn，绝不影响业务流程。
 *
 * 事件类型约定：
 * - LLM_CALL       LLM 调用成功（taskType, durationMs, promptTokens, completionTokens）
 * - LLM_ERROR      LLM 调用失败（taskType, durationMs, errorClass, message）
 * - CHAPTER_WRITTEN 单章写作成功（chapterIndex, wordCount, durationMs）
 * - CHAPTER_FAILED  单章写作失败（errorClass, message, consecutiveFailures）
 */
public final class EventLog {

    private static final Logger log = LoggerFactory.getLogger(EventLog.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Object LOCK = new Object();

    /** 测试/脚本可通过该系统属性重定向输出文件 */
    public static final String PATH_PROPERTY = "novel.events.file";
    private static final String DEFAULT_PATH = "data/logs/events.jsonl";

    private EventLog() {}

    public static Path currentPath() {
        return Path.of(System.getProperty(PATH_PROPERTY, DEFAULT_PATH));
    }

    /**
     * 追加一条事件。自动补充时间戳与 EventContext 中的 projectId/chapter。
     */
    public static void emit(String event, Map<String, Object> fields) {
        try {
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("ts", Instant.now().toString());
            record.put("event", event);
            String projectId = EventContext.projectId();
            if (projectId != null) record.put("projectId", projectId);
            Integer chapter = EventContext.chapter();
            if (chapter != null) record.put("chapter", chapter);
            fields.forEach((k, v) -> {
                if (v != null) record.put(k, v);
            });
            String line = MAPPER.writeValueAsString(record);
            synchronized (LOCK) {
                Path path = currentPath();
                if (path.getParent() != null) {
                    Files.createDirectories(path.getParent());
                }
                Files.writeString(path, line + "\n", StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
        } catch (Exception e) {
            log.warn("事件日志写入失败: {}", e.getMessage());
        }
    }

    /** 截断长文本（错误消息等），避免单行事件膨胀 */
    public static String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "…";
    }
}
