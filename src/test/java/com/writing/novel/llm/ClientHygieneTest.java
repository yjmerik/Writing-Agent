package com.writing.novel.llm;

import com.writing.novel.llm.client.OpenAiCompatibleClient;
import com.writing.novel.telemetry.EventLog;
import com.writing.novel.telemetry.EventLog.Listener;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * NCS-135: 传输层卫生——共享 SSE 帧解析（聚合/回调同源）；
 * 审查温和化显式 CONTENT_SOFTENED 事件。
 */
class ClientHygieneTest {

    private final List<Map<String, Object>> events = new CopyOnWriteArrayList<>();
    private Listener listener;

    @BeforeEach
    void setUp() {
        events.clear();
        listener = events::add;
        EventLog.subscribe(listener);
    }

    @AfterEach
    void tearDown() {
        if (listener != null) EventLog.unsubscribe(listener);
    }

    @Test
    void 共享SSE帧解析_终止与usage语义() {
        var done = OpenAiCompatibleClient.parseSseData("[DONE]");
        assertTrue(done.doneMarker());
        assertTrue(done.terminated());

        var content = OpenAiCompatibleClient.parseSseData(
                "{\"choices\":[{\"delta\":{\"content\":\"片段\"}}],\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":2}}");
        assertEquals("片段", content.delta());
        assertEquals(5, content.promptTokens());
        assertEquals(2, content.completionTokens());
        assertFalse(content.terminated());

        var finish = OpenAiCompatibleClient.parseSseData(
                "{\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}");
        assertEquals("stop", finish.finishReason());
        assertTrue(finish.terminated(), "finish_reason 非空即终止（与 NCS-065 语义一致）");
    }

    @Test
    void 审查拦截_温和化发CONTENT_SOFTENED事件() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            // 首次 1301 审查拦截 → 温和化重试；第二次成功
            server.enqueue(new MockResponse().setResponseCode(400)
                    .setBody("{\"error\":{\"code\":1301,\"message\":\"content filter\"}}"));
            server.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
                    .setBody("{\"choices\":[{\"message\":{\"content\":\"温和后的结果\"}}],"
                            + "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1}}"));
            var cfg = new com.writing.novel.llm.config.ModelConfig();
            cfg.setBaseUrl(server.url("/v1").toString());
            cfg.setApiKey("k");
            cfg.setModel("m");
            cfg.setStreaming(false);
            cfg.setRetryAttempts(3);
            cfg.setBackoffBaseMs(1);

            var resp = new OpenAiCompatibleClient().complete(cfg, LlmRequest.builder()
                    .taskType(LlmTaskType.CHAPTER_WRITING)
                    .systemPrompt("激烈冲突描写").userMessage("写正文").build());
            assertEquals("温和后的结果", resp.getContent());
            EventLog.awaitFlush();
            assertTrue(events.stream().anyMatch(e -> "CONTENT_SOFTENED".equals(e.get("event"))
                            && "non-stream".equals(e.get("phase"))),
                    "传输层审查温和化必须事件化（此前对上层不可见）");
        }
    }

    @Test
    void 回调式流式路径_共享解析正常工作() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            server.enqueue(new MockResponse().setHeader("Content-Type", "text/event-stream")
                    .setBody("data: {\"choices\":[{\"delta\":{\"content\":\"你好\"}}]}\n\n"
                            + "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n"
                            + "data: [DONE]\n\n"));
            var cfg = new com.writing.novel.llm.config.ModelConfig();
            cfg.setBaseUrl(server.url("/v1").toString());
            cfg.setApiKey("k");
            cfg.setModel("m");
            cfg.setStreaming(true);

            StringBuilder received = new StringBuilder();
            var completed = new boolean[]{false};
            new OpenAiCompatibleClient().streamComplete(cfg, LlmRequest.builder()
                    .taskType(LlmTaskType.GENERAL).systemPrompt("s").userMessage("u").build(),
                    new com.writing.novel.llm.client.StreamHandler() {
                        @Override public void onToken(String token) { received.append(token); }
                        @Override public void onComplete() { completed[0] = true; }
                        @Override public void onError(Throwable e) { }
                    });
            assertTrue(completed[0], "正常终止流必须回调 onComplete");
            assertEquals("你好", received.toString());
        }
    }
}
