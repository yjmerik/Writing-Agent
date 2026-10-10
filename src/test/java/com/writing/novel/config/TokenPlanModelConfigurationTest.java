package com.writing.novel.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.writing.novel.llm.LlmRequest;
import com.writing.novel.llm.LlmTaskType;
import com.writing.novel.llm.client.OpenAiCompatibleClient;
import com.writing.novel.llm.config.LlmProperties;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class TokenPlanModelConfigurationTest {
    private StandardEnvironment environment(Map<String, Object> variables) throws Exception {
        var env = new StandardEnvironment();
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        env.getPropertySources().addFirst(new MapPropertySource("synthetic-credentials", variables));
        for (var source : new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"))) {
            env.getPropertySources().addLast(source);
        }
        return env;
    }

    @Test
    void allRoutesUseTokenPlanAndKeepExistingBudgets() throws Exception {
        var env = environment(Map.of("DASHSCOPE_TOKEN_PLAN_API_KEY", "test-plan-key"));
        var properties = Binder.get(env).bind("llm", LlmProperties.class).get();
        properties.validateKeys();
        // NCS-108: providers/routing 唯一形态——六参数档全走 Token Plan，九任务显式路由
        assertFalse(properties.isLegacyRouting());
        assertEquals(6, properties.getProviders().size());
        assertEquals(9, properties.getRouting().getTasks().size());
        for (var config : properties.getProviders().values()) {
            assertEquals("https://token-plan.cn-beijing.maas.aliyuncs.com/compatible-mode/v1", config.getBaseUrl());
            assertEquals("qwen3.8-flash", config.getModel());
            assertEquals("test-plan-key", config.getApiKey());
            assertEquals(64000, config.getContextWindow());
            assertTrue(config.isStreaming());
        }
        assertEquals(20000, properties.forTaskType("CHAPTER_WRITING").getMaxTokens());
        // NCS-066: 提取路由独立预算且关思考（输出不被 reasoning 挤占）
        // NCS-186: 16000→32000——deepseek-v4-flash 实体图谱 50k+ 字符在 16k 截断（章后处理必败）
        var extraction = properties.forTaskType("EXTRACTION");
        assertEquals(32000, extraction.getMaxTokens());
        assertEquals(0.1, extraction.getTemperature());
        assertFalse(extraction.isThinkingEnabled());
    }

    @Test
    void unrelatedKeysCannotSilentlySelectAnotherBillingChannel() throws Exception {
        var env = environment(Map.of("DEEPSEEK_API_KEY", "test-old-key", "DASHSCOPE_API_KEY", "test-payg-key"));
        var properties = Binder.get(env).bind("llm", LlmProperties.class).get();
        assertThrows(IllegalStateException.class, properties::validateKeys);
        // NCS-108: providers 形态下无关 key 不得给任何 provider 供钥
        for (var config : properties.getProviders().values()) {
            assertTrue(config.getApiKey() == null || config.getApiKey().isBlank());
        }
        assertEquals("test-payg-key", env.getProperty("persistence.embedding-cfg.api-key"));
        assertEquals("qwen3.7-text-embedding", env.getProperty("persistence.embedding-cfg.model"));
    }

    @Test
    void configuredClientSendsQwenAndPlanKeyUsingOpenAiStreamPath() throws Exception {
        try (var server = new MockWebServer()) {
            server.start();
            var env = environment(Map.of("DASHSCOPE_TOKEN_PLAN_API_KEY", "test-plan-key",
                    "DASHSCOPE_TOKEN_PLAN_BASE_URL", server.url("/compatible-mode/v1").toString()));
            var properties = Binder.get(env).bind("llm", LlmProperties.class).get();
            assertEquals(server.url("/compatible-mode/v1").toString(),
                    properties.forTaskType("CHAPTER_WRITING").getBaseUrl());
            server.enqueue(new MockResponse().setHeader("Content-Type", "text/event-stream")
                    .setBody("data: {\"choices\":[{\"delta\":{\"content\":\"配置验证通过\"}}]}\n\ndata: [DONE]\n\n"));
            var response = new OpenAiCompatibleClient().complete(properties.forTaskType("CHAPTER_WRITING"),
                    LlmRequest.builder().taskType(LlmTaskType.CHAPTER_WRITING)
                            .systemPrompt("配置测试").userMessage("连通模拟").build());
            assertEquals("配置验证通过", response.getContent());
            var request = server.takeRequest(2, TimeUnit.SECONDS);
            assertNotNull(request);
            assertEquals("/compatible-mode/v1/chat/completions", request.getPath());
            assertEquals("Bearer test-plan-key", request.getHeader("Authorization"));
            var body = new ObjectMapper().readTree(request.getBody().readUtf8());
            assertEquals("qwen3.8-flash", body.path("model").asText());
            assertTrue(body.path("stream").asBoolean());
        }
    }

    @TempDir Path profileRoot;
    private static final String PLAN_BASE = "https://token-plan.cn-beijing.maas.aliyuncs.com/compatible-mode/v1";

    private Map<String, Object> provider(String base, String model, String key) {
        return Map.of("options", Map.of("baseURL", base, "apiKey", key), "models", Map.of(model, Map.of()));
    }

    private String loadTokenKey(Map<String, Object> providers, Map<String, String> variables) throws Exception {
        Files.createDirectories(profileRoot.resolve(".zcode/v2"));
        Files.writeString(profileRoot.resolve(".zcode/v2/config.json"),
                new ObjectMapper().writeValueAsString(Map.of("provider", providers)));
        String script = Files.readString(Path.of("scripts/load-env.sh"))
                .replace("$HOME", "$TASK_PROFILE_ROOT")
                + "\nprintf '%s' \"${DASHSCOPE_TOKEN_PLAN_API_KEY-unset}\"\n";
        var builder = new ProcessBuilder("bash", "-c", script).redirectErrorStream(true);
        builder.environment().keySet().removeIf(key -> key.endsWith("_API_KEY"));
        builder.environment().put("TASK_PROFILE_ROOT", profileRoot.toString());
        builder.environment().putAll(variables);
        var process = builder.start();
        boolean finished = process.waitFor(10, TimeUnit.SECONDS);
        if (!finished) process.destroyForcibly();
        assertTrue(finished, "Credential loader timed out");
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(0, process.exitValue(), output);
        return output;
    }

    @Test void explicitTokenKeyWinsOverSavedProvider() throws Exception {
        assertEquals("sk-sp-explicit", loadTokenKey(
                Map.of("saved", provider(PLAN_BASE, "qwen3.8-flash", "sk-sp-saved")),
                Map.of("DASHSCOPE_TOKEN_PLAN_API_KEY", "sk-sp-explicit")));
    }

    @Test void matchingProviderLoadsItsPlanKey() throws Exception {
        assertEquals("sk-sp-saved", loadTokenKey(
                Map.of("saved", provider(PLAN_BASE, "qwen3.8-flash", "sk-sp-saved")), Map.of()));
    }

    @Test void wrongHostAndWrongModelCannotSupplyCredentials() throws Exception {
        assertEquals("unset", loadTokenKey(Map.of(
                "host", provider("https://token-plan.cn-beijing.maas.aliyuncs.com.example.invalid", "qwen3.8-flash", "sk-sp-host"),
                "model", provider(PLAN_BASE, "other-model", "sk-sp-model")), Map.of()));
    }

    @Test void ambiguousDifferentKeysAreNotChosenAutomatically() throws Exception {
        assertEquals("unset", loadTokenKey(Map.of(
                "first", provider(PLAN_BASE, "qwen3.8-flash", "sk-sp-first"),
                "second", provider(PLAN_BASE, "qwen3.8-flash", "sk-sp-second")), Map.of()));
    }

    @Test void repeatedMatchingProvidersWithSameKeyAreUnambiguous() throws Exception {
        assertEquals("sk-sp-shared", loadTokenKey(Map.of(
                "first", provider(PLAN_BASE, "qwen3.8-flash", "sk-sp-shared"),
                "second", provider(PLAN_BASE, "qwen3.8-flash", "sk-sp-shared")), Map.of()));
    }
}
