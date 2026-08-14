package com.writing.novel.service;

import com.writing.novel.domain.ChapterSynopsis;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * p11-q1-04: PlanGate 章节梗概预检——写正文前校验梗概完整性。
 */
class PlanGateTest {

    private final PlanGate gate = new PlanGate();

    @Test
    void 完整梗概通过() {
        var s = new ChapterSynopsis("p", 1, "开端", "本章主角入门。");
        s.setBeats("节拍1；节拍2");
        assertTrue(gate.checkSynopsis(s).pass());
    }

    @Test
    void 缺beats不通过() {
        var s = new ChapterSynopsis("p", 1, "开端", "梗概");
        assertFalse(gate.checkSynopsis(s).pass());
        assertTrue(gate.checkSynopsis(s).feedback().contains("节拍"));
    }

    @Test
    void 空梗概或null不通过() {
        assertFalse(gate.checkSynopsis(null).pass());
        var empty = new ChapterSynopsis("p", 1, "", "");
        assertFalse(gate.checkSynopsis(empty).pass());
    }
}
