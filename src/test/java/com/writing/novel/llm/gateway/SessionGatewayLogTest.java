package com.writing.novel.llm.gateway;

import com.writing.novel.llm.LlmRequest;
import com.writing.novel.llm.LlmResponse;
import com.writing.novel.telemetry.EventContext;
import com.writing.novel.telemetry.SessionLog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * NCS-172: ObservedLlmGateway 与 session 日志的串联——complete 成功/失败后
 * session.log 行必须携带 EventContext.sessionId 与 prompt/response 全文。
 */
class SessionGatewayLogTest {

    private Path tempFile;

    @BeforeEach
    void setUp() throws Exception {
        tempFile = Files.createTempFile("session-gw-test", ".log");
        Files.deleteIfExists(tempFile);
        System.setProperty(SessionLog.FILE_PROPERTY, tempFile.toString());
    }

    @AfterEach
    void tearDown() {
        System.clearProperty(SessionLog.FILE_PROPERTY);
        SessionLog.deleteAllForTest();
        try { Files.deleteIfExists(tempFile); } catch (Exception ignored) {}
        EventContext.clear();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> firstRecord() throws Exception {
        List<String> lines = Files.readAllLines(tempFile);
        assertEquals(1, lines.size(), "应恰好一条记录");
        return new com.fasterxml.jackson.databind.ObjectMapper().readValue(lines.get(0), Map.class);
    }

    @Test
    void complete成功记录session与全文() throws Exception {
        EventContext.setSession("task-gw", "WRITE");
        ObservedLlmGateway gateway = new ObservedLlmGateway(new com.writing.novel.llm.LlmGateway() {
            @Override public LlmResponse complete(LlmRequest request) {
                return new LlmResponse("第一章：夜账……", 1200, 4000);
            }
            @Override public void streamComplete(LlmRequest request,
                    com.writing.novel.llm.client.StreamHandler handler) {}
        });

        LlmResponse resp = gateway.complete(LlmRequest.builder()
                .taskType(com.writing.novel.llm.LlmTaskType.CHAPTER_WRITING)
                .systemPrompt("你是写作专家")
                .userMessage("写第一章")
                .maxTokens(8000).temperature(0.8).build());

        assertEquals("第一章：夜账……", resp.getContent());
        Map<String, Object> r = firstRecord();
        assertEquals("task-gw", r.get("sessionId"));
        assertEquals("CHAPTER_WRITING", r.get("taskType"));
        assertTrue(String.valueOf(r.get("prompt")).contains("[system]") &&
                   String.valueOf(r.get("prompt")).contains("写第一章"), "prompt 全文分段记录");
        assertTrue(String.valueOf(r.get("response")).contains("夜账"), "response 全文");
        assertEquals(1200, r.get("promptTokens"));
        assertEquals(4000, r.get("completionTokens"));
    }

    @Test
    void complete失败记录error与prompt() throws Exception {
        EventContext.setSession("task-gw-err", "CONCEIVE");
        ObservedLlmGateway gateway = new ObservedLlmGateway(new com.writing.novel.llm.LlmGateway() {
            @Override public LlmResponse complete(LlmRequest request) {
                throw new IllegalStateException("provider 全部冷却");
            }
            @Override public void streamComplete(LlmRequest request,
                    com.writing.novel.llm.client.StreamHandler handler) {}
        });

        try {
            gateway.complete(LlmRequest.builder()
                    .taskType(com.writing.novel.llm.LlmTaskType.WORLDBUILDING)
                    .userMessage("生成世界观").maxTokens(1000).build());
            org.junit.jupiter.api.Assertions.fail("应向上抛出");
        } catch (IllegalStateException expected) {
            // 观察者转发原异常
        }
        Map<String, Object> r = firstRecord();
        assertEquals("task-gw-err", r.get("sessionId"));
        assertTrue(String.valueOf(r.get("error")).contains("provider 全部冷却"));
        assertTrue(String.valueOf(r.get("prompt")).contains("生成世界观"));
    }
}
