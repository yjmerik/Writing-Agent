package com.writing.novel.service;

import com.writing.novel.domain.AssertionSupport;
import com.writing.novel.persistence.sqlite.AssertionSupportRepository;
import com.writing.novel.telemetry.EventLog;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * NCS-053: 来源撤回的依赖传播。
 *
 * <ul>
 *   <li>AND 缺一失效：撤掉一条 AND 前提 → 该断言失效（级联）</li>
 *   <li>OR 余一保留：撤掉一条 OR 来源 → 还有其他 ACTIVE 来源则断言存活</li>
 *   <li>无外根循环不自证：撤回传播沿依赖图走，环上节点不因环自证存活</li>
 *   <li>传播至全部依赖者：失效沿支持图递归（访问集防重入）</li>
 * </ul>
 */
@Service
public class SupportPropagationService {

    private final AssertionSupportRepository supports;
    /** NCS-053: 外根存在性覆盖——在集合中的节点视为存在；不在集合的"游离节点"（无入边且无支持行）视为不存在 */
    private final java.util.Set<String> existingRoots;

    @org.springframework.beans.factory.annotation.Autowired
    public SupportPropagationService(AssertionSupportRepository supports) {
        this(supports, null);
    }

    /** 测试：显式登记外根存在性集合 */
    public SupportPropagationService(AssertionSupportRepository supports,
                                     java.util.Set<String> existingRoots) {
        this.supports = supports;
        this.existingRoots = existingRoots; // null=宽松（游离节点视为存在——生产真典图外根由事实表背书）
    }

    /** 撤回一条支持并传播；返回全部失效的断言 factId（撤回后全图重算，含级联） */
    public List<String> withdrawAndPropagate(String projectId, Long supportRowId) {
        var target = supports.findById(supportRowId).orElseThrow();
        supports.withdraw(supportRowId);
        // 传播=撤回后全图重算：收集撤回前存活∩依赖闭包，重判失效者
        Set<String> candidates = new LinkedHashSet<>();
        collectDependents(projectId, target.getFactId(), candidates);
        candidates.add(target.getFactId());
        Set<String> failed = new LinkedHashSet<>();
        for (String factId : candidates) {
            if (!aliveRecursive(projectId, factId, new java.util.HashSet<>())) {
                failed.add(factId);
            }
        }
        EventLog.emit("SUPPORT_PROPAGATED", java.util.Map.of(
                "projectId", projectId, "failedAssertions", failed.size()));
        return new ArrayList<>(failed);
    }

    /** 依赖闭包（沿 support_fact_id 反向边收集） */
    private void collectDependents(String projectId, String factId, Set<String> out) {
        for (AssertionSupport dependent : supports.findBySupportFactId(projectId, factId)) {
            if (out.add(dependent.getFactId())) {
                collectDependents(projectId, dependent.getFactId(), out);
            }
        }
    }

    /** 存活判定（环安全：in-progress 集断环——环上节点不得自证存活） */
    /** 断言在现行支持组下是否存活 */
    public boolean isAlive(String projectId, String factId) {
        return aliveRecursive(projectId, factId, new java.util.HashSet<>());
    }

    /** 外根存在性：有覆盖集时以集合为准；否则有支持行或被任何断言引用即存在 */
    private boolean rootExists(String projectId, String factId) {
        if (existingRoots != null) return existingRoots.contains(factId);
        return !supports.findByFact(projectId, factId).isEmpty();
    }

    private boolean aliveRecursive(String projectId, String factId, Set<String> inProgress) {
        if (!inProgress.add(factId)) return false; // 环上节点：不自证
        List<AssertionSupport> supportsOfFact = supports.findByFact(projectId, factId);
        // NCS-053: 有支持历史但全部失效 → 断言失效（不再走外根存在性）
        if (!supportsOfFact.isEmpty()) {
            boolean anyActive = supportsOfFact.stream()
                    .anyMatch(s -> s.getStatus() == AssertionSupport.Status.ACTIVE);
            if (!anyActive) {
                inProgress.remove(factId);
                return false;
            }
        } else if (!rootExists(projectId, factId)) {
            // 游离节点（无支持行=被支持断言，也非任何支持组的引用目标）按存在性覆盖判定
            inProgress.remove(factId);
            return false;
        }
        List<AssertionSupport> ands = new ArrayList<>();
        List<AssertionSupport> ors = new ArrayList<>();
        for (AssertionSupport s : supportsOfFact) {
            if (s.getStatus() != AssertionSupport.Status.ACTIVE) continue;
            if (s.getLink() == AssertionSupport.Link.AND_PREMISE) ands.add(s);
            else ors.add(s);
        }
        // NCS-053: AND 前提组与 OR 来源组是独立的支持通道——
        // AND 组非空且全部存活 → 存活；否则 OR 组任一存活 → 存活；两组皆不满足 → 失效
        boolean andSatisfied = ands.isEmpty()
                ? true
                : ands.stream().allMatch(a -> aliveRecursive(projectId, a.getSupportFactId(), inProgress));
        if (andSatisfied) {
            inProgress.remove(factId);
            return true;
        }
        boolean orSatisfied = ors.stream()
                .anyMatch(o -> aliveRecursive(projectId, o.getSupportFactId(), inProgress));
        inProgress.remove(factId);
        return orSatisfied;
    }
}
