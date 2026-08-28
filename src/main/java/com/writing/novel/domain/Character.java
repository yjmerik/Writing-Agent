package com.writing.novel.domain;

import java.time.Instant;
import java.util.UUID;

/** 人物卡：一个角色的核心设定 */
public class Character {

    /** p19-01: 角色存续状态——死亡/退场后不再被调度出场，注入链标注防止"死人复活" */
    public enum Status { ACTIVE, DECEASED, DEPARTED }

    private String id;
    private String projectId;
    private String name;
    private String role;          // 主角/配角/反派 等
    private String personality;
    private String ability;       // 能力/境界
    private String motivation;    // 动机
    private String appearance;    // 外貌
    private String relationships; // 人物关系（自然语言或结构化）
    private String growthArc;     // 成长弧
    private String speakingStyle;  // 说话风格："引经据典，善用兵法典故，语气沉稳"
    private String knowledgeDomains; // 知识领域 JSON：["兵法","古籍","阵法","星象"]

    // p10-a1: 对手智谋建模（题材无关——武侠是招式反制，言情是情感防线，科幻是技术反制）
    private Integer strategyLevel;     // 智谋层级 1-5（1=易对付，5=极难对付）
    private String countermeasures;    // 反制能力：面对主角策略时的典型反制手段
    private String independentWill;    // 独立判断原则：在什么条件下妥协/绝不退让
    private String blindSpot;          // 认知盲区：主角可利用但需合理铺垫

    // p10-a2: 角色弧光调度
    private String arcStage;           // 弧光当前阶段（如"蛰伏→觉醒→考验→蜕变→完成"）
    private Integer lastSeenChapter;   // 最近一次出场章节号

    // p19-01: 存续状态（null 视为 ACTIVE，兼容存量数据）
    private Status status;
    private Integer deathChapter;

    private int version;
    private Instant updatedAt;

    public Character(String projectId, String name, String role) {
        this.id = UUID.randomUUID().toString();
        this.projectId = projectId;
        this.name = name;
        this.role = role;
        this.version = 1;
        this.updatedAt = Instant.now();
    }

    public Status getStatus() { return status == null ? Status.ACTIVE : status; }
    public void setStatus(Status status) { this.status = status; }
    public Integer getDeathChapter() { return deathChapter; }
    public void setDeathChapter(Integer deathChapter) { this.deathChapter = deathChapter; }
    public boolean isDeceased() { return status == Status.DECEASED; }

    public String getId() { return id; }
    public String getProjectId() { return projectId; }
    public String getName() { return name; }
    public void setName(String n) { this.name = n; bumpVersion(); }
    public String getRole() { return role; }
    public void setRole(String r) { this.role = r; bumpVersion(); }
    public String getPersonality() { return personality; }
    public void setPersonality(String p) { this.personality = p; bumpVersion(); }
    public String getAbility() { return ability; }
    public void setAbility(String a) { this.ability = a; bumpVersion(); }
    public String getMotivation() { return motivation; }
    public void setMotivation(String m) { this.motivation = m; bumpVersion(); }
    public String getAppearance() { return appearance; }
    public void setAppearance(String a) { this.appearance = a; bumpVersion(); }
    public String getRelationships() { return relationships; }
    public void setRelationships(String r) { this.relationships = r; bumpVersion(); }
    public String getGrowthArc() { return growthArc; }
    public void setGrowthArc(String g) { this.growthArc = g; bumpVersion(); }
    public String getSpeakingStyle() { return speakingStyle; }
    public void setSpeakingStyle(String s) { this.speakingStyle = s; bumpVersion(); }
    public String getKnowledgeDomains() { return knowledgeDomains; }
    public void setKnowledgeDomains(String k) { this.knowledgeDomains = k; bumpVersion(); }
    public Integer getStrategyLevel() { return strategyLevel; }
    public void setStrategyLevel(Integer s) { this.strategyLevel = s; bumpVersion(); }
    public String getCountermeasures() { return countermeasures; }
    public void setCountermeasures(String c) { this.countermeasures = c; bumpVersion(); }
    public String getIndependentWill() { return independentWill; }
    public void setIndependentWill(String i) { this.independentWill = i; bumpVersion(); }
    public String getBlindSpot() { return blindSpot; }
    public void setBlindSpot(String b) { this.blindSpot = b; bumpVersion(); }
    public String getArcStage() { return arcStage; }
    public void setArcStage(String s) { this.arcStage = s; bumpVersion(); }
    public Integer getLastSeenChapter() { return lastSeenChapter; }
    public void setLastSeenChapter(Integer c) { this.lastSeenChapter = c; bumpVersion(); }
    public int getVersion() { return version; }
    public Instant getUpdatedAt() { return updatedAt; }

    private void bumpVersion() { this.version++; this.updatedAt = Instant.now(); }
}
