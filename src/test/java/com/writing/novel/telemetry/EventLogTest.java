package com.writing.novel.telemetry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EventLogTest {

    @TempDir
    Path tempDir;

    @AfterEach
    void cleanup() {
        System.clearProperty(EventLog.PATH_PROPERTY);
        EventContext.clear();
    }

    @Test
    void 事件以JSON行追加且自动合并上下文() throws Exception {
        Path eventsFile = tempDir.resolve("events.jsonl");
        System.setProperty(EventLog.PATH_PROPERTY, eventsFile.toString());

        EventContext.setProjectId("proj-1");
        EventContext.setChapter(3);
        EventLog.emit("LLM_CALL", Map.of("taskType", "CHAPTER_WRITING", "durationMs", 1234));
        EventLog.emit("CHAPTER_WRITTEN", Map.of("wordCount", 3000));

        List<String> lines = Files.readAllLines(eventsFile);
        assertEquals(2, lines.size());

        ObjectMapper mapper = new ObjectMapper();
        JsonNode first = mapper.readTree(lines.get(0));
        assertEquals("LLM_CALL", first.get("event").asText());
        assertEquals("proj-1", first.get("projectId").asText());
        assertEquals(3, first.get("chapter").asInt());
        assertEquals("CHAPTER_WRITING", first.get("taskType").asText());
        assertEquals(1234, first.get("durationMs").asInt());
        assertTrue(first.has("ts"));

        JsonNode second = mapper.readTree(lines.get(1));
        assertEquals("CHAPTER_WRITTEN", second.get("event").asText());
        assertEquals(3000, second.get("wordCount").asInt());
    }

    @Test
    void 上下文清除后事件不含projectId() throws Exception {
        Path eventsFile = tempDir.resolve("events.jsonl");
        System.setProperty(EventLog.PATH_PROPERTY, eventsFile.toString());

        EventContext.setProjectId("proj-1");
        EventContext.clear();
        EventLog.emit("LLM_CALL", Map.of("taskType", "GENERAL"));

        JsonNode node = new ObjectMapper().readTree(Files.readAllLines(eventsFile).get(0));
        assertFalse(node.has("projectId"));
    }

    @Test
    void 清除上下文后下一次任务不会继承上一次章节号() {
        EventContext.setProjectId("first-project");
        EventContext.setChapter(8);
        EventContext.clear();

        assertNull(EventContext.projectId());
        assertNull(EventContext.chapter());
    }

    @Test
    void 写入失败不抛异常() {
        System.setProperty(EventLog.PATH_PROPERTY, "/dev/null/impossible/events.jsonl");
        assertDoesNotThrow(() -> EventLog.emit("LLM_CALL", Map.of("k", "v")));
    }

    @Test
    void truncate截断长文本() {
        assertEquals("", EventLog.truncate(null, 10));
        assertEquals("short", EventLog.truncate("short", 10));
        assertEquals("0123456789…", EventLog.truncate("0123456789ABCDEF", 10));
    }
}
