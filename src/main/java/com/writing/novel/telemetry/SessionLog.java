package com.writing.novel.telemetry;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * NCS-172: 全链路 Session 日志 — 以 JSON Lines 追加写入 data/logs/session.log。
 *
 * 与 events.jsonl（统计口径、诊断脚本数据源）互补：本文件记录
 * 排障所需的完整现场——LLM 调用的 prompt/response 全文，以及任务/操作的生命周期条目，
 * 以 sessionId 串联（EventContext.sessionId，来自任务 taskId/工作 workId）。
 *
 * 滚动：单文件超过 max-bytes（默认 150MB，系统属性 novel.session.max-bytes，
 * 支持 "150MB"/"150M"/纯字节）时按序号滚动 session.log → session.log.1 → …，
 * 超过 max-files（默认 3，novel.session.max-files）删除最旧。
 * 写入失败只记 warn，绝不影响业务流程。同步写 + 全局锁（低频大行场景）。
 */
public final class SessionLog {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(SessionLog.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Object LOCK = new Object();

    public static final String FILE_PROPERTY = "novel.session.file";
    public static final String MAX_BYTES_PROPERTY = "novel.session.max-bytes";
    public static final String MAX_FILES_PROPERTY = "novel.session.max-files";
    private static final String DEFAULT_PATH = "data/logs/session.log";
    private static final long DEFAULT_MAX_BYTES = parseSize("150MB");
    private static final int DEFAULT_MAX_FILES = 3;

    private SessionLog() {}

    /** 解析 "150MB"/"150M"/"150KB"/纯字节 */
    static long parseSize(String raw) {
        if (raw == null || raw.isBlank()) return DEFAULT_MAX_BYTES;
        String v = raw.trim().toUpperCase();
        try {
            if (v.endsWith("MB")) return (long) (Double.parseDouble(v.substring(0, v.length() - 2)) * 1024 * 1024);
            if (v.endsWith("M")) return (long) (Double.parseDouble(v.substring(0, v.length() - 1)) * 1024 * 1024);
            if (v.endsWith("KB")) return (long) (Double.parseDouble(v.substring(0, v.length() - 2)) * 1024);
            if (v.endsWith("K")) return (long) (Double.parseDouble(v.substring(0, v.length() - 1)) * 1024);
            return Long.parseLong(v);
        } catch (NumberFormatException e) {
            log.warn("session.max-bytes 无法解析: {}，回退默认 {} 字节", raw, DEFAULT_MAX_BYTES);
            return DEFAULT_MAX_BYTES;
        }
    }

    static long maxBytes() {
        String v = System.getProperty(MAX_BYTES_PROPERTY);
        return v == null ? DEFAULT_MAX_BYTES : parseSize(v);
    }

    static int maxFiles() {
        try {
            return Math.max(1, Integer.parseInt(System.getProperty(MAX_FILES_PROPERTY,
                    String.valueOf(DEFAULT_MAX_FILES))));
        } catch (NumberFormatException e) {
            return DEFAULT_MAX_FILES;
        }
    }

    static Path currentPath() {
        return Path.of(System.getProperty(FILE_PROPERTY, DEFAULT_PATH));
    }

    /** 记录一次模型调用的完整现场（prompt/response 全文） */
    public static void llm(String taskType, String model, String provider, String correlationId,
                           long durationMs, Integer promptTokens, Integer completionTokens,
                           String prompt, String response, String error) {
        Map<String, Object> record = base("llm");
        record.put("taskType", taskType);
        if (model != null) record.put("model", model);
        if (provider != null) record.put("provider", provider);
        if (correlationId != null) record.put("correlationId", correlationId);
        record.put("durationMs", durationMs);
        if (promptTokens != null) record.put("promptTokens", promptTokens);
        if (completionTokens != null) record.put("completionTokens", completionTokens);
        if (prompt != null) record.put("prompt", prompt);
        if (response != null) record.put("response", response);
        if (error != null) record.put("error", error);
        append(record);
    }

    /** 记录一次关键操作（任务生命周期/批准/检查点/长任务等） */
    public static void op(String action, Map<String, Object> details) {
        Map<String, Object> record = base("op");
        record.put("action", action);
        if (details != null) record.putAll(details);
        append(record);
    }

    private static Map<String, Object> base(String kind) {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("ts", Instant.now().toString());
        record.put("kind", kind);
        String sessionId = EventContext.sessionId();
        if (sessionId != null) record.put("sessionId", sessionId);
        String operation = EventContext.operation();
        if (operation != null) record.put("operation", operation);
        String projectId = EventContext.projectId();
        if (projectId != null) record.put("projectId", projectId);
        Integer chapter = EventContext.chapter();
        if (chapter != null) record.put("chapter", chapter);
        return record;
    }

    private static void append(Map<String, Object> record) {
        try {
            String line = MAPPER.writeValueAsString(record);
            synchronized (LOCK) {
                Path path = currentPath();
                if (path.getParent() != null) Files.createDirectories(path.getParent());
                rollIfNeeded(path);
                Files.writeString(path, line + "\n", StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
        } catch (Exception e) {
            log.warn("session 日志写入失败: {}", e.getMessage());
        }
    }

    /** 超限滚动：current → .1 → .2 …（最多 maxFiles 个滚动件，最旧删除） */
    static void rollIfNeeded(Path path) throws IOException {
        if (!Files.exists(path) || Files.size(path) <= maxBytes()) return;
        int maxFiles = maxFiles();
        Path oldest = path.resolveSibling(path.getFileName() + "." + maxFiles);
        if (Files.exists(oldest)) Files.delete(oldest);
        for (int i = maxFiles - 1; i >= 1; i--) {
            Path from = path.resolveSibling(path.getFileName() + "." + i);
            if (Files.exists(from)) Files.move(from, path.resolveSibling(path.getFileName() + "." + (i + 1)));
        }
        Files.move(path, path.resolveSibling(path.getFileName() + ".1"));
    }

    /** 测试钩子：清空全部 session 日志文件（重定向路径下） */
    public static void deleteAllForTest() {
        synchronized (LOCK) {
            Path path = currentPath();
            try {
                Files.deleteIfExists(path);
                for (int i = 1; i <= maxFiles() + 1; i++) {
                    Files.deleteIfExists(path.resolveSibling(path.getFileName() + "." + i));
                }
            } catch (IOException ignored) {
            }
        }
    }
}
