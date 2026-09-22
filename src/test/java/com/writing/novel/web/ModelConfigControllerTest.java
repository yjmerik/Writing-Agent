package com.writing.novel.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.MvcResult;

import java.io.File;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * p43-01: 模型配置页面 REST API。
 * 用独立测试配置文件（test-llm-models-config.json）避免污染生产覆盖层。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(StubLlmGatewayTestConfig.class)
@TestPropertySource(properties = {
        "persistence.embedding=stub",
        "persistence.sqlite.db-path=./data/test-model-config.db",
        "persistence.vector-store.index-path=./data/test-model-config-vecs.idx",
        "llm.model-config-file=./data/test-llm-models-config.json"
})
class ModelConfigControllerTest {

    private static final File CONFIG_FILE = new File("./data/test-llm-models-config.json");

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;
    @Autowired com.writing.novel.llm.config.LlmProperties llmProperties;
    @Autowired com.writing.novel.llm.config.LlmModelConfigStore configStore;

    @BeforeEach
    @AfterEach
    void cleanConfigFile() throws Exception {
        // 共享上下文：先删光内存中的页面覆盖（恢复 yml 基线），再清测试配置文件
        for (String scope : configStore.overridesSnapshot().keySet().toArray(new String[0])) {
            configStore.delete(scope);
        }
        Files.deleteIfExists(CONFIG_FILE.toPath());
        new File("./data").mkdirs();
    }

    private MvcResult putConfig(String scope, String body) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.put("/api/llm/models/" + scope)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();
    }

    @Test
    void 总览展示yml现有配置_默认与全部任务类型() throws Exception {
        String json = new String(mvc.perform(get("/api/llm/models"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray(), java.nio.charset.StandardCharsets.UTF_8);
        var root = om.readTree(json);

        // UI 验证缺陷修复（NCS-070 发现）：EXTRACTION 路由必须出现在模型配置页
        assertEquals(10, root.path("entries").size());
        var extraction = findEntry(root, "EXTRACTION");
        assertEquals("提取任务", extraction.path("label").asText());
        assertEquals("YML", extraction.path("source").asText());
        assertEquals(16000, extraction.path("maxTokens").asInt());
        var chapter = findEntry(root, "CHAPTER_WRITING");
        var ymlChapter = llmProperties.getModels().get("CHAPTER_WRITING");
        assertEquals("YML", chapter.path("source").asText());
        assertEquals("OPENAI_CHAT_COMPLETIONS", chapter.path("protocol").asText());
        assertEquals(ymlChapter.getModel(), chapter.path("model").asText());
        assertEquals(ymlChapter.getBaseUrl(), chapter.path("baseUrl").asText());
        assertEquals(ymlChapter.getMaxTokens(), chapter.path("maxTokens").asInt());
        // api_key 只暴露掩码，不含明文
        String masked = chapter.path("apiKeyMasked").asText();
        String rawKey = ymlChapter.getApiKey();
        assertTrue(masked.isEmpty() || rawKey == null || rawKey.isBlank()
                || (!masked.equals(rawKey) && !masked.contains(rawKey)), "掩码不应泄露完整 key");
        // 当前 yml 全部 8 个任务类型均已显式配置 → 无可新增范围
        assertEquals(0, root.path("addableScopes").size());
        assertEquals(3, root.path("protocols").size());
    }

    @Test
    void 修改模型配置_来源切换为PAGE并持久化() throws Exception {
        var result = putConfig("GENERAL", om.writeValueAsString(java.util.Map.of(
                "model", "glm-5.3-test",
                "protocol", "ANTHROPIC_MESSAGES",
                "baseUrl", "https://api.anthropic.com/v1",
                "apiKey", "sk-ant-abc-12345678",
                "temperature", 0.4,
                "maxTokens", 8192)));
        var entry = om.readTree(result.getResponse().getContentAsString());
        assertEquals("PAGE", entry.path("source").asText());
        assertEquals("glm-5.3-test", entry.path("model").asText());
        assertEquals("ANTHROPIC_MESSAGES", entry.path("protocol").asText());
        // 新 key 返回掩码而非明文
        assertEquals("sk-a...5678", entry.path("apiKeyMasked").asText());

        assertTrue(CONFIG_FILE.exists(), "覆盖层应已落盘");
        String file = Files.readString(CONFIG_FILE.toPath());
        assertTrue(file.contains("glm-5.3-test"));
        assertTrue(file.contains("ANTHROPIC_MESSAGES"));

        // 总览反映 PAGE 来源
        String json = mvc.perform(get("/api/llm/models")).andReturn().getResponse().getContentAsString();
        assertEquals("PAGE", findEntry(om.readTree(json), "GENERAL").path("source").asText());
    }

    @Test
    void 空api_key保留原值() throws Exception {
        putConfig("GENERAL", om.writeValueAsString(java.util.Map.of(
                "model", "with-key-model",
                "apiKey", "sk-original-key-0000")));

        // 第二次提交不带 key（空字符串）：key 保持第一次的值
        var result = putConfig("GENERAL", om.writeValueAsString(java.util.Map.of(
                "model", "changed-model",
                "apiKey", "")));
        var entry = om.readTree(result.getResponse().getContentAsString());
        assertEquals("sk-o...0000", entry.path("apiKeyMasked").asText());
        assertTrue(entry.path("hasApiKey").asBoolean());

        String file = Files.readString(CONFIG_FILE.toPath());
        assertTrue(file.contains("sk-original-key-0000"), "空提交不应清空已存 key");
    }

    @Test
    void 掩码原样回传视为保留原值() throws Exception {
        // 先读当前掩码
        String json = mvc.perform(get("/api/llm/models")).andReturn().getResponse().getContentAsString();
        String masked = findEntry(om.readTree(json), "STYLE_GUARDIAN").path("apiKeyMasked").asText();

        if (masked.isEmpty()) return; // 环境 key 未配置时跳过该场景

        putConfig("STYLE_GUARDIAN", om.writeValueAsString(java.util.Map.of(
                "model", "keep-key-model",
                "apiKey", masked)));

        String json2 = mvc.perform(get("/api/llm/models")).andReturn().getResponse().getContentAsString();
        String masked2 = findEntry(om.readTree(json2), "STYLE_GUARDIAN").path("apiKeyMasked").asText();
        assertEquals(masked, masked2);
    }

    @Test
    void 删除覆盖回退yml配置() throws Exception {
        // 先取 yml 基线期望值（PUT 会改运行时 properties）
        String ymlModel = llmProperties.getModels().get("MEMORY_SUMMARY").getModel();
        putConfig("MEMORY_SUMMARY", om.writeValueAsString(java.util.Map.of(
                "model", "temp-override-model")));

        String json = mvc.perform(delete("/api/llm/models/MEMORY_SUMMARY"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        var entry = om.readTree(json);
        assertEquals("YML", entry.path("source").asText());
        assertEquals(ymlModel, entry.path("model").asText());

        // 文件中不再有该条目
        String file = Files.readString(CONFIG_FILE.toPath());
        assertFalse(file.contains("temp-override-model"));
    }

    @Test
    void 删除无覆盖的范围返回409() throws Exception {
        mvc.perform(delete("/api/llm/models/CHAPTER_WRITING"))
                .andExpect(status().isConflict());
    }

    @Test
    void 非法入参返回400() throws Exception {
        // 非法协议
        mvc.perform(MockMvcRequestBuilders.put("/api/llm/models/GENERAL").contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(java.util.Map.of("protocol", "NOT_A_PROTOCOL"))))
                .andExpect(status().isBadRequest());
        // 非法 scope
        mvc.perform(get("/api/llm/models")).andExpect(status().isOk());
        mvc.perform(MockMvcRequestBuilders.put("/api/llm/models/NOT_A_SCOPE").contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
        // 非法 baseUrl
        putInvalid(java.util.Map.of("model", "m", "baseUrl", "ftp://bad-example"));
    }

    private void putInvalid(java.util.Map<String, String> body) throws Exception {
        mvc.perform(MockMvcRequestBuilders.put("/api/llm/models/GENERAL").contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(body)))
                .andExpect(status().isBadRequest());
    }

    private com.fasterxml.jackson.databind.JsonNode findEntry(
            com.fasterxml.jackson.databind.JsonNode root, String scope) {
        for (var entry : root.path("entries")) {
            if (scope.equals(entry.path("scope").asText())) return entry;
        }
        fail("缺少条目: " + scope);
        return null;
    }
}
