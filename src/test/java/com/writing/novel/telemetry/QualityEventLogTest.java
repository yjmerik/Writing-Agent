package com.writing.novel.telemetry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * p11-q3-01: 质量事件遥测——5 类质量事件正确写入 events.jsonl。
 */
class QualityEventLogTest {

    @TempDir Path tempDir;

    @Test
    void 质量事件正确写入events_jsonl() throws Exception {
        Path events = tempDir.resolve("quality-events.jsonl");
        System.setProperty(EventLog.PATH_PROPERTY, events.toString());
        try {
            EventLog.emit("QUALITY_REVIEW", Map.of("score", 8, "passed", true));
            EventLog.emit("REWRITE_TRIGGERED", Map.of("reason", "reader_review", "afterScore", 9));
            EventLog.emit("CONSISTENCY_ISSUE", Map.of("type", "CHARACTER", "severity", "HIGH", "action", "REWRITE"));
            EventLog.emit("QUALITY_CIRCUIT_BREAKER", Map.of("consecutiveHighIssues", 10, "threshold", 10));
            EventLog.emit("QUALITY_GATE", Map.of("stage", "WorldBuilder", "feedback", "缺力量体系"));

            String content = Files.readString(events);
            for (String ev : new String[]{"QUALITY_REVIEW", "REWRITE_TRIGGERED", "CONSISTENCY_ISSUE",
                    "QUALITY_CIRCUIT_BREAKER", "QUALITY_GATE"}) {
                assertTrue(content.contains("\"event\":\"" + ev + "\""), "events.jsonl 应含事件 " + ev);
            }
            assertTrue(content.contains("\"score\":8"), "应含评分字段");
            assertTrue(content.contains("\"stage\":\"WorldBuilder\""), "应含门禁 stage 字段");
        } finally {
            System.clearProperty(EventLog.PATH_PROPERTY);
        }
    }
}
