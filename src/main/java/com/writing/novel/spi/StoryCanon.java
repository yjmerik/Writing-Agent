package com.writing.novel.spi;

import com.writing.novel.domain.StoryFact;

import java.util.List;
import java.util.Optional;

/**
 * p22-01: 故事真典端口——真典的全部读写语义（HUMAN_PATCH 保护窗口、ACTIVE 唯一性、
 * 审计链）都藏在实现内部，业务层（agent/memory/orchestrator/service）只面向此接口，
 * 不依赖具体持久化（persistence.sqlite.StoryFactRepository）。
 *
 * <p>语义约定（契约测试 StoryCanonContractTest 对所有实现生效）：
 * <ul>
 *   <li>{@link #harvest}：自动事实合并——新主体 ADDED；一致（互相包含）VALIDATED；
 *       冲突且旧者为保护窗口内人工补丁 → SKIPPED_PROTECTED（机器断言丢弃，不落库）；
 *       其余冲突 SUPERSEDED（旧 SUPERSEDED + supersededBy 审计链）</li>
 *   <li>{@link #applyHumanPatch}：人工写入总是取代同 project/type/subject 的 ACTIVE 事实
 *       （保护窗口只约束自动路径），保证恰好一条 ACTIVE</li>
 *   <li>{@link #saveIfAbsent}：幂等播种——同 type+subject 已有 ACTIVE 则不写</li>
 * </ul>
 */
public interface StoryCanon {

    /** 人工补丁断言前缀（审计与保护判定依据） */
    String HUMAN_PATCH_PREFIX = "【HUMAN_PATCH】";

    /** 自动事实不得取代人工补丁的保护窗口（章数） */
    int HUMAN_PATCH_PROTECT_CHAPTERS = 5;

    /** 自动合并结果 */
    enum Outcome { ADDED, VALIDATED, SUPERSEDED, SKIPPED_PROTECTED }

    /** 写入结果：outcome + 关键事实（SKIPPED_PROTECTED 时 fact 为 null） */
    record WriteResult(Outcome outcome, StoryFact fact) {
        public boolean written() { return outcome != Outcome.SKIPPED_PROTECTED; }
    }

    /** 全部 ACTIVE 事实（按 importance 降序）——检索消费 */
    List<StoryFact> recall(String projectId);

    /** 按主体查 ACTIVE 事实 */
    List<StoryFact> recallBySubject(String projectId, String subject);

    /** 全部事实（含 SUPERSEDED/RETIRED——审计/诊断） */
    List<StoryFact> findAll(String projectId);

    Optional<StoryFact> findById(String factId);

    /**
     * 自动事实合并（收割/弧光演进共用）。machineFact 需带 projectId/type/subject/
     * assertion/chapterEstablished（lastValidatedChapter 取同值）。
     */
    WriteResult harvest(StoryFact machineFact);

    /**
     * 人工补丁写入——断言自动加 {@link #HUMAN_PATCH_PREFIX} 前缀（若未带），
     * 并取代同 project/type/subject 的旧 ACTIVE 事实（ACTIVE 唯一性）。
     * @param importance null 时取 5
     */
    StoryFact applyHumanPatch(String projectId, StoryFact.FactType type, String subject,
                              String assertion, int chapter, Integer importance);

    /** 幂等播种——同 project/type+subject 已有 ACTIVE 则 false（不覆盖演进） */
    boolean saveIfAbsent(StoryFact fact);

    /** 刷新确认章（事实仍有效，lastValidatedChapter=chapter 并落库） */
    void validate(StoryFact fact, int chapter);

    /**
     * 定向取代——调用方已选定旧事实（如 ArcUpdater 的「成长弧：」分面匹配），
     * 实现保证审计链（old → SUPERSEDED + supersededBy 指向 newer）。
     * 注意：绕过保护窗口——只用于调用方已自行裁决的分面策略，通用冲突请走 {@link #harvest}。
     */
    StoryFact supersedeFact(StoryFact oldFact, StoryFact newFact);

    /** 归档（RETIRED，审计保留） */
    StoryFact retire(String projectId, String factId);

    /** 事实是否处于人工补丁保护窗口内（策略随端口走，实现共享） */
    default boolean isProtectedHumanPatch(StoryFact fact, int currentChapter) {
        if (fact == null || fact.getAssertion() == null) return false;
        if (!fact.getAssertion().startsWith(HUMAN_PATCH_PREFIX)) return false;
        return currentChapter - fact.getLastValidatedChapter() <= HUMAN_PATCH_PROTECT_CHAPTERS;
    }
}
