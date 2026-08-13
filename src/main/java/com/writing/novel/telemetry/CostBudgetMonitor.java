package com.writing.novel.telemetry;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * p10-w1-04: 按项目累计 LLM 估算成本，供写作循环做预算熔断。
 * <p>
 * ObservedLlmGateway 每次调用后按 taskType 模型 pricing 计价并 add(projectId, cost)；
 * WritingTaskExecutor 在 runWritingLoop 返回后检查 total(projectId) 是否超 WritingPreferences.costBudgetYuan，
 * 超限则置 PAUSED（诚实终态：非失败，是主动暂停，用户加预算可续写）。
 * projectId 经 EventContext.projectId() 归因（ThreadLocal，由 NovelOrchestrator 写作循环入口 set）。
 */
@Component
public class CostBudgetMonitor {

    private final Map<String, AtomicReference<Double>> totals = new ConcurrentHashMap<>();

    /** 累加一次调用成本到指定项目 */
    public void add(String projectId, double costCny) {
        if (projectId == null || costCny <= 0) return;
        totals.computeIfAbsent(projectId, k -> new AtomicReference<>(0.0))
                .accumulateAndGet(costCny, Double::sum);
    }

    /** 项目累计成本（元） */
    public double total(String projectId) {
        AtomicReference<Double> v = totals.get(projectId);
        return v == null ? 0.0 : v.get();
    }

    /** 是否超预算（budgetYuan<=0 表示不限制，永远 false） */
    public boolean isOverBudget(String projectId, double budgetYuan) {
        if (budgetYuan <= 0) return false;
        return total(projectId) >= budgetYuan;
    }

    /** 续写/重置用 */
    public void reset(String projectId) {
        totals.remove(projectId);
    }
}
