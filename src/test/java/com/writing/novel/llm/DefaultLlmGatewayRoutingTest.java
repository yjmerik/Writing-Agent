package com.writing.novel.llm;

import com.writing.novel.llm.client.LlmClient;
import com.writing.novel.llm.client.StreamHandler;
import com.writing.novel.llm.config.LlmProperties;
import com.writing.novel.llm.config.ModelConfig;
import com.writing.novel.llm.gateway.DefaultLlmGateway;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DefaultLlmGatewayRoutingTest {

    @Test
    void 按任务类型路由到对应模型配置() {
        LlmProperties props = new LlmProperties();
        ModelConfig chapterCfg = new ModelConfig();
        chapterCfg.setModel("qwen-max");
        chapterCfg.setBaseUrl("https://api.a.com/v1");
        chapterCfg.setApiKey("key-a");
        chapterCfg.setMaxTokens(3000);
        chapterCfg.setTemperature(0.8);
        props.getModels().put("CHAPTER_WRITING", chapterCfg);

        ModelConfig checkerCfg = new ModelConfig();
        checkerCfg.setModel("qwen-turbo");
        checkerCfg.setBaseUrl("https://api.b.com/v1");
        checkerCfg.setApiKey("key-b");
        checkerCfg.setMaxTokens(2000);
        checkerCfg.setTemperature(0.3);
        props.getModels().put("CONTINUITY_CHECK", checkerCfg);

        ModelConfig[] received = new ModelConfig[1];
        LlmClient mockClient = new LlmClient() {
            @Override
            public LlmResponse complete(ModelConfig config, LlmRequest request) {
                received[0] = config;
                return new LlmResponse("mock response", 10, 20);
            }
            @Override
            public void streamComplete(ModelConfig config, LlmRequest request, StreamHandler handler) {}
        };

        DefaultLlmGateway gateway = new DefaultLlmGateway(props, mockClient);

        LlmRequest chapterReq = LlmRequest.builder()
                .taskType(LlmTaskType.CHAPTER_WRITING)
                .systemPrompt("sys").userMessage("写章节").build();
        gateway.complete(chapterReq);

        assertEquals("qwen-max", received[0].getModel());
        assertEquals("key-a", received[0].getApiKey());
        assertEquals(3000, received[0].getMaxTokens());
    }

    @Test
    void 未配置的任务回退到默认模型() {
        LlmProperties props = new LlmProperties();
        ModelConfig defaultCfg = new ModelConfig();
        defaultCfg.setModel("qwen-plus");
        defaultCfg.setApiKey("default-key");
        props.setDefaultModel(defaultCfg);

        ModelConfig[] received = new ModelConfig[1];
        LlmClient mockClient = new LlmClient() {
            @Override
            public LlmResponse complete(ModelConfig config, LlmRequest request) {
                received[0] = config;
                return new LlmResponse("ok", 1, 1);
            }
            @Override
            public void streamComplete(ModelConfig config, LlmRequest request, StreamHandler handler) {}
        };

        DefaultLlmGateway gateway = new DefaultLlmGateway(props, mockClient);

        gateway.complete(LlmRequest.builder()
                .taskType(LlmTaskType.WORLDBUILDING)
                .systemPrompt("s").userMessage("u").build());

        assertEquals("qwen-plus", received[0].getModel());
    }

    @Test
    void 请求中的temperature覆盖配置默认值() {
        LlmProperties props = new LlmProperties();
        ModelConfig cfg = new ModelConfig();
        cfg.setModel("m");
        cfg.setTemperature(0.7);
        props.getModels().put("CHAPTER_WRITING", cfg);

        ModelConfig[] received = new ModelConfig[1];
        LlmClient mockClient = new LlmClient() {
            @Override
            public LlmResponse complete(ModelConfig config, LlmRequest request) {
                received[0] = config;
                return new LlmResponse("ok", 1, 1);
            }
            @Override
            public void streamComplete(ModelConfig config, LlmRequest request, StreamHandler handler) {}
        };

        DefaultLlmGateway gateway = new DefaultLlmGateway(props, mockClient);

        gateway.complete(LlmRequest.builder()
                .taskType(LlmTaskType.CHAPTER_WRITING)
                .systemPrompt("s").userMessage("u")
                .temperature(0.95)
                .build());

        assertEquals(0.95, received[0].getTemperature());
    }
}
