package com.writing.novel.web;

import com.writing.novel.llm.LlmRequest;
import com.writing.novel.llm.LlmResponse;
import com.writing.novel.llm.client.LlmClient;
import com.writing.novel.llm.client.StreamHandler;
import com.writing.novel.llm.config.LlmModelConfigStore;
import com.writing.novel.llm.config.LlmProperties;
import com.writing.novel.llm.config.ModelConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * NCS-142（旅程三 F-3 产品化收尾）：模型连通性自检——
 * 保存/切换路由前以候选配置发一次微型补全，连通/鉴权问题当场可见，
 * 避免"新建项目第一个章写在坏路由上必卡"。
 */
class ModelConnectivityCheckTest {

    @TempDir Path tempDir;

    /** 捕获探测请求与配置的桩客户端 */
    static final class CapturingClient implements LlmClient {
        final List<LlmRequest> requests = new ArrayList<>();
        final List<ModelConfig> configs = new ArrayList<>();
        RuntimeException failure;
        String reply = "pong";

        @Override
        public LlmResponse complete(ModelConfig config, LlmRequest request) {
            configs.add(config);
            requests.add(request);
            if (failure != null) throw failure;
            return new LlmResponse(reply, 1, 1);
        }

        @Override
        public void streamComplete(ModelConfig config, LlmRequest request, StreamHandler handler) {
            throw new UnsupportedOperationException();
        }
    }

    CapturingClient client;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        client = new CapturingClient();
        LlmProperties props = new LlmProperties();
        ModelConfig def = new ModelConfig();
        def.setBaseUrl("http://127.0.0.1:9999/v1");
        def.setModel("local-model");
        def.setApiKey("");
        def.setContextWindow(64000);
        props.setDefaultModel(def);
        var store = new LlmModelConfigStore(props, tempDir.resolve("cfg.json").toString());
        mvc = MockMvcBuilders.standaloneSetup(new ModelConfigController(store, client))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    void 探测成功返回ok与延迟() throws Exception {
        mvc.perform(post("/api/llm/models/DEFAULT/test")).andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.model").value("local-model"))
                .andExpect(jsonPath("$.latencyMs").isNumber())
                .andExpect(jsonPath("$.sample").isString());
        // 探测必须是微型且单次：maxTokens 受限、singleAttempt、短超时
        assertEquals(1, client.requests.size());
        assertTrue(client.requests.get(0).getMaxTokens() <= 64, "探测请求 maxTokens 必须受限");
        assertTrue(client.configs.get(0).isSingleAttempt(), "探测必须单次尝试");
        assertTrue(client.configs.get(0).getRequestTimeoutSeconds() <= 60, "探测超时必须短");
    }

    @Test
    void 探测失败返回结构化错误而非500() throws Exception {
        client.failure = new RuntimeException("Connection refused");
        mvc.perform(post("/api/llm/models/DEFAULT/test")).andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.error").value("Connection refused"))
                .andExpect(jsonPath("$.latencyMs").isNumber());
    }

    /** NCS-160（review 整改）：LlmProbe.run 内部防御性拷贝，不得突变调用方入参。 */
    @Test
    void 探测不突变入参配置() throws Exception {
        ModelConfig candidate = new ModelConfig();
        candidate.setBaseUrl("http://127.0.0.1:9999/v1");
        candidate.setModel("input-model");
        candidate.setMaxTokens(8000);
        candidate.setStreaming(true);
        candidate.setSingleAttempt(false);
        var before = Map.entry(candidate.getMaxTokens(), candidate.isSingleAttempt());

        var result = com.writing.novel.llm.LlmProbe.run(client, candidate);

        assertEquals(true, result.get("ok"));
        assertEquals(8000, candidate.getMaxTokens(), "入参 maxTokens 不得被探测改为受限值");
        assertTrue(candidate.isStreaming(), "入参 streaming 不得被探测关闭");
        assertFalse(candidate.isSingleAttempt(), "入参 singleAttempt 不得被探测改动");
        assertEquals(before, Map.entry(candidate.getMaxTokens(), candidate.isSingleAttempt()));
        // 探测本身仍受限
        assertTrue(client.configs.get(0).getMaxTokens() <= 64);
        assertTrue(client.configs.get(0).isSingleAttempt());
    }

    @Test
    void 探测使用候选覆盖而非仅已存配置() throws Exception {
        mvc.perform(post("/api/llm/models/DEFAULT/test")
                        .contentType("application/json")
                        .content("{\"model\":\"candidate-model\",\"baseUrl\":\"http://127.0.0.1:9999/v1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.model").value("candidate-model"));
        assertEquals("candidate-model", client.configs.get(0).getModel(), "探测必须用候选覆盖值");
    }

    @Test
    void 未知范围返回400() throws Exception {
        mvc.perform(post("/api/llm/models/NOPE/test")).andExpect(status().isBadRequest());
    }

    /// === 模型库（/api/llm/library）探测端点 ===

    private MockMvc libraryMvc() {
        LlmProperties props = new LlmProperties();
        ModelConfig saved = new ModelConfig();
        saved.setBaseUrl("http://127.0.0.1:9999/v1");
        saved.setModel("saved-model");
        saved.setApiKey("sk-saved");
        props.getProviders().put("my-model", saved);
        var controller = new ModelLibraryController(props, client);
        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    void 库存条目探测成功与失败都结构化返回() throws Exception {
        MockMvc lib = libraryMvc();
        lib.perform(post("/api/llm/library/my-model/test")).andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.model").value("saved-model"));
        client.failure = new RuntimeException("401 Unauthorized");
        lib.perform(post("/api/llm/library/my-model/test")).andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.error").value("401 Unauthorized"));
    }

    @Test
    void 库候选探测与不存在条目40x() throws Exception {
        MockMvc lib = libraryMvc();
        lib.perform(post("/api/llm/library/test")
                        .contentType("application/json")
                        .content("{\"baseUrl\":\"http://127.0.0.1:9999/v1\",\"model\":\"cand\",\"apiKey\":\"k\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.model").value("cand"));
        lib.perform(post("/api/llm/library/ghost/test")).andExpect(status().isBadRequest());
        lib.perform(post("/api/llm/library/test")
                        .contentType("application/json")
                        .content("{\"model\":\"missing-base\"}"))
                .andExpect(status().isBadRequest());
    }
}
