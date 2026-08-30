package com.writing.novel.orchestrator;

/**
 * p22-05: 生命周期策略包——Orchestrator 的全部后置可选依赖收敛为组合期显式值对象。
 *
 * <p>生产组合（OrchestratorConfig）必须给全并逐项 {@code requirePolicy} 校验，
 * 缺失策略 Spring 启动即失败并指名能力；{@code null} 包（{@link #none}）仅限测试
 * harness 的降级组合（对应能力安全跳过）。
 */
public record OrchestratorPolicies(
        com.writing.novel.spi.StoryCanon storyCanon,
        com.writing.novel.memory.RelevanceGateway relevanceGateway,
        com.writing.novel.service.RepairArbiter repairArbiter,
        com.writing.novel.service.ConceiveGate conceiveGate,
        com.writing.novel.service.PlanGate planGate,
        com.writing.novel.service.QualityCouncil qualityCouncil,
        com.writing.novel.service.Arbiter arbiter,
        com.writing.novel.agent.FactHarvesterAgent factHarvester,
        com.writing.novel.service.ArchiveCompactor archiveCompactor,
        com.writing.novel.telemetry.CostBudgetMonitor costBudgetMonitor,
        com.writing.novel.persistence.sqlite.PostProcessLedgerRepository postProcessLedger) {

    /** 测试降级组合：全部策略缺省（对应能力安全跳过） */
    public static OrchestratorPolicies none() {
        return new OrchestratorPolicies(null, null, null, null, null,
                null, null, null, null, null, null);
    }
}
