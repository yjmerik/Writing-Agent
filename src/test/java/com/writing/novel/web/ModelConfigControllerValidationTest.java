package com.writing.novel.web;

import com.writing.novel.llm.config.LlmModelConfigStore;
import com.writing.novel.llm.config.LlmProperties;
import com.writing.novel.llm.config.ModelConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * p43-01: 模型配置保存路径的入参校验（纯单元测试，不走 Spring 上下文——
 * 上下文启动校验本身会拒绝远程端点缺 key，故该场景在此单独覆盖）。
 */
class ModelConfigControllerValidationTest {

    @TempDir
    Path tempDir;

    private ModelConfigController controller() {
        LlmProperties props = new LlmProperties();
        ModelConfig def = new ModelConfig();
        def.setBaseUrl("http://127.0.0.1:9999/v1"); // 本地端点：免 key，可构造「无 key」状态
        def.setModel("local-model");
        props.setDefaultModel(def);
        return new ModelConfigController(
                new LlmModelConfigStore(props, tempDir.resolve("cfg.json").toString()));
    }

    @Test
    void 远程端点缺key拒绝保存() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () ->
                controller().update("GENERAL", new ModelConfigController.UpdateRequest(
                        null, "https://remote.example.com/v1", null, "no-key-model",
                        null, null, null, null, null)));
        assertTrue(e.getMessage().contains("API Key"));
    }

    @Test
    void 本地端点允许空key() {
        var entry = controller().update("GENERAL", new ModelConfigController.UpdateRequest(
                null, "http://192.168.1.10:8080/v1", null, "local-model",
                null, null, null, null, null));
        assertEquals("GENERAL", entry.scope());
        assertEquals("PAGE", entry.source());
        assertFalse(entry.hasApiKey());
    }

    @Test
    void contextWindow必须大于maxTokens() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () ->
                controller().update("GENERAL", new ModelConfigController.UpdateRequest(
                        null, "http://127.0.0.1:9999/v1", null, "m",
                        null, 20000, 10000, null, null)));
        assertTrue(e.getMessage().contains("context_window"));
    }

    @Test
    void temperature范围校验() {
        assertThrows(IllegalArgumentException.class, () ->
                controller().update("GENERAL", new ModelConfigController.UpdateRequest(
                        null, "http://127.0.0.1:9999/v1", null, "m",
                        2.5, null, null, null, null)));
    }

    @Test
    void 空model拒绝() {
        assertThrows(IllegalArgumentException.class, () ->
                controller().update("GENERAL", new ModelConfigController.UpdateRequest(
                        null, "http://127.0.0.1:9999/v1", null, " ",
                        null, null, null, null, null)));
    }

    @Test
    void 掩码规则() {
        assertEquals("", ModelConfigController.mask(null));
        assertEquals("", ModelConfigController.mask(""));
        assertEquals("****", ModelConfigController.mask("sk12345"));
        assertEquals("sk-a...5678", ModelConfigController.mask("sk-ant-abc-12345678"));
    }
}
