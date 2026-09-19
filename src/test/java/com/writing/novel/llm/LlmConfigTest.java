package com.writing.novel.llm;

import com.writing.novel.llm.config.LlmProperties;
import com.writing.novel.llm.config.ModelConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.io.File;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@TestPropertySource(properties = {
        // p43-01: 禁用模型配置覆盖文件，避免生产 data/config/llm-models.json 污染断言
        "llm.model-config-file=",
        "llm.models.CHAPTER_WRITING.base-url=https://api.example.com/v1",
        "llm.models.CHAPTER_WRITING.api-key=sk-chapter-key",
        "llm.models.CHAPTER_WRITING.model=qwen-max",
        "llm.models.CHAPTER_WRITING.temperature=0.8",
        "llm.models.CHAPTER_WRITING.max-tokens=3000",
        "llm.models.CHECKER.base-url=https://api.example.com/v1",
        "llm.models.CHECKER.api-key=sk-checker-key",
        "llm.models.CHECKER.model=qwen-turbo",
        "llm.models.CHECKER.temperature=0.3"
})
class LlmConfigTest {

    @Autowired
    LlmProperties properties;

    @org.junit.jupiter.api.BeforeAll
    static void createDataDir() { new File("./data").mkdirs(); }

    @Test
    void 按任务类型读取模型配置() {
        ModelConfig chapter = properties.getModels().get("CHAPTER_WRITING");
        assertNotNull(chapter);
        assertEquals("https://api.example.com/v1", chapter.getBaseUrl());
        assertEquals("qwen-max", chapter.getModel());
        assertEquals("sk-chapter-key", chapter.getApiKey());
        assertEquals(0.8, chapter.getTemperature());
        assertEquals(3000, chapter.getMaxTokens());
    }

    @Test
    void 不同任务可配不同模型() {
        ModelConfig checker = properties.getModels().get("CHECKER");
        assertEquals("qwen-turbo", checker.getModel());
        assertEquals(0.3, checker.getTemperature());
    }

    @Test
    void 未配置的任务类型回退到默认() {
        ModelConfig fallback = properties.forTaskType("WORLDBUILDING");
        assertNotNull(fallback, "未配置的任务类型应有默认兜底");
    }
}
