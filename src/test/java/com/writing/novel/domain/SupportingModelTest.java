package com.writing.novel.domain;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SupportingModelTest {

    @Test
    void 摘要覆盖一段章节范围() {
        Summary s = new Summary("proj-1", 1, 5,
                "第1-5章：主角拜入青云宗，通过灵根测试，与师兄结怨。");
        assertEquals(1, s.getFromChapter());
        assertEquals(5, s.getToChapter());
        assertTrue(s.covers(3));
        assertFalse(s.covers(6));
    }

    @Test
    void 风格基线记录关键词与样本() {
        StyleProfile sp = new StyleProfile("proj-1");
        sp.setTone("热血、紧凑");
        sp.addSampleChapter(1);
        assertEquals("热血、紧凑", sp.getTone());
        assertTrue(sp.getSampleChapterIndices().contains(1));
    }

    @Test
    void 连续性问题分严重级别() {
        ContinuityIssue issue = new ContinuityIssue("proj-1", 12,
                ContinuityIssue.Type.CHARACTER, ContinuityIssue.Severity.HIGH,
                "张三的佩剑名前后不一致：第12章叫霜寒，第3章叫断水。");
        assertEquals(ContinuityIssue.Severity.HIGH, issue.getSeverity());
        assertFalse(issue.isResolved());
        issue.resolve();
        assertTrue(issue.isResolved());
    }

    @Test
    void 检查点可被解决() {
        Checkpoint cp = new Checkpoint("proj-1", Checkpoint.Type.AFTER_INITIAL_SETUP,
                "设定产物待审核");
        assertEquals(Checkpoint.Status.PENDING, cp.getStatus());
        cp.approve();
        assertEquals(Checkpoint.Status.APPROVED, cp.getStatus());
    }
}
