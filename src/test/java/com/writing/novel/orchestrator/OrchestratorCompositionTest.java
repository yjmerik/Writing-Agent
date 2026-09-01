package com.writing.novel.orchestrator;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * p22-05: 显式生命周期策略组合。
 *
 * - 生产组合（OrchestratorConfig）经 requirePolicy 逐项校验：缺失能力启动即失败并指名
 * - 后置可选 setter 已移除——策略只能经构造期 OrchestratorPolicies 注入
 * - 测试降级组合（OrchestratorPolicies.none()/null）保持空安全跳过语义
 */
class OrchestratorCompositionTest {

    @Test
    void 缺失必需策略时指名能力并失败() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> OrchestratorConfig.requirePolicy(null, "StoryCanon"));
        assertTrue(ex.getMessage().contains("StoryCanon"), "错误信息必须指名缺失能力: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("p22-05"));
    }

    @Test
    void 非空策略原样返回() {
        var canon = new com.writing.novel.persistence.sqlite.SqliteStoryCanon(null);
        assertSame(canon, OrchestratorConfig.requirePolicy(canon, "StoryCanon"));
    }

    @Test
    void 降级策略包全部为空且None工厂可用() {
        OrchestratorPolicies none = OrchestratorPolicies.none();
        for (int i = 0; i < 11; i++) {
            assertTrue(java.util.stream.Stream.of(
                    none.storyCanon(), none.relevanceGateway(), none.repairArbiter(),
                    none.conceiveGate(), none.planGate(), none.qualityCouncil(),
                    none.arbiter(), none.factHarvester(), none.archiveCompactor(),
                    none.costBudgetMonitor(), none.postProcessLedger())
                    .allMatch(java.util.Objects::isNull), "none() 的全部策略应为 null");
        }
        // 组合记录的组件数必须保持 11——新增策略需同步 none()
        assertEquals(11, OrchestratorPolicies.class.getRecordComponents().length);
    }

    @Test
    void 编排器不再暴露后置可选setter() {
        for (var m : NovelOrchestrator.class.getMethods()) {
            String n = m.getName();
            for (String banned : new String[]{"setStoryCanon", "setRelevanceGateway", "setRepairArbiter",
                    "setConceiveGate", "setPlanGate", "setQualityCouncil", "setArbiter",
                    "setFactHarvester", "setArchiveCompactor", "setCostBudgetMonitor",
                    "setPostProcessLedger"}) {
                assertNotEquals(banned, n, "后置可选 setter 必须移除: " + banned);
            }
        }
    }
}
