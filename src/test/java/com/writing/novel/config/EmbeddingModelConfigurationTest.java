package com.writing.novel.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.writing.novel.persistence.embedding.CloudApiEmbeddingProvider;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class EmbeddingModelConfigurationTest {
    private static final String PREFIX = "persistence.embedding-cfg.";

    private StandardEnvironment config(Map<String, Object> variables) throws Exception {
        var env = new StandardEnvironment();
        // Use synthetic credentials so the test never depends on or exposes host secrets.
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        env.getPropertySources().addFirst(new MapPropertySource("test-variables", variables));
        for (var source : new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"))) {
            env.getPropertySources().addLast(source);
        }
        return env;
    }

    @Test
    void usesDocumentedBeijingModelAndSeparateVectorIndex() throws Exception {
        var env = config(Map.of());
        assertEquals("qwen3.7-text-embedding", env.getProperty(PREFIX + "model"));
        assertEquals("https://dashscope.aliyuncs.com/compatible-mode/v1", env.getProperty(PREFIX + "api-base-url"));
        assertEquals("./data/vectors-qwen3.7-text-embedding-1024.idx", env.getProperty("persistence.vector-store.index-path"));
    }

    @Test
    void fallsBackToStandardDashscopeKeyAndSendsConfiguredModel() throws Exception {
        try (var server = new MockWebServer()) {
            server.start();
            var env = config(Map.of("DASHSCOPE_API_KEY", "test-standard-key",
                    "DASHSCOPE_EMBEDDING_BASE_URL", server.url("/compatible-mode/v1").toString()));
            assertEquals("test-standard-key", env.getProperty(PREFIX + "api-key"));
            server.enqueue(new MockResponse().setBody("{\"data\":[{\"index\":0,\"embedding\":[0.1,0.2]}]}"));
            var provider = new CloudApiEmbeddingProvider(env.getProperty(PREFIX + "api-base-url"),
                    env.getProperty(PREFIX + "api-key"), env.getProperty(PREFIX + "model"));
            assertEquals(2, provider.embed("水文档案连接验证").length);
            var request = server.takeRequest(2, TimeUnit.SECONDS);
            assertNotNull(request);
            assertEquals("/compatible-mode/v1/embeddings", request.getPath());
            assertEquals("Bearer test-standard-key", request.getHeader("Authorization"));
            var body = new ObjectMapper().readTree(request.getBody().readUtf8());
            assertEquals("qwen3.7-text-embedding", body.path("model").asText());
            assertEquals("水文档案连接验证", body.path("input").get(0).asText());
        }
    }

    @Test
    void dedicatedEmbeddingKeyTakesPrecedence() throws Exception {
        var env = config(Map.of("DASHSCOPE_API_KEY", "test-standard-key",
                "DASHSCOPE_EMBEDDING_API_KEY", "test-dedicated-key"));
        assertEquals("test-dedicated-key", env.getProperty(PREFIX + "api-key"));
    }

    @Test
    void supportsWorkspaceSpecificEndpoint() throws Exception {
        String endpoint = "https://llm-example.cn-beijing.maas.aliyuncs.com/compatible-mode/v1";
        var env = config(Map.of("DASHSCOPE_EMBEDDING_BASE_URL", endpoint));
        assertEquals(endpoint, env.getProperty(PREFIX + "api-base-url"));
    }

    @TempDir Path profileRoot;

    private String loadCredentials(String profile, String cliKey, Map<String, String> variables) throws Exception {
        Files.writeString(profileRoot.resolve(".zshrc"), profile);
        Files.createDirectories(profileRoot.resolve(".bailian"));
        Files.writeString(profileRoot.resolve(".bailian/config.json"),
                new ObjectMapper().writeValueAsString(Map.of("api_key", cliKey)));
        // Execute the real loader against synthetic profiles; never change HOME or read host credentials.
        String script = Files.readString(Path.of("scripts/load-env.sh"))
                .replace("$HOME", "$TASK_PROFILE_ROOT")
                + "\nprintf '<%s>|<%s>' \"${DASHSCOPE_EMBEDDING_API_KEY-unset}\" \"${DASHSCOPE_API_KEY-unset}\"\n";
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

    @Test void explicitEmptyDedicatedKeyIsNotReplacedByShellProfile() throws Exception {
        assertEquals("<>|<sk-sp-plan>", loadCredentials(
                "export DASHSCOPE_EMBEDDING_API_KEY=sk-profile\n", "sk-cli",
                Map.of("DASHSCOPE_EMBEDDING_API_KEY", "", "DASHSCOPE_API_KEY", "sk-sp-plan")));
    }

    @Test void explicitEmptyDedicatedKeyIsNotReplacedByCliProfile() throws Exception {
        assertEquals("<>|<sk-sp-plan>", loadCredentials("", "sk-cli",
                Map.of("DASHSCOPE_EMBEDDING_API_KEY", "", "DASHSCOPE_API_KEY", "sk-sp-plan")));
    }

    @Test void explicitDedicatedKeyWinsOverBothSavedProfiles() throws Exception {
        assertEquals("<sk-explicit>|<sk-sp-plan>", loadCredentials(
                "export DASHSCOPE_EMBEDDING_API_KEY=sk-profile\n", "sk-cli",
                Map.of("DASHSCOPE_EMBEDDING_API_KEY", "sk-explicit", "DASHSCOPE_API_KEY", "sk-sp-plan")));
    }

    @Test void unsetDedicatedKeyUsesPaygCliWithoutChangingPlanKey() throws Exception {
        assertEquals("<sk-cli>|<sk-sp-plan>", loadCredentials("", "sk-cli",
                Map.of("DASHSCOPE_API_KEY", "sk-sp-plan")));
    }

    @Test void ordinaryGeneralKeyDoesNotImportCliCredentials() throws Exception {
        assertEquals("<unset>|<sk-general>", loadCredentials("", "sk-cli",
                Map.of("DASHSCOPE_API_KEY", "sk-general")));
    }

    @Test void planCliKeyIsNeverImportedAsEmbeddingKey() throws Exception {
        assertEquals("<unset>|<sk-sp-plan>", loadCredentials("", "sk-sp-cli",
                Map.of("DASHSCOPE_API_KEY", "sk-sp-plan")));
    }
}
