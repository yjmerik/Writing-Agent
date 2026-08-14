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

    @Test
    void p11_q1_02_人物门禁_主角缺失或无能力不通过() {
        assertFalse(gate.checkCharacters(List.of()).pass(), "空人物不通过");
        var noAbility = new com.writing.novel.domain.Character("p", "李四", "主角");
        assertFalse(gate.checkCharacters(List.of(noAbility)).pass(), "主角无能力不通过");
        var ok = new com.writing.novel.domain.Character("p", "季衡", "主角");
        ok.setAbility("剑修");
        assertTrue(gate.checkCharacters(List.of(ok)).pass(), "完整主角通过");
    }

    @Test
    void p11_q1_02_人物门禁_重名不通过() {
        var a = new com.writing.novel.domain.Character("p", "季衡", "主角");
        a.setAbility("剑修");
        var b = new com.writing.novel.domain.Character("p", "季衡", "配角");
        assertFalse(gate.checkCharacters(List.of(a, b)).pass());
    }

    @Test
    void p11_q1_02_大纲门禁_总纲分卷缺失不通过() {
        assertFalse(gate.checkOutline(null).pass());
        assertFalse(gate.checkOutline(new com.writing.novel.domain.Outline("p", "")).pass());
    }
}
