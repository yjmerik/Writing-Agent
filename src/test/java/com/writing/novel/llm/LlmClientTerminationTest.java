package com.writing.novel.llm;

import com.writing.novel.llm.client.OpenAiCompatibleClient;
import com.writing.novel.llm.config.ModelConfig;
import com.writing.novel.llm.gateway.ObservedLlmGateway;
import com.writing.novel.llm.gateway.RecordReplayLlmGateway;
import com.writing.novel.telemetry.EventLog;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * NCS-065: 流式终止校验与截断信号。
 * 根因：completeViaStream 读到 EOF 即返回，不校验 [DONE]/finish_reason——被干净掐断的流
 * 把半截内容（如真典收割的单字符 "["）当作成功响应返回（evidence: continued-fact-harvest-
 * diagnosis-20260921.json）；且 LlmResponse 不携带 finish_reason，finish_reason=length 的
 * 截断响应传输层不可见（evidence: continued-entity-diagnosis-20260921.json）。
 */
class LlmClientTerminationTest {

    MockWebServer server;
    OpenAiCompatibleClient client;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        client = new OpenAiCompatibleClient();
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    private ModelConfig streamingConfig() {
        ModelConfig cfg = new ModelConfig();
        cfg.setBaseUrl(server.url("/v1").toString().replaceAll("/$", ""));
        cfg.setApiKey("sk-test");
        cfg.setModel("qwen-test");
        cfg.setTemperature(0.3);
        cfg.setMaxTokens(100);
        cfg.setStreaming(true);
        return cfg;
    }

    private LlmRequest request() {
        return LlmRequest.builder()
                .taskType(LlmTaskType.GENERAL)
                .systemPrompt("s").userMessage("u").build();
    }

    /** 未终止流（无 [DONE] 且无 finish_reason 即 EOF）不得作为成功返回——与空聚合同语义重试 */
    @Test
    void 未终止流不得作为成功返回_重试后拿到完整内容() throws Exception {
        // 第一次：流被干净掐断（EOF，无 [DONE]/finish_reason），只吐出半截 "["
        server.enqueue(new MockResponse().setBody(
                        "data: {\"choices\":[{\"delta\":{\"content\":\"[\"}}]}\n\n")
                .addHeader("Content-Type", "text/event-stream"));
        // 第二次：正常终止的流
        server.enqueue(new MockResponse().setBody(
                        "data: {\"choices\":[{\"delta\":{\"content\":\"OK\"}}]}\n\n"
                                + "data: [DONE]\n\n")
                .addHeader("Content-Type", "text/event-stream"));

        LlmResponse resp = client.complete(streamingConfig(), request());

        assertEquals("OK", resp.getContent(), "未终止流必须重试而不是返回半截内容");
        assertEquals(2, server.getRequestCount(), "未终止流应触发一次重试");
    }

    /** 未终止流重试耗尽后必须显式失败，不得把半截内容当成功 */
    @Test
    void 未终止流重试耗尽显式失败() throws Exception {
        for (int i = 0; i < 3; i++) {
            server.enqueue(new MockResponse().setBody(
                            "data: {\"choices\":[{\"delta\":{\"content\":\"[\"}}]}\n\n")
                    .addHeader("Content-Type", "text/event-stream"));
        }
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> client.complete(streamingConfig(), request()));
        assertTrue(ex.getMessage().contains("未正常终止") || ex.getMessage().contains("终止"),
                "异常应说明流未正常终止，实际: " + ex.getMessage());
    }

    /** finish_reason=length 的流正常终止但内容截断——信号必须透传给调用方 */
    @Test
    void finish_reason为length时透传截断信号() throws Exception {
        server.enqueue(new MockResponse().setBody(
                        "data: {\"choices\":[{\"delta\":{\"content\":\"[{\\\"name\\\":\\\"张\"},\"finish_reason\":\"length\"}]}\n\n"
                                + "data: [DONE]\n\n")
                .addHeader("Content-Type", "text/event-stream"));
        LlmResponse resp = client.complete(streamingConfig(), request());
        assertEquals("length", resp.getFinishReason(), "截断信号必须透传");
        assertTrue(resp.getContent().contains("张"));
    }

    /** 正常 stop 流的 finishReason 为 stop，行为不变 */
    @Test
    void 正常stop流的finishReason为stop() throws Exception {
        server.enqueue(new MockResponse().setBody(
                        "data: {\"choices\":[{\"delta\":{\"content\":\"好\"}}],\"finish_reason\":\"stop\"}\n\n"
                                + "data: [DONE]\n\n")
                .addHeader("Content-Type", "text/event-stream"));
        LlmResponse resp = client.complete(streamingConfig(), request());
        assertEquals("stop", resp.getFinishReason());
        assertEquals("好", resp.getContent());
    }

    /** 非流式路径同样透传 finish_reason=length */
    @Test
    void 非流式路径透传finish_reason() throws Exception {
        server.enqueue(new MockResponse().setBody("""
                {"choices":[{"message":{"content":"[{\\"a\\":1"},"finish_reason":"length"}],
                 "usage":{"prompt_tokens":5,"completion_tokens":9}}
                """).addHeader("Content-Type", "application/json"));
        ModelConfig cfg = streamingConfig();
        cfg.setStreaming(false);
        LlmResponse resp = client.complete(cfg, request());
        assertEquals("length", resp.getFinishReason());
    }

    /** 观测层对截断响应发 LLM_TRUNCATED 事件（events.jsonl 可诊断） */
    @Test
    void 观测层对截断响应发LLM_TRUNCATED事件(@TempDir Path tmp) throws Exception {
        Path eventsFile = tmp.resolve("events.jsonl");
        System.setProperty(EventLog.PATH_PROPERTY, eventsFile.toString());
        try {
            LlmGateway returningTruncated = stubGateway(new LlmResponse("[{\"name\":\"张", 10, 4000, "length", null));
            new ObservedLlmGateway(returningTruncated).complete(request());
            assertTrue(Files.exists(eventsFile), "事件文件应已写入");
            String lines = Files.readString(eventsFile);
            assertTrue(lines.contains("\"event\":\"LLM_TRUNCATED\""), "应发 LLM_TRUNCATED 事件: " + lines);
            assertTrue(lines.contains("\"finishReason\":\"length\""), "事件应含 finishReason: " + lines);
        } finally {
            System.clearProperty(EventLog.PATH_PROPERTY);
        }
    }

    /** stop 响应不发 LLM_TRUNCATED（可观测性不产生噪音） */
    @Test
    void stop响应不发LLM_TRUNCATED(@TempDir Path tmp) throws Exception {
        Path eventsFile = tmp.resolve("events.jsonl");
        System.setProperty(EventLog.PATH_PROPERTY, eventsFile.toString());
        try {
            LlmGateway normal = stubGateway(new LlmResponse("ok", 10, 5, "stop", null));
            new ObservedLlmGateway(normal).complete(request());
            if (Files.exists(eventsFile)) {
                assertFalse(Files.readString(eventsFile).contains("LLM_TRUNCATED"));
            }
        } finally {
            System.clearProperty(EventLog.PATH_PROPERTY);
        }
    }

    /** 回放兼容：旧 fixture 无 finishReason 字段默认 stop；录制时保留非 stop 信号 */
    @Test
    void 回放fixture缺finishReason默认stop_录制保留信号(@TempDir Path tmp) throws Exception {
        Path fixtureDir = tmp.resolve("fixtures");
        Files.createDirectories(fixtureDir);
        LlmRequest req = request();
        LlmGateway unreachable = stubGateway(null);

        // 录制：finishReason=length 的响应必须落盘
        RecordReplayLlmGateway record = new RecordReplayLlmGateway(
                stubGateway(new LlmResponse("半截", 3, 4000, "length", null)),
                RecordReplayLlmGateway.Mode.RECORD, fixtureDir);
        record.complete(req);
        Path recorded = fixtureDir.resolve("GENERAL-" + fixtureFingerprintHint(fixtureDir) + "-1.json");
        assertTrue(Files.exists(recorded), "录制 fixture 应存在: " + recorded);
        assertTrue(Files.readString(recorded).contains("\"finishReason\""), "fixture 应记录 finishReason");

        // 回放：非 stop 信号保留
        RecordReplayLlmGateway replay = new RecordReplayLlmGateway(
                unreachable, RecordReplayLlmGateway.Mode.REPLAY, fixtureDir);
        assertEquals("length", replay.complete(req).getFinishReason(), "回放保留截断信号");

        // 旧格式 fixture（无 finishReason、无调用序号后缀）→ 回放默认 stop
        String fingerprint = fixtureFingerprintHint(fixtureDir);
        Files.writeString(fixtureDir.resolve("GENERAL-" + fingerprint + ".json"),
                "{\"taskType\":\"GENERAL\",\"content\":\"旧内容\",\"promptTokens\":1,\"completionTokens\":1}");
        Files.delete(recorded);
        RecordReplayLlmGateway legacyReplay = new RecordReplayLlmGateway(
                unreachable, RecordReplayLlmGateway.Mode.REPLAY, fixtureDir);
        assertEquals("stop", legacyReplay.complete(req).getFinishReason(), "旧 fixture 默认 stop");
    }

    /** 找到录制轮实际写入的 fixture 文件名（指纹未知，按 taskType 前缀扫描非 legacy 的那个） */
    private String fixtureFingerprintHint(Path fixtureDir) throws Exception {
        try (var stream = Files.list(fixtureDir)) {
            return stream.map(p -> p.getFileName().toString())
                    .filter(n -> n.startsWith("GENERAL-") && !n.startsWith("GENERAL-aaaaaaaaaaaaaaaa"))
                    .map(n -> n.replaceAll("^GENERAL-(.+)-1\\.json$", "$1"))
                    .findFirst().orElseThrow();
        }
    }

    private static LlmGateway stubGateway(LlmResponse response) {
        return new LlmGateway() {
            @Override
            public LlmResponse complete(LlmRequest request) {
                if (response == null) throw new IllegalStateException("该桩网关不应被触达");
                return response;
            }

            @Override
            public void streamComplete(LlmRequest request,
                                       com.writing.novel.llm.client.StreamHandler handler) {
                throw new IllegalStateException("该桩网关不支持流式");
            }
        };
    }
}
