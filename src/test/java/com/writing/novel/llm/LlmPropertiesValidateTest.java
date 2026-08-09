package com.writing.novel.llm;

import com.writing.novel.llm.config.LlmProperties;
import com.writing.novel.llm.config.ModelConfig;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * mc-p0-04: 凭据 fail-fast 语义测试。
 * 远程端点必须带 api-key；本地端点豁免；replay 模式跳过校验。
 */
class LlmPropertiesValidateTest {

    private ModelConfig remote(String key) {
        ModelConfig c = new ModelConfig();
        c.setBaseUrl("https://api.deepseek.com/v1");
        c.setApiKey(key);
        return c;
    }

    private ModelConfig local(String key) {
        ModelConfig c = new ModelConfig();
        c.setBaseUrl("http://127.0.0.1:8088/v1");
        c.setApiKey(key);
        return c;
    }

    @Test
    void 远程端点缺key则failFast() {
        LlmProperties p = new LlmProperties();
        p.setDefaultModel(remote(null));
        IllegalStateException e = assertThrows(IllegalStateException.class, p::validateKeys);
        assertTrue(e.getMessage().contains("api-key 未配置"));
    }

    @Test
    void 远程端点带key则通过() {
        LlmProperties p = new LlmProperties();
        p.setDefaultModel(remote("sk-real"));
        assertDoesNotThrow(p::validateKeys);
    }

    @Test
    void 本地端点缺key也通过() {
        LlmProperties p = new LlmProperties();
        p.setDefaultModel(local(null));
        assertDoesNotThrow(p::validateKeys);
    }

    @Test
    void 任意任务模型缺key都failFast() {
        LlmProperties p = new LlmProperties();
        p.setDefaultModel(local(null));
        p.setModels(Map.of("CHAPTER_WRITING", remote("sk-ok"), "PLOTTING", remote(null)));
        IllegalStateException e = assertThrows(IllegalStateException.class, p::validateKeys);
        assertTrue(e.getMessage().contains("PLOTTING"));
    }

    @Test
    void 全部任务模型带key则通过() {
        LlmProperties p = new LlmProperties();
        p.setDefaultModel(local(null));
        p.setModels(Map.of("CHAPTER_WRITING", remote("sk-ok"), "PLOTTING", remote("sk-ok2")));
        assertDoesNotThrow(p::validateKeys);
    }

    @Test
    void replay模式跳过校验() {
        LlmProperties p = new LlmProperties();
        p.setDefaultModel(remote(null));
        p.setReplayMode(true);
        assertDoesNotThrow(p::validateKeys);
    }

    @Test
    void 本地端点判定() {
        assertTrue(LlmProperties.isLocalBaseUrl("http://localhost:8088/v1"));
        assertTrue(LlmProperties.isLocalBaseUrl("http://127.0.0.1:8088/v1"));
        assertTrue(LlmProperties.isLocalBaseUrl("http://192.168.1.5:9000"));
        assertTrue(LlmProperties.isLocalBaseUrl("http://ollama.local:11434"));
        assertFalse(LlmProperties.isLocalBaseUrl("https://api.deepseek.com/v1"));
        assertFalse(LlmProperties.isLocalBaseUrl(""));
        assertFalse(LlmProperties.isLocalBaseUrl(null));
    }
}
