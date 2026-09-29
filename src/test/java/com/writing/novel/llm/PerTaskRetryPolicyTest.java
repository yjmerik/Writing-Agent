package com.writing.novel.llm;

import com.writing.novel.llm.client.OpenAiCompatibleClient;
import com.writing.novel.llm.config.ModelConfig;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * NCS-129: 按任务超时与重试策略——retryAttempts/backoffBaseMs 可按任务配置；
 * 退避指数化 + jitter；默认行为（未配置）与旧三连重试一致。
 */
class PerTaskRetryPolicyTest {

    @Test
    void 配置默认值_次数回退3_基数回退1000() {
        var cfg = new ModelConfig();
        assertEquals(3, cfg.effectiveRetryAttempts(), "未配置回退默认 3 次");
        assertEquals(1000, cfg.effectiveBackoffBaseMs());
        cfg.setRetryAttempts(1);
        cfg.setBackoffBaseMs(200);
        assertEquals(1, cfg.effectiveRetryAttempts());
        assertEquals(200, cfg.effectiveBackoffBaseMs());
        cfg.setRetryAttempts(0);
        assertEquals(3, cfg.effectiveRetryAttempts(), "0 回退默认");
    }

    @Test
    void 重试一次即成功_按配置只试一次不重试() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            server.enqueue(new MockResponse().setResponseCode(500).setBody("boom"));
            server.enqueue(new MockResponse().setResponseCode(500).setBody("boom"));
            var cfg = new ModelConfig();
            cfg.setBaseUrl(server.url("/v1").toString());
            cfg.setApiKey("k");
            cfg.setModel("m");
            cfg.setStreaming(false);
            cfg.setRetryAttempts(1);          // 只允许一次
            cfg.setBackoffBaseMs(1);          // 退避最小化

            var ex = assertThrows(RuntimeException.class, () ->
                    new OpenAiCompatibleClient().complete(cfg, LlmRequest.builder()
                            .taskType(LlmTaskType.GENERAL).systemPrompt("s").userMessage("u").build()));
            assertTrue(ex.getMessage().contains("重试耗尽") || ex.getMessage().contains("500"));
            assertEquals(1, server.getRequestCount(), "retryAttempts=1 时 500 后不得再发请求");
        }
    }

    @Test
    void 默认三连重试_第三次成功() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            server.enqueue(new MockResponse().setResponseCode(500).setBody("x"));
            server.enqueue(new MockResponse().setResponseCode(500).setBody("x"));
            server.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
                    .setBody("{\"choices\":[{\"message\":{\"content\":\"终于成功\"},"
                            + "\"finish_reason\":\"stop\"}],"
                            + "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1}}"));
            var cfg = new ModelConfig();
            cfg.setBaseUrl(server.url("/v1").toString());
            cfg.setApiKey("k");
            cfg.setModel("m");
            cfg.setStreaming(false);
            OpenAiCompatibleClient.setBackoffUnitMs(1);   // 测试加速

            var resp = new OpenAiCompatibleClient().complete(cfg, LlmRequest.builder()
                    .taskType(LlmTaskType.GENERAL).systemPrompt("s").userMessage("u").build());
            assertEquals("终于成功", resp.getContent());
            assertEquals(3, server.getRequestCount(), "默认重试上限 3 保持");
        } finally {
            OpenAiCompatibleClient.setBackoffUnitMs(1000);
        }
    }
}
