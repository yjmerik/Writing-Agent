package com.writing.novel.spi;

import com.writing.novel.domain.StoryFact;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * p22-01: StoryCanon 契约——所有实现（SQLite 适配器 / 内存适配器）必须行为一致：
 * 保护窗口、ACTIVE 唯一性、审计链、幂等播种。子类只提供适配器实例。
 */
abstract class StoryCanonContract {

    /** 每个测试方法调用一次，返回全新适配器实例 */
    abstract StoryCanon create();

    static final String PID = "proj-contract";

    StoryFact machineFact(String subject, String assertion, int chapter) {
        return new StoryFact(PID, StoryFact.FactType.CHARACTER_STATE, subject, assertion, chapter);
    }

    StoryFact humanFact(String subject, String assertion, int chapter) {
        StoryFact f = new StoryFact(PID, StoryFact.FactType.CHARACTER_STATE, subject,
                StoryCanon.HUMAN_PATCH_PREFIX + assertion, chapter);
        f.setLastValidatedChapter(chapter);
        return f;
    }

    @org.junit.jupiter.api.Test
    void 新主体收割为ADDED且可召回() {
        var canon = create();
        var r = canon.harvest(machineFact("季衡", "筑基期", 1));
        assertEquals(StoryCanon.Outcome.ADDED, r.outcome());
        assertEquals(1, canon.recallBySubject(PID, "季衡").size());
        assertTrue(canon.recall(PID).stream().anyMatch(f -> f.getSubject().equals("季衡")));
    }

    @org.junit.jupiter.api.Test
    void 一致断言收割为VALIDATED并刷新确认章() {
        var canon = create();
        canon.harvest(machineFact("季衡", "筑基期", 1));
        var r = canon.harvest(machineFact("季衡", "筑基期（初期）", 4));
        assertEquals(StoryCanon.Outcome.VALIDATED, r.outcome());
        assertEquals(4, r.fact().getLastValidatedChapter());
        assertEquals(1, canon.recallBySubject(PID, "季衡").size(), "VALIDATED 不得新增 ACTIVE");
    }

    @org.junit.jupiter.api.Test
    void 保护窗口内冲突机器事实被SKIPPED且不落库() {
        var canon = create();
        StoryFact human = humanFact("季衡", "命格：不灭玄体", 2);
        canon.saveIfAbsent(human);
        var r = canon.harvest(machineFact("季衡", "命格：凡体", 4)); // 4-2=2 ≤5
        assertEquals(StoryCanon.Outcome.SKIPPED_PROTECTED, r.outcome());
        assertFalse(r.written());
        assertNull(r.fact());
        List<StoryFact> active = canon.recallBySubject(PID, "季衡");
        assertEquals(1, active.size(), "机器事实不得与人工补丁并存");
        assertEquals(human.getId(), active.get(0).getId());
    }

    @org.junit.jupiter.api.Test
    void 保护窗口外冲突取代并保留审计链() {
        var canon = create();
        StoryFact human = humanFact("季衡", "命格：不灭玄体", 2);
        canon.saveIfAbsent(human);
        var r = canon.harvest(machineFact("季衡", "命格：凡体", 8)); // 8-2=6 >5
        assertEquals(StoryCanon.Outcome.SUPERSEDED, r.outcome());
        StoryFact old = canon.findById(human.getId()).orElseThrow();
        assertEquals(StoryFact.Status.SUPERSEDED, old.getStatus());
        assertEquals(r.fact().getId(), old.getSupersededBy(), "审计链必须指向新事实");
        assertEquals(1, canon.recallBySubject(PID, "季衡").size());
    }

    @org.junit.jupiter.api.Test
    void 人工补丁重复写入保持ACTIVE唯一且前缀幂等() {
        var canon = create();
        var first = canon.applyHumanPatch(PID, StoryFact.FactType.CHARACTER_STATE,
                "季衡", "能力：筑基", 3, null);
        assertTrue(first.getAssertion().startsWith(StoryCanon.HUMAN_PATCH_PREFIX));
        assertEquals(5, first.getImportance(), "importance=null 时取 5");

        var second = canon.applyHumanPatch(PID, StoryFact.FactType.CHARACTER_STATE,
                "季衡", "能力：金丹", 5, 4);
        assertEquals(4, second.getImportance());
        assertTrue(second.getAssertion().startsWith(StoryCanon.HUMAN_PATCH_PREFIX),
                "无前缀断言自动补前缀");

        List<StoryFact> active = canon.recallBySubject(PID, "季衡").stream()
                .filter(f -> f.getFactType() == StoryFact.FactType.CHARACTER_STATE)
                .toList();
        assertEquals(1, active.size(), "重复人工编辑必须恰好一条 ACTIVE");
        assertEquals(second.getId(), active.get(0).getId());
        assertEquals(StoryFact.Status.SUPERSEDED,
                canon.findById(first.getId()).orElseThrow().getStatus(),
                "旧人工补丁保留审计");
        assertEquals(second.getId(), canon.findById(first.getId()).orElseThrow().getSupersededBy());
    }

    @org.junit.jupiter.api.Test
    void saveIfAbsent幂等且不覆盖演进() {
        var canon = create();
        StoryFact seed = machineFact("季衡", "初始能力：剑修", 1);
        assertTrue(canon.saveIfAbsent(seed));
        assertFalse(canon.saveIfAbsent(machineFact("季衡", "另一起始描述", 2)),
                "同 type+subject 已有 ACTIVE 时播种必须跳过");
        assertEquals(seed.getId(), canon.recallBySubject(PID, "季衡").get(0).getId(),
                "播种不得覆盖已有事实");
    }

    @org.junit.jupiter.api.Test
    void retire置RETIRED并退出ACTIVE集() {
        var canon = create();
        var f = canon.applyHumanPatch(PID, StoryFact.FactType.WORLD_RULE,
                "境界体系", "九境三阶", 1, null);
        var retired = canon.retire(PID, f.getId());
        assertEquals(StoryFact.Status.RETIRED, retired.getStatus());
        assertTrue(canon.recallBySubject(PID, "境界体系").isEmpty());
        assertTrue(canon.findAll(PID).stream().anyMatch(x -> x.getId().equals(f.getId())),
                "归档后审计仍可见");
    }

    @org.junit.jupiter.api.Test
    void 归属不符的retire与不存在事实报错() {
        var canon = create();
        var f = canon.applyHumanPatch("other-project", StoryFact.FactType.WORLD_RULE,
                "subject", "断言", 1, null);
        assertThrows(IllegalArgumentException.class, () -> canon.retire(PID, f.getId()));
        assertThrows(IllegalArgumentException.class, () -> canon.retire(PID, "no-such-id"));
    }
}
