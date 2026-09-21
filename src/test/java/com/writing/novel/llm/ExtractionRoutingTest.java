package com.writing.novel.llm;

import com.writing.novel.agent.EntityExtractorAgent;
import com.writing.novel.agent.FactHarvesterAgent;
import com.writing.novel.agent.TestPromptHelper;
import com.writing.novel.llm.client.OpenAiCompatibleClient;
import com.writing.novel.llm.config.LlmProperties;
import com.writing.novel.llm.config.ModelConfig;
import com.writing.novel.llm.gateway.DefaultLlmGateway;
import com.writing.novel.persistence.sqlite.ConnectionProvider;
import com.writing.novel.persistence.sqlite.SqliteNovelRepository;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * NCS-066: 结构化提取独立路由与输出预算。
 * 根因：实体/事实提取走 GENERAL 路由（有效预算 max(请求,4096)=4096 token），而提取输出需求
 * 约 9.4k 字符且思考模型 reasoning 计入输出预算——必然截断（continued-entity-diagnosis-20260921.json）。
 * 方案：新增 EXTRACTION 任务类型（独立 max-tokens/温度/思考开关）；思考关闭参数按端点适配——
 * DashScope 兼容端点用 enable_thinking:false，GLM 端点保持 thinking.type=disabled。
 */
class ExtractionRoutingTest {

    MockWebServer server;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    private ModelConfig config(int maxTokens, double temperature, boolean thinkingEnabled,
                               ModelConfig.ThinkingParamStyle style) {
        ModelConfig cfg = new ModelConfig();
        cfg.setBaseUrl(server.url("/v1").toString().replaceAll("/$", ""));
        cfg.setApiKey("sk-test");
        cfg.setModel("qwen-test");
        cfg.setMaxTokens(maxTokens);
        cfg.setTemperature(temperature);
        cfg.setThinkingEnabled(thinkingEnabled);
        cfg.setStreaming(false);
        if (style != null) cfg.setThinkingParamStyle(style);
        return cfg;
    }

    private void enqueueOk() {
        server.enqueue(new MockResponse().setBody("""
                {"choices":[{"message":{"content":"ok"},"finish_reason":"stop"}],
                 "usage":{"prompt_tokens":1,"completion_tokens":1}}
                """).addHeader("Content-Type", "application/json"));
    }

    /** EXTRACTION 路由的预算/温度独立于 GENERAL，且关思考以 DashScope 形态注入 */
    @Test
    void EXTRACTION路由独立预算温度并注入enable_thinking_false() throws Exception {
        LlmProperties props = new LlmProperties();
        props.getModels().put("EXTRACTION", config(16000, 0.1, false, ModelConfig.ThinkingParamStyle.DASHSCOPE));
        props.getModels().put("GENERAL", config(4096, 0.3, true, null));
        DefaultLlmGateway gateway = new DefaultLlmGateway(props, new OpenAiCompatibleClient());
        enqueueOk();

        LlmRequest req = LlmRequest.builder()
                .taskType(LlmTaskType.EXTRACTION)
                .systemPrompt("提取").userMessage("第1章")
                .temperature(0.1).maxTokens(2000).build();
        LlmResponse resp = gateway.complete(req);
        assertEquals("ok", resp.getContent());

        String body = server.takeRequest().getBody().readUtf8();
        assertTrue(body.contains("\"max_tokens\":16000"), "预算应为路由值16000而非请求值2000: " + body);
        assertTrue(body.contains("\"temperature\":0.1"), body);
        assertTrue(body.contains("\"enable_thinking\":false"), "DashScope 端点关思考应为 enable_thinking:false: " + body);
        assertFalse(body.contains("\"thinking\""), "DashScope 端点不得注入 GLM 形态 thinking 对象: " + body);
    }

    /** GLM 端点关思考保持 thinking.type=disabled，不注入 enable_thinking */
    @Test
    void GLM端点关思考保持thinking_disabled() throws Exception {
        LlmProperties props = new LlmProperties();
        props.getModels().put("EXTRACTION", config(16000, 0.1, false, ModelConfig.ThinkingParamStyle.GLM));
        DefaultLlmGateway gateway = new DefaultLlmGateway(props, new OpenAiCompatibleClient());
        enqueueOk();

        gateway.complete(LlmRequest.builder().taskType(LlmTaskType.EXTRACTION)
                .systemPrompt("s").userMessage("u").build());
        String body = server.takeRequest().getBody().readUtf8();
        assertTrue(body.contains("\"thinking\":{\"type\":\"disabled\"}"), body);
        assertFalse(body.contains("enable_thinking"), "GLM 端点不得注入 DashScope 参数: " + body);
    }

    /** AUTO 风格按端点推断：aliyuncs/dashscope → DashScope 形态；其余 → GLM 形态（现状兼容） */
    @Test
    void AUTO风格按端点推断思考参数形态() {
        ModelConfig auto = config(100, 0.3, false, ModelConfig.ThinkingParamStyle.AUTO);

        auto.setBaseUrl("https://token-plan.cn-beijing.maas.aliyuncs.com/compatible-mode/v1");
        assertEquals(ModelConfig.ThinkingParamStyle.DASHSCOPE,
                OpenAiCompatibleClient.resolveThinkingStyle(auto), "token-plan 端点应推断 DashScope 形态");

        auto.setBaseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1");
        assertEquals(ModelConfig.ThinkingParamStyle.DASHSCOPE,
                OpenAiCompatibleClient.resolveThinkingStyle(auto));

        auto.setBaseUrl("https://open.bigmodel.cn/api/paas/v4");
        assertEquals(ModelConfig.ThinkingParamStyle.GLM,
                OpenAiCompatibleClient.resolveThinkingStyle(auto), "非 DashScope 端点保持 GLM 形态");
    }

    /** 实体提取请求必须走 EXTRACTION 任务类型 */
    @Test
    void 实体提取走EXTRACTION任务类型(@TempDir Path dir) {
        var cp = new ConnectionProvider(dir.resolve("entity.db").toString());
        new com.writing.novel.persistence.sqlite.SqliteSchemaInitializer(cp).initialize();
        var repo = new SqliteNovelRepository(cp);
        var gateway = mock(LlmGateway.class);
        when(gateway.complete(any())).thenReturn(new LlmResponse("{\"entities\":[],\"edges\":[]}", 1, 1));

        var chapter = new com.writing.novel.domain.Chapter("p", 1, "取证");
        chapter.setContent("正文。");
        new EntityExtractorAgent(gateway, repo, TestPromptHelper.create()).extract("p", chapter);

        ArgumentCaptor<LlmRequest> captor = ArgumentCaptor.forClass(LlmRequest.class);
        verify(gateway).complete(captor.capture());
        assertEquals(LlmTaskType.EXTRACTION, captor.getValue().getTaskType(),
                "实体提取必须走 EXTRACTION 路由（独立预算/温度/思考开关）");
    }

    /** 事实收割请求必须走 EXTRACTION 任务类型 */
    @Test
    void 事实收割走EXTRACTION任务类型(@TempDir Path dir) {
        var gateway = mock(LlmGateway.class);
        when(gateway.complete(any())).thenReturn(new LlmResponse("[]", 1, 1));
        var canon = mock(com.writing.novel.spi.StoryCanon.class);
        when(canon.recall("p")).thenReturn(java.util.List.of());

        var chapter = new com.writing.novel.domain.Chapter("p", 1, "取证");
        chapter.setContent("正文。");
        new FactHarvesterAgent(gateway, canon, TestPromptHelper.create()).harvest("p", chapter);

        ArgumentCaptor<LlmRequest> captor = ArgumentCaptor.forClass(LlmRequest.class);
        verify(gateway).complete(captor.capture());
        assertEquals(LlmTaskType.EXTRACTION, captor.getValue().getTaskType(),
                "事实收割必须走 EXTRACTION 路由");
    }
}
