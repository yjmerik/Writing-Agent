package com.writing.novel.llm;

import com.writing.novel.llm.config.LlmProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class LlmProviderConfigurationTest {
    static Map<String, Object> config() {
        Map<String, Object> m = new HashMap<>();
        m.put("llm.default-model.base-url", "http://localhost/legacy");
        m.put("llm.default-model.model", "legacy");
        for (String id : List.of("a", "b")) {
            m.put("llm.providers."+id+".base-url", "https://example.org/v1");
            m.put("llm.providers."+id+".api-key", "test-key-"+id);
            m.put("llm.providers."+id+".model", "model-"+id);
            m.put("llm.providers."+id+".context-window", 64000);
        }
        m.put("llm.routing.default-providers[0]", "a");
        m.put("llm.routing.default-providers[1]", "b");
        return m;
    }
    static LlmProperties bind(Map<String, Object> m) {
        return new Binder(new MapConfigurationPropertySource(m)).bind("llm", Bindable.of(LlmProperties.class)).get();
    }
    @Test void namedRoutesSupplyPrimaryToContextBudgetAndTaskOverrides() {
        Map<String,Object> m=config();
        m.put("llm.routing.tasks.CHAPTER_WRITING[0]", "b");
        var p=bind(m); p.validateKeys();
        assertEquals("model-a", p.forTaskType("GENERAL").getModel());
        assertEquals("model-b", p.forTaskType("CHAPTER_WRITING").getModel());
        assertEquals(64000, p.forTaskType("CHAPTER_WRITING").getContextWindow());
    }
    @Test void rejectsUnknownDuplicateAndEmptyTaskRoutes() {
        for (String value : List.of("unknown", "a", "")) {
            var m=config(); m.put("llm.routing.default-providers[1]",value);
            assertThrows(IllegalStateException.class, () -> bind(m).validateKeys(), value);
        }
        var m=config(); m.put("llm.routing.tasks.CHAPTER_WRITING", "");
        assertThrows(IllegalStateException.class, () -> bind(m).validateKeys());
        m.clear(); m.putAll(config()); m.put("llm.routing.tasks.TYPO[0]", "a");
        var props=bind(m); assertThrows(IllegalStateException.class,props::validateKeys);
    }
    @Test void validatesOnlyActiveKeysButRetainsLegacyTasksWhenNoDefaultRoute() {
        var m=config(); m.put("llm.default-model.base-url","https://legacy.invalid/v1");
        m.put("llm.providers.unused.base-url","https://unused.invalid/v1");
        assertDoesNotThrow(() -> bind(m).validateKeys());
        m.remove("llm.routing.default-providers[0]"); m.remove("llm.routing.default-providers[1]");
        m.put("llm.routing.tasks.GENERAL[0]","a");
        assertThrows(IllegalStateException.class, () -> bind(m).validateKeys());
    }
    @Test void validatesActiveProviderParametersWithoutExposingKeys() {
        for (var invalid : Map.of("base-url","ftp://example.org", "model","", "api-key","", "context-window","0", "request-timeout-seconds","0").entrySet()) {
            var m=config();m.put("llm.providers.b."+invalid.getKey(),invalid.getValue());
            var e=assertThrows(IllegalStateException.class, () -> bind(m).validateKeys(),invalid.getKey());
            assertFalse(e.getMessage().contains("test-key"));
        }
        var m=config();m.put("llm.providers.b.context-window",32000);
        assertThrows(IllegalStateException.class, () -> bind(m).validateKeys());
        m.clear();m.putAll(config());m.put("llm.fallback.cooldown-seconds",0);
        var p=bind(m);assertThrows(IllegalStateException.class,p::validateKeys);
    }
    @Test void replayDoesNotRequireSecrets() {
        var m=config();m.put("llm.providers.a.api-key","");m.put("llm.providers.b.api-key","");
        var p=bind(m);p.setReplayMode(true);assertDoesNotThrow(p::validateKeys);
    }
    @Test void fallbackMustReserveAtLeastAsMuchInputSpaceAsPrimary() {
        var m=config();m.put("llm.providers.a.max-tokens",8000);m.put("llm.providers.b.max-tokens",32000);
        var p=bind(m);assertThrows(IllegalStateException.class,p::validateKeys);
        m.put("llm.providers.b.context-window",128000);
        assertDoesNotThrow(()->bind(m).validateKeys());
    }

}
