package com.writing.novel.service;

import com.writing.novel.domain.KnowledgeEntry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * p11-q1-01: ConceiveGate WorldBuilder 门禁——力量体系/势力不重复/完整性校验。
 */
class ConceiveGateTest {

    private final ConceiveGate gate = new ConceiveGate();

    private KnowledgeEntry entry(String type, String name) {
        return new KnowledgeEntry("p", type, name, "内容");
    }

    @Test
    void 完整且含力量体系_通过() {
        var result = gate.checkWorldbuilding(List.of(
                entry("POWER_SYSTEM", "灵气体系"),
                entry("FACTION", "天剑宗"),
                entry("RULE", "修炼禁忌")), "玄幻");
        assertTrue(result.pass());
    }

    @Test
    void 缺少力量体系_不通过() {
        var result = gate.checkWorldbuilding(List.of(
                entry("FACTION", "天剑宗"),
                entry("RULE", "禁忌")), "玄幻");
        assertFalse(result.pass());
        assertTrue(result.feedback().contains("力量体系"));
    }

    @Test
    void 同类条目重名_不通过() {
        var result = gate.checkWorldbuilding(List.of(
                entry("POWER_SYSTEM", "灵气"),
                entry("FACTION", "天剑宗"),
                entry("FACTION", "天剑宗")), "玄幻");
        assertFalse(result.pass());
        assertTrue(result.issues().stream().anyMatch(i -> i.contains("重复")));
    }

    @Test
    void 条目为空_不通过() {
        var result = gate.checkWorldbuilding(List.of(), "玄幻");
        assertFalse(result.pass());
        assertTrue(result.feedback().contains("为空"));
    }

    @Test
    void 条目过少_不通过() {
        var result = gate.checkWorldbuilding(List.of(entry("POWER_SYSTEM", "灵气")), "玄幻");
        assertFalse(result.pass());
        assertTrue(result.issues().stream().anyMatch(i -> i.contains("过少")));
    }
}
