package com.writing.novel.agent;

import com.writing.novel.domain.ContinuityIssue;
import com.writing.novel.service.RepairArbiter;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * p11-q0-04: 闭环修复三分类仲裁（修复 W3-08 只实现"重写"1/3）。
 */
class ClosedLoopRepairClassificationTest {

    private final RepairArbiter arbiter = new RepairArbiter();

    private ContinuityIssue issue(ContinuityIssue.Type type) {
        return new ContinuityIssue("p", 1, type, ContinuityIssue.Severity.HIGH, "desc");
    }

    @Test
    void 人物类矛盾_未重写_判为重写当前章() {
        assertEquals(RepairArbiter.RepairAction.REWRITE,
                arbiter.classify(issue(ContinuityIssue.Type.CHARACTER), false));
    }

    @Test
    void 世界观类矛盾_判为真典supercede() {
        assertEquals(RepairArbiter.RepairAction.SUPERSEDE_CANON,
                arbiter.classify(issue(ContinuityIssue.Type.WORLDBUILDING), false));
    }

    @Test
    void 时间线矛盾_判为人工审查() {
        assertEquals(RepairArbiter.RepairAction.MANUAL_REVIEW,
                arbiter.classify(issue(ContinuityIssue.Type.TIMELINE), false));
    }

    @Test
    void 重写后仍存_升级为根源性人工审查() {
        // 重写一次后矛盾仍存 → 非简单正文错误 → 待人工
        assertEquals(RepairArbiter.RepairAction.MANUAL_REVIEW,
                arbiter.classify(issue(ContinuityIssue.Type.CHARACTER), true));
    }

    @Test
    void 情节类矛盾_判为重写当前章() {
        assertEquals(RepairArbiter.RepairAction.REWRITE,
                arbiter.classify(issue(ContinuityIssue.Type.PLOT), false));
    }
}
