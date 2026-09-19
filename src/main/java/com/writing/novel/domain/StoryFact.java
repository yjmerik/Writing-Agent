package com.writing.novel.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * p10-w2-01: 故事真典（Story Bible）的单条事实——可累积、可纠错、可检索的真相层。
 * <p>
 * 与已发布正文分离：承载"当前什么是真的"。被推翻时旧事实置 SUPERSEDED、新事实 ACTIVE 并
 * supersededBy 指向旧者，永不删除（审计链）。ContextAssembler/ContinuityChecker 按相关性检索
 * ACTIVE 事实，作为长程记忆（替代级联压缩的 book_summary）与修复闭环的纠错对象。
 */
public class StoryFact {

    /** 事实类型 */
    public enum FactType {
        CHARACTER_STATE,   // 角色当前状态（修为/目标/位置）——替代会漂移的 Character.ability/growthArc
        WORLD_RULE,        // 世界铁律（如时代背景、力量体系约束）
        TIMELINE_EVENT,    // 时间线事件
        RELATIONSHIP_STATE,// 角色间当前关系
        PLOT_COMMITMENT,   // 对读者做过的承诺（与 Foreshadowing 互补）
        OBJECT_STATE       // 物品状态（归属/位置）
    }

    /** 事实状态：ACTIVE 有效 / SUPERSEDED 被新事实取代 / RETIRED 归档 */
    public enum Status { ACTIVE, SUPERSEDED, RETIRED }

    /**
     * p32-06（体验报告 #4/P1）: 证据分级——CONFIRMED 已确认（人工补丁/播种/状态类收割），
     * UNVERIFIED 待核（机器收割的世界铁律：可能只是角色台词或推测，未确认前不得
     * 以铁律身份恒注入约束创作，也不得取代已确认铁律）。
     */
    public enum EvidenceGrade { CONFIRMED, UNVERIFIED }

    private String id;
    private String projectId;
    private FactType factType;
    private String subject;          // 主体（角色名/势力/物品），可关联 entity
    private String assertion;        // 规范陈述
    /** NCS-008: 谓词——属性的判别键（如 ability.read / location / 持有），null=旧数据 */
    private String predicate;
    /** NCS-008: 谓词的取值/宾语（如 false / 城门 / 已焚毁） */
    private String objectValue;
    /** NCS-008: 极性 positive/negative——同谓词相反极性是冲突候选，不是同义确认 */
    private String polarity;
    /** NCS-008: 生效区间起点（缺省=建立章） */
    private int validFromChapter;
    /** NCS-008: 生效区间终点（null=开放，尚未失效） */
    private Integer validToChapter;
    private int chapterEstablished;  // 建立章节
    private int sourceChapterVersion;// 溯源：建立时该章版本
    private int importance;          // 1-5，用于检索排序与衰减
    private Status status;
    /** p32-06: 证据分级，默认确认（兼容旧数据/播种/人工补丁） */
    private EvidenceGrade evidenceGrade = EvidenceGrade.CONFIRMED;
    private String supersededBy;     // 指向取代它的新事实 id（审计链）
    private int lastValidatedChapter;// 最近一次被本章确认的章节
    private Instant createdAt;
    private Instant updatedAt;

    public StoryFact(String projectId, FactType factType, String subject, String assertion, int chapterEstablished) {
        this.id = UUID.randomUUID().toString();
        this.projectId = projectId;
        this.factType = factType;
        this.subject = subject;
        this.assertion = assertion;
        this.chapterEstablished = chapterEstablished;
        this.validFromChapter = chapterEstablished;
        this.importance = 3;
        this.status = Status.ACTIVE;
        this.lastValidatedChapter = chapterEstablished;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    /** 用新事实取代本事实：本事实 SUPERSEDED，新事实 ACTIVE 并反向指向 */
    public void supersedeBy(StoryFact newer) {
        this.status = Status.SUPERSEDED;
        this.supersededBy = newer.id;
        this.updatedAt = Instant.now();
    }

    /** 本章再次确认该事实仍有效（刷新最近确认章 + 不改内容） */
    public void validateAt(int chapterIndex) {
        this.lastValidatedChapter = chapterIndex;
        this.updatedAt = Instant.now();
    }

    public void retire() {
        this.status = Status.RETIRED;
        this.updatedAt = Instant.now();
    }

    // --- getters/setters ---
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getProjectId() { return projectId; }
    public FactType getFactType() { return factType; }
    public void setFactType(FactType t) { this.factType = t; }
    public String getSubject() { return subject; }
    public void setSubject(String s) { this.subject = s; }
    public String getAssertion() { return assertion; }
    public void setAssertion(String a) { this.assertion = a; }
    public String getPredicate() { return predicate; }
    public void setPredicate(String p) { this.predicate = p == null || p.isBlank() ? null : p.trim(); }
    public String getObjectValue() { return objectValue; }
    public void setObjectValue(String o) { this.objectValue = o == null || o.isBlank() ? null : o.trim(); }
    public String getPolarity() { return polarity; }
    public void setPolarity(String p) { this.polarity = p == null || p.isBlank() ? null : p.trim().toLowerCase(); }
    public int getValidFromChapter() { return validFromChapter; }
    public void setValidFromChapter(int c) { this.validFromChapter = c; }
    public Integer getValidToChapter() { return validToChapter; }
    public void setValidToChapter(Integer c) { this.validToChapter = c; }
    public int getChapterEstablished() { return chapterEstablished; }
    public int getSourceChapterVersion() { return sourceChapterVersion; }
    public void setSourceChapterVersion(int v) { this.sourceChapterVersion = v; }
    public int getImportance() { return importance; }
    public void setImportance(int i) { this.importance = Math.max(1, Math.min(5, i)); }
    public Status getStatus() { return status; }
    public void setStatus(Status s) { this.status = s; }
    public EvidenceGrade getEvidenceGrade() { return evidenceGrade; }
    public void setEvidenceGrade(EvidenceGrade g) { this.evidenceGrade = g == null ? EvidenceGrade.CONFIRMED : g; }
    public String getSupersededBy() { return supersededBy; }
    public void setSupersededBy(String s) { this.supersededBy = s; }
    public int getLastValidatedChapter() { return lastValidatedChapter; }
    public void setLastValidatedChapter(int c) { this.lastValidatedChapter = c; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
