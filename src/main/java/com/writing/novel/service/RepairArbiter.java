package com.writing.novel.service;

import com.writing.novel.domain.ContinuityIssue;
import org.springframework.stereotype.Component;

/**
 * p11-q0-04: 闭环修复三分类仲裁器。
 * <p>
 * 对检测到的 HIGH 一致性矛盾分类修复动作，修复 W3-08 只实现"重写"1/3 的缺口：
 * <ul>
 *   <li>{@link RepairAction#REWRITE} —— 确属本章正文错误（笔误/能力越界）→ 重写当前章</li>
 *   <li>{@link RepairAction#SUPERSEDE_CANON} —— 真典需演进（合法剧情变化，如世界观拓展）
 *       → 纠正真典让后续章节遵从，不改正文</li>
 *   <li>{@link RepairAction#MANUAL_REVIEW} —— 早期根源/跨章矛盾（如时间线），单章重写无法修复
 *       → 标 {@code Chapter.NEEDS_MANUAL_REVIEW} 待人工</li>
 * </ul>
 */
@Component
public class RepairArbiter {

    public enum RepairAction { REWRITE, SUPERSEDE_CANON, MANUAL_REVIEW }

    /**
     * @param issue            待分类的 HIGH 一致性问题
     * @param alreadyRewritten 本章是否已尝试过重写（重写后矛盾仍存 → 升级为根源性矛盾）
     */
    public RepairAction classify(ContinuityIssue issue, boolean alreadyRewritten) {
        if (alreadyRewritten) {
            // 重写一次后矛盾仍存：非简单正文错误 → 根源性矛盾，待人工
            return RepairAction.MANUAL_REVIEW;
        }
        if (issue.getType() == ContinuityIssue.Type.TIMELINE) {
            // 时间线矛盾多为跨章根源（年龄倒退/事件先后颠倒），单章重写难修复
            return RepairAction.MANUAL_REVIEW;
        }
        if (issue.getType() == ContinuityIssue.Type.WORLDBUILDING) {
            // 世界观类矛盾可能是合法剧情演进（体系拓展）→ 真典 supercede 让后续遵从
            return RepairAction.SUPERSEDE_CANON;
        }
        // CHARACTER/FORESHADOWING/PLOT 等：本章正文错误 → 重写
        return RepairAction.REWRITE;
    }
}
