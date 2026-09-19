package com.writing.novel.llm;

import com.writing.novel.llm.config.LlmModelConfigStore;
import com.writing.novel.llm.config.LlmProperties;
import com.writing.novel.llm.config.LlmProtocol;
import com.writing.novel.llm.config.ModelConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class LlmModelConfigStoreTest {

    @TempDir
    Path tempDir;

    private LlmProperties ymlProperties() {
        LlmProperties props = new LlmProperties();
        ModelConfig def = new ModelConfig();
        def.setBaseUrl("https://default.example.com/v1");
        def.setApiKey("sk-default-key-123456");
        def.setModel("default-model");
        def.setMaxTokens(4096);
        props.setDefaultModel(def);
        ModelConfig chapter = new ModelConfig();
        chapter.setBaseUrl("https://default.example.com/v1");
        chapter.setApiKey("sk-default-key-123456");
        chapter.setModel("chapter-model");
        chapter.setMaxTokens(20000);
        chapter.setTemperature(0.8);
        chapter.setStyleGuide("保持风格");
        props.getModels().put("CHAPTER_WRITING", chapter);
        return props;
    }

    private LlmModelConfigStore store(LlmProperties props) {
        return new LlmModelConfigStore(props, tempDir.resolve("llm-models.json").toString());
    }

    @Test
    void 保存覆盖立即生效并持久化到JSON() throws Exception {
        LlmProperties props = ymlProperties();
        LlmModelConfigStore store = store(props);

        ModelConfig merged = LlmModelConfigStore.copy(props.getModels().get("CHAPTER_WRITING"));
        merged.setModel("new-model");
        merged.setProtocol(LlmProtocol.ANTHROPIC_MESSAGES);
        merged.setApiKey("sk-ant-new-key-9999");
        store.save("CHAPTER_WRITING", merged);

        // 运行时立即生效
        assertEquals("new-model", props.forTaskType("CHAPTER_WRITING").getModel());
        assertEquals(LlmProtocol.ANTHROPIC_MESSAGES, props.forTaskType("CHAPTER_WRITING").getProtocol());
        // 落盘
        Path file = tempDir.resolve("llm-models.json");
        assertTrue(Files.exists(file));
        String json = Files.readString(file);
        assertTrue(json.contains("new-model"));
        assertTrue(json.contains("ANTHROPIC_MESSAGES"));
        assertTrue(json.contains("sk-ant-new-key-9999"));
        // 未覆盖的任务不受影响
        assertEquals("default-model", props.forTaskType("GENERAL").getModel());
    }

    @Test
    void 重启后覆盖层从JSON恢复到运行时() {
        LlmProperties props = ymlProperties();
        LlmModelConfigStore first = store(props);
        ModelConfig merged = LlmModelConfigStore.copy(props.getDefaultModel());
        merged.setModel("file-default-model");
        first.save(LlmModelConfigStore.DEFAULT_SCOPE, merged);

        // 模拟重启：新的 properties（yml 基线）+ 新 store 读同一文件
        LlmProperties rebooted = ymlProperties();
        LlmModelConfigStore second = store(rebooted);
        assertEquals("file-default-model", rebooted.getDefaultModel().getModel());
        assertTrue(second.hasOverride(LlmModelConfigStore.DEFAULT_SCOPE));
        assertFalse(second.hasOverride("CHAPTER_WRITING"));
        assertEquals("chapter-model", rebooted.forTaskType("CHAPTER_WRITING").getModel());
    }

    @Test
    void 删除覆盖回退yml基线值并清理文件条目() throws Exception {
        LlmProperties props = ymlProperties();
        LlmModelConfigStore store = store(props);
        ModelConfig merged = LlmModelConfigStore.copy(props.getModels().get("CHAPTER_WRITING"));
        merged.setModel("override-model");
        store.save("CHAPTER_WRITING", merged);
        assertEquals("override-model", props.forTaskType("CHAPTER_WRITING").getModel());

        store.delete("CHAPTER_WRITING");

        // 回退 yml 基线（含未在表单暴露的字段，如 styleGuide）
        ModelConfig restored = props.forTaskType("CHAPTER_WRITING");
        assertEquals("chapter-model", restored.getModel());
        assertEquals("保持风格", restored.getStyleGuide());
        assertFalse(store.hasOverride("CHAPTER_WRITING"));
        String json = Files.readString(tempDir.resolve("llm-models.json"));
        assertFalse(json.contains("override-model"));
    }

    @Test
    void 删除基线中不存在的覆盖后该任务恢复跟随默认() {
        LlmProperties props = ymlProperties();
        LlmModelConfigStore store = store(props);
        ModelConfig merged = LlmModelConfigStore.copy(props.getDefaultModel());
        merged.setModel("general-override");
        store.save("GENERAL", merged);
        assertEquals("general-override", props.forTaskType("GENERAL").getModel());

        store.delete("GENERAL");

        // yml 基线无 GENERAL 条目 → models map 移除，运行时回退 defaultModel
        assertFalse(props.getModels().containsKey("GENERAL"));
        assertEquals("default-model", props.forTaskType("GENERAL").getModel());
    }

    @Test
    void 删除不存在的覆盖抛出冲突异常() {
        LlmModelConfigStore store = store(ymlProperties());
        assertThrows(IllegalStateException.class, () -> store.delete("CHAPTER_WRITING"));
    }

    @Test
    void 非法scope被拒绝() {
        LlmModelConfigStore store = store(ymlProperties());
        ModelConfig cfg = new ModelConfig();
        assertThrows(IllegalArgumentException.class, () -> store.save("NOT_A_TASK", cfg));
        assertThrows(IllegalArgumentException.class, () -> store.delete("NOT_A_TASK"));
    }

    @Test
    void 空路径禁用文件IO且行为不变() throws Exception {
        LlmProperties props = ymlProperties();
        LlmModelConfigStore store = new LlmModelConfigStore(props, "");
        ModelConfig merged = LlmModelConfigStore.copy(props.getDefaultModel());
        merged.setModel("memory-only");
        store.save(LlmModelConfigStore.DEFAULT_SCOPE, merged);

        assertEquals("memory-only", props.getDefaultModel().getModel());
        assertFalse(Files.exists(tempDir.resolve("llm-models.json")));
        // tempDir 下没有任何新文件
        Optional<Path> leaked;
        try (var stream = Files.list(tempDir)) {
            leaked = stream.findAny();
        }
        assertTrue(leaked.isEmpty());
    }

    @Test
    void scope合法性校验() {
        assertTrue(LlmModelConfigStore.isValidScope("DEFAULT"));
        assertTrue(LlmModelConfigStore.isValidScope("CHAPTER_WRITING"));
        assertFalse(LlmModelConfigStore.isValidScope("default"));
        assertFalse(LlmModelConfigStore.isValidScope("UNKNOWN"));
    }
}
