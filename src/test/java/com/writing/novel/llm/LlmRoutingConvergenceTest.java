package com.writing.novel.llm;

import com.writing.novel.llm.config.LlmProperties;
import com.writing.novel.llm.config.ModelConfig;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * NCS-108: 路由语义收敛——
 * 1) application.yml 为 providers/routing 唯一形态（llm.models 已移除，全部任务显式路由）；
 * 2) LlmProperties 路由行为：按任务取 provider 参数档（temp/max-tokens/thinking 等价迁移）；
 * 3) legacy llm.models 解析保留（isLegacyRouting 判定，供弃用告警与兼容路径）。
 */
class LlmRoutingConvergenceTest {

    @Test
    @SuppressWarnings("unchecked")
    void applicationYml_为providers唯一形态() {
        Map<String, Object> root = new Yaml().load(getClass()
                .getResourceAsStream("/application.yml"));
        Map<String, Object> llm = (Map<String, Object>) root.get("llm");
        assertNotNull(llm);
        assertFalse(llm.containsKey("models"), "llm.models 已弃用，yml 不得再使用");
        assertFalse(llm.containsKey("default-model"), "default-model 属 legacy 形态");

        Map<String, Object> providers = (Map<String, Object>) llm.get("providers");
        Map<String, Object> routing = (Map<String, Object>) llm.get("routing");
        assertNotNull(providers);
        assertFalse(providers.isEmpty());
        assertNotNull(routing);

        Map<String, Object> tasks = (Map<String, Object>) routing.get("tasks");
        // 全部任务类型显式路由（无隐性回退语义）
        for (LlmTaskType task : LlmTaskType.values()) {
            List<String> ids = (List<String>) tasks.get(task.name());
            assertNotNull(ids, "任务 " + task + " 必须显式路由");
            for (String id : ids) {
                assertTrue(providers.containsKey(id), "路由引用的 provider 必须存在: " + id);
            }
        }
    }

    @Test
    void 路由行为_各任务取对应参数档() {
        LlmProperties props = new LlmProperties();
        Map<String, ModelConfig> providers = new java.util.HashMap<>();
        providers.put("write", model(0.8, 20000));
        providers.put("check", model(0.3, 4096));
        providers.put("extract", model(0.1, 16000));
        providers.get("extract").setThinkingEnabled(false);
        props.setProviders(providers);
        LlmProperties.Routing routing = new LlmProperties.Routing();
        routing.setDefaultProviders(List.of("check"));
        routing.setTasks(Map.of(
                "CHAPTER_WRITING", List.of("write"),
                "CONTINUITY_CHECK", List.of("check"),
                "EXTRACTION", List.of("extract")));
        props.setRouting(routing);

        assertFalse(props.isLegacyRouting(), "providers/routing 形态不是 legacy");
        assertEquals(0.8, props.forTaskType("CHAPTER_WRITING").getTemperature(), 1e-9);
        assertEquals(20000, props.forTaskType("CHAPTER_WRITING").getMaxTokens());
        assertFalse(props.forTaskType("EXTRACTION").isThinkingEnabled(), "提取关思考语义保持");
        // 未显式路由的任务回退 defaultProviders
        assertEquals(0.3, props.forTaskType("GENERAL").getTemperature(), 1e-9);
    }

    @Test
    void legacy配置_解析保留并可判定() {
        LlmProperties legacy = new LlmProperties();
        legacy.getModels().put("GENERAL", model(0.3, 4096));
        assertTrue(legacy.isLegacyRouting(), "仅 llm.models 时为 legacy 形态");
        // legacy 路径回退 defaultModel 语义不变
        assertEquals(0.3, legacy.forTaskType("GENERAL").getTemperature(), 1e-9);
        assertSame(legacy.getDefaultModel(), legacy.forTaskType("PLOTTING"), "未配置任务回退 defaultModel");
    }

    private static ModelConfig model(double temp, int maxTokens) {
        ModelConfig cfg = new ModelConfig();
        cfg.setBaseUrl("http://localhost:9");
        cfg.setApiKey("local");
        cfg.setModel("m");
        cfg.setTemperature(temp);
        cfg.setMaxTokens(maxTokens);
        cfg.setContextWindow(64000);
        return cfg;
    }
}
