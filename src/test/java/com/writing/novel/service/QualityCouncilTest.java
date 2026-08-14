package com.writing.novel.service;

import com.writing.novel.domain.ContinuityIssue;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * p11-q2-01: QualityCouncil 评审汇总——四检测器报告合并为 CouncilReport。
 */
class QualityCouncilTest {

    private final QualityCouncil council = new QualityCouncil();

    @Test
    void 汇总问题列表并判定hasHigh() {
        var high = new ContinuityIssue("p", 1, ContinuityIssue.Type.CHARACTER,
                ContinuityIssue.Severity.HIGH, "能力越界");
        var med = new ContinuityIssue("p", 1, ContinuityIssue.Type.PLOT,
                ContinuityIssue.Severity.MEDIUM, "节奏");
        var report = council.collect(List.of(high), List.of(med), 8, null, 70);
        assertEquals(2, report.issues().size());
        assertTrue(report.hasHigh());
    }

    @Test
    void 无HIGH且评分高时hasHigh为false() {
        var report = council.collect(List.of(), List.of(), 9, null, 80);
        assertFalse(report.hasHigh());
        assertTrue(report.combinedFeedback().isEmpty());
    }

    @Test
    void 读者低分建议进入合并纠偏指令() {
        var report = council.collect(List.of(), List.of(), 5, "增加冲突张力", 60);
        assertTrue(report.combinedFeedback().contains("增加冲突张力"));
        assertEquals(5, report.scores().get("readerOverall"));
    }

    @Test
    void HIGH问题描述进入纠偏指令() {
        var high = new ContinuityIssue("p", 1, ContinuityIssue.Type.WORLDBUILDING,
                ContinuityIssue.Severity.HIGH, "朝代矛盾");
        var report = council.collect(List.of(high), List.of(), 7, null, null);
        assertTrue(report.combinedFeedback().contains("朝代矛盾"));
        assertTrue(report.combinedFeedback().contains("必须修正"));
    }
}
