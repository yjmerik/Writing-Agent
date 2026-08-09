package com.writing.novel.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.writing.novel.llm.client.StreamHandler;
import com.writing.novel.llm.gateway.ObservedLlmGateway;
import com.writing.novel.telemetry.EventLog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * mc-p3-03: LLM_CALL 事件埋点——promptTokens/completionTokens/estimatedCostCny。
 * 复现 T1：无法按任务类型核算成本。
 */
class LlmCostTelemetryTest {

    @TempDir
    Path tempDir;

    @AfterEach
    void cleanup() {
        System.clearProperty(EventLog.PATH_PROPERTY);
    }

    @Test
    void LLM调用事件含token与估算成本() throws Exception {
        Path eventsFile = tempDir.resolve("events.jsonl");
        System.setProperty(EventLog.PATH_PROPERTY, eventsFile.toString());

        LlmGateway delegate = new LlmGateway() {
            @Override public LlmResponse complete(LlmRequest req) {
                return new LlmResponse("正文内容", 1000, 500);
            }
            @Override public void streamComplete(LlmRequest req, StreamHandler handler) {}
        };
        ObservedLlmGateway observed = new ObservedLlmGateway(delegate);

        observed.complete(LlmRequest.builder()
                .taskType(LlmTaskType.CHAPTER_WRITING)
                .systemPrompt("system")
                .userMessage("user")
                .build());

        List<String> lines = Files.readAllLines(eventsFile);
        assertEquals(1, lines.size());

        ObjectMapper mapper = new ObjectMapper();
        JsonNode event = mapper.readTree(lines.get(0));
        assertEquals("LLM_CALL", event.get("event").asText());
        assertEquals("CHAPTER_WRITING", event.get("taskType").asText());
        assertEquals(1000, event.get("promptTokens").asInt());
        assertEquals(500, event.get("completionTokens").asInt());

        // 估算成本 = 1000/1000*0.001 + 500/1000*0.002 = 0.001 + 0.001 = 0.002 元
        double cost = event.get("estimatedCostCny").asDouble();
        assertEquals(0.002, cost, 1e-4);
    }

    @Test
    void 失败事件不含成本字段() throws Exception {
        Path eventsFile = tempDir.resolve("events.jsonl");
        System.setProperty(EventLog.PATH_PROPERTY, eventsFile.toString());

        LlmGateway failing = new LlmGateway() {
            @Override public LlmResponse complete(LlmRequest req) {
                throw new IllegalStateException("上游 429");
            }
            @Override public void streamComplete(LlmRequest req, StreamHandler handler) {}
        };
        ObservedLlmGateway observed = new ObservedLlmGateway(failing);

        assertThrows(IllegalStateException.class, () -> observed.complete(
                LlmRequest.builder().taskType(LlmTaskType.CONTINUITY_CHECK).build()));

        List<String> lines = Files.readAllLines(eventsFile);
        JsonNode event = new ObjectMapper().readTree(lines.get(0));
        assertEquals("LLM_ERROR", event.get("event").asText());
        assertFalse(event.has("estimatedCostCny"));
    }
}
