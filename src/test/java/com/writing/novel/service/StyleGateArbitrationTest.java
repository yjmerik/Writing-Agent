package com.writing.novel.service;

import com.writing.novel.service.QualityCouncil.CouncilReport;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * NCS-100: 风格评分进质量仲裁——
 * 1) Arbiter：style<60 触发预算内 REWRITE（此前 styleScore 生产恒 null，20 分也不重写）；
 * 2) QualityCouncil：低分把风格约束写进纠偏 brief；
 * 3) 评分进 scores 供下游可观测。
 */
class StyleGateArbitrationTest {

    private final QualityCouncil council = new QualityCouncil();
    private final Arbiter arbiter = new Arbiter();

    @Test
    void 风格低分_无HIGH也能触发预算内重写() {
        CouncilReport report = council.collect(List.of(), List.of(), 8, null, 40);
        var decision = arbiter.arbitrate(report, 1, false);
        assertEquals(Arbiter.Action.REWRITE, decision.action(), "style=40 必须触发重写（旧逻辑恒 PASS）");
        assertTrue(decision.correctionBrief().contains("风格漂移"), "纠偏 brief 应含风格约束");
        assertTrue(decision.correctionBrief().contains("40/100"));
    }

    @Test
    void 风格及格_无其它问题则PASS() {
        CouncilReport ok = council.collect(List.of(), List.of(), 8, null, 90);
        assertEquals(Arbiter.Action.PASS, arbiter.arbitrate(ok, 1, false).action());
        // 未评分（null）行为与旧版一致
        CouncilReport noScore = council.collect(List.of(), List.of(), 8, null, null);
        assertEquals(Arbiter.Action.PASS, arbiter.arbitrate(noScore, 1, false).action());
        assertFalse(noScore.scores().containsKey("style"));
    }

    @Test
    void 重写后风格仍低_升级BLOCK交人工() {
        CouncilReport stillLow = council.collect(List.of(), List.of(), 8, null, 40);
        assertEquals(Arbiter.Action.BLOCK, arbiter.arbitrate(stillLow, 0, true).action(),
                "预算耗尽且风格仍低 → 人工屏障");
    }

    @Test
    void 低分风格进入scores_供事件与报告消费() {
        CouncilReport report = council.collect(List.of(), List.of(), 7, null, 55);
        assertEquals(55, report.scores().get("style"));
        assertTrue(report.combinedFeedback().contains("回归基线风格"));
    }
}
