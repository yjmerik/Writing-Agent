package com.writing.novel.web;

import com.writing.novel.llm.LlmRequest;
import com.writing.novel.llm.LlmResponse;
import com.writing.novel.llm.client.LlmClient;
import com.writing.novel.llm.client.StreamHandler;
import com.writing.novel.llm.config.LlmProperties;
import com.writing.novel.llm.config.ModelConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * NCS-162（review 整改，落 NCS-142「失败即时不入库」）：模型库保存前连通性探测——
 * 探测失败返回 400 结构化错误且配置不入库；?force=true 离线逃生门；探测成功正常入库。
 */
class ModelLibrarySaveGateTest {

    @TempDir Path tempDir;

    /** 与 ModelConnectivityCheckTest 同形的桩客户端 */
    static final class StubClient implements LlmClient {
        final List<ModelConfig> configs = new ArrayList<>();
        RuntimeException failure;
        String reply = "pong";

        @Override
        public LlmResponse complete(ModelConfig config, LlmRequest request) {
            configs.add(config);
            if (failure != null) throw failure;
            return new LlmResponse(reply, 1, 1);
        }

        @Override
        public void streamComplete(ModelConfig config, LlmRequest request, StreamHandler handler) {
            throw new UnsupportedOperationException();
        }
    }

    StubClient client;
    LlmProperties props;
    MockMvc lib;

    @BeforeEach
    void setUp() {
        client = new StubClient();
        props = new LlmProperties();
        props.getProviders(); // 触发默认 provider 初始化（如有）
        var controller = new ModelLibraryController(props, client);
        lib = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    private static final String BODY =
            "{\"baseUrl\":\"http://127.0.0.1:9999/v1\",\"model\":\"cand\",\"apiKey\":\"k\",\"contextWindow\":64000}";

    @Test
    void 探测失败返回400结构化错误且不入库() throws Exception {
        client.failure = new RuntimeException("401 Unauthorized");
        lib.perform(put("/api/llm/library/new-model").contentType("application/json").content(BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.saved").value(false))
                .andExpect(jsonPath("$.code").value("MODEL_PROBE_FAILED"))
                .andExpect(jsonPath("$.probe.ok").value(false))
                .andExpect(jsonPath("$.probe.error").value("401 Unauthorized"));
        org.junit.jupiter.api.Assertions.assertFalse(
                props.getProviders().containsKey("new-model"), "探测失败不得入库");
    }

    @Test
    void force逃生门跳过探测直接入库() throws Exception {
        client.failure = new RuntimeException("离线环境连不上");
        lib.perform(put("/api/llm/library/offline-model?force=true")
                        .contentType("application/json").content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("offline-model"));
        org.junit.jupiter.api.Assertions.assertTrue(props.getProviders().containsKey("offline-model"));
    }

    @Test
    void 探测成功正常入库且已存条目更新走同样门禁() throws Exception {
        lib.perform(put("/api/llm/library/good-model").contentType("application/json").content(BODY))
                .andExpect(status().isOk());
        org.junit.jupiter.api.Assertions.assertTrue(props.getProviders().containsKey("good-model"));
        org.junit.jupiter.api.Assertions.assertEquals(1, client.configs.size(), "保存前应恰好探测一次");

        // 已存条目再次保存：探测使用合并后候选，失败同样拒存且保留原配置
        props.getProviders().put("existing", new ModelConfig());
        props.getProviders().get("existing").setModel("keep-original-model");
        client.failure = new RuntimeException("endpoint down");
        lib.perform(put("/api/llm/library/existing").contentType("application/json").content(BODY))
                .andExpect(status().isBadRequest());
        org.junit.jupiter.api.Assertions.assertEquals("keep-original-model",
                props.getProviders().get("existing").getModel(), "拒存后原配置保持不变");
    }
}
