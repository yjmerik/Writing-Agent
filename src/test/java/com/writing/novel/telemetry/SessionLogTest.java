package com.writing.novel.telemetry;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * NCS-172: 全链路 Session 日志——llm/op 记录内容、150MB（可配）超限序号滚动、
 * 最旧滚动件删除。全部在重定向路径（novel.session.file）下进行，不碰真实 data/logs。
 */
class SessionLogTest {

    private Path tempFile;

    @BeforeEach
    void setUp() throws Exception {
        tempFile = Files.createTempFile("session-log-test", ".log");
        Files.deleteIfExists(tempFile); // SessionLog 会 CREATE
        System.setProperty(SessionLog.FILE_PROPERTY, tempFile.toString());
    }

    @AfterEach
    void tearDown() {
        System.clearProperty(SessionLog.FILE_PROPERTY);
        System.clearProperty(SessionLog.MAX_BYTES_PROPERTY);
        System.clearProperty(SessionLog.MAX_FILES_PROPERTY);
        SessionLog.deleteAllForTest();
        try { Files.deleteIfExists(tempFile); } catch (Exception ignored) {}
    }

    private List<String> lines() throws Exception {
        return Files.readAllLines(tempFile);
    }

    @Test
    void llm记录含session与prompt_response全文() throws Exception {
        EventContext.setSession("task-abc", "WRITE");
        EventContext.setProjectId("proj-1");
        EventContext.setChapter(11);
        try {
            SessionLog.llm("CHAPTER_WRITING", "qwen3.8-flash", "primary", "corr123",
                    1200, 100, 5000,
                    "[system]\n你是写作专家\n\n[user]\n写第十一章",
                    "第十一章正文……", null);

            var records = readRecords();
            assertEquals(1, records.size());
            Map<String, Object> r = records.get(0);
            assertEquals("llm", r.get("kind"));
            assertEquals("task-abc", r.get("sessionId"));
            assertEquals("WRITE", r.get("operation"));
            assertEquals("proj-1", r.get("projectId"));
            assertEquals(11, r.get("chapter"));
            assertEquals("CHAPTER_WRITING", r.get("taskType"));
            assertEquals("qwen3.8-flash", r.get("model"));
            assertEquals("corr123", r.get("correlationId"));
            assertTrue(String.valueOf(r.get("prompt")).contains("写第十一章"), "prompt 全文");
            assertTrue(String.valueOf(r.get("response")).contains("第十一章正文"), "response 全文");
            assertNull(r.get("error"));
        } finally {
            EventContext.clear();
        }
    }

    @Test
    void op记录与无session场景() throws Exception {
        // 无 session 上下文也应可写（不抛异常、无 sessionId 字段）
        SessionLog.op("approval-requested", Map.of("chapterIndex", 10));
        var records = readRecords();
        assertEquals(1, records.size());
        assertEquals("op", records.get(0).get("kind"));
        assertEquals("approval-requested", records.get(0).get("action"));
        assertEquals(10, records.get(0).get("chapterIndex"));
        assertFalse(records.get(0).containsKey("sessionId"), "无上下文不得伪造 sessionId");
    }

    @Test
    void 失败调用记录error字段() throws Exception {
        EventContext.setSession("task-err", "CONCEIVE");
        try {
            SessionLog.llm("WORLDBUILDING", "m", "p", "c", 50, null, null,
                    "prompt-text", null, "RuntimeException: 超时");
            var r = readRecords().get(0);
            assertNull(r.get("response"));
            assertTrue(String.valueOf(r.get("error")).contains("超时"));
        } finally {
            EventContext.clear();
        }
    }

    @Test
    void 超限触发序号滚动且最旧被删() throws Exception {
        // 上限 1KB、保留 2 个滚动件：写 5 条大行（每条 >600B）→ session.log.1/.2 存在、.3 不存在
        System.setProperty(SessionLog.MAX_BYTES_PROPERTY, "1KB");
        System.setProperty(SessionLog.MAX_FILES_PROPERTY, "2");
        String big = "x".repeat(700);
        for (int i = 0; i < 5; i++) {
            SessionLog.op("fill-" + i, Map.of("payload", big));
        }

        assertTrue(Files.exists(tempFile.resolveSibling(tempFile.getFileName() + ".1")),
                "应存在 .1 滚动件");
        assertTrue(Files.exists(tempFile.resolveSibling(tempFile.getFileName() + ".2")),
                "应存在 .2 滚动件");
        assertFalse(Files.exists(tempFile.resolveSibling(tempFile.getFileName() + ".3")),
                "max-files=2 时 .3 必须被删除");
        // 主文件是最新内容
        var mains = readRecords();
        assertFalse(mains.isEmpty());
        assertEquals("fill-4", mains.get(mains.size() - 1).get("action"), "最新条在主文件");
        // 总条数守恒：主 + .1 + .2 = 5（无丢失）
        long total = mains.size()
                + Files.readAllLines(tempFile.resolveSibling(tempFile.getFileName() + ".1")).size()
                + Files.readAllLines(tempFile.resolveSibling(tempFile.getFileName() + ".2")).size();
        assertEquals(5, total, "滚动不得丢失条目");
    }

    @Test
    void parseSize支持MB与纯字节() throws Exception {
        assertEquals(150L * 1024 * 1024, SessionLog.parseSize("150MB"));
        assertEquals(150L * 1024 * 1024, SessionLog.parseSize("150M"));
        assertEquals(2048, SessionLog.parseSize("2048"));
        assertEquals(150L * 1024 * 1024, SessionLog.parseSize("垃圾输入"), "非法值回退默认 150MB");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> readRecords() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        return lines().stream()
                .filter(l -> !l.isBlank())
                .map(l -> {
                    try {
                        return (Map<String, Object>) mapper.readValue(l, Map.class);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                })
                .toList();
    }
}
