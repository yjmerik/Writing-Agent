package com.writing.novel.service;

import com.writing.novel.domain.ContinuityIssue;
import com.writing.novel.service.QualityCouncil.CouncilReport;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * p11-q2-02: Arbiter 仲裁器——基于委员会报告决定 REWRITE/BLOCK/PASS。
 */
class ArbiterTest {

    private final Arbiter arbiter = new Arbiter();
    private final QualityCouncil council = new QualityCouncil();

    private CouncilReport report(boolean hasHigh, int readerOverall) {
        var issues = hasHigh ? List.of(new ContinuityIssue("p", 1,
                ContinuityIssue.Type.CHARACTER, ContinuityIssue.Severity.HIGH, "越界")) : List.<ContinuityIssue>of();
        return new CouncilReport(issues, hasHigh, Map.of("readerOverall", readerOverall), "feedback");
    }

    @Test
    void 有HIGH且预算允许_未重写_判REWRITE() {
        var a = arbiter.arbitrate(report(true, 8), 1, false);
        assertEquals(Arbiter.Action.REWRITE, a.action());
        assertEquals("feedback", a.correctionBrief());
    }

    @Test
    void 重写后仍HIGH_判BLOCK() {
        var a = arbiter.arbitrate(report(true, 8), 1, true);
        assertEquals(Arbiter.Action.BLOCK, a.action());
    }

    @Test
    void 无问题高评分_判PASS() {
        var a = arbiter.arbitrate(report(false, 9), 1, false);
        assertEquals(Arbiter.Action.PASS, a.action());
    }

    @Test
    void 预算耗尽_判PASS不再重写() {
        var a = arbiter.arbitrate(report(true, 8), 0, false);
        assertEquals(Arbiter.Action.PASS, a.action());
    }

    @Test
    void 读者低分无HIGH_判REWRITE() {
        var a = arbiter.arbitrate(report(false, 5), 1, false);
        assertEquals(Arbiter.Action.REWRITE, a.action());
    }

    @Test
    void council与arbiter协同_汇总后仲裁() {
        var report = council.collect(
                List.of(new ContinuityIssue("p", 1, ContinuityIssue.Type.WORLDBUILDING,
                        ContinuityIssue.Severity.HIGH, "朝代错")), List.of(), 6, "改", 70);
        var a = arbiter.arbitrate(report, 1, false);
        assertEquals(Arbiter.Action.REWRITE, a.action());
        assertTrue(a.correctionBrief().contains("朝代错"));
    }
}
