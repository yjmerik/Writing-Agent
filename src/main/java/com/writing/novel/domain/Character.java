package com.writing.novel.domain;

import java.time.Instant;
import java.util.UUID;

/** 人物卡：一个角色的核心设定 */
public class Character {
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
    public int getVersion() { return version; }
    public Instant getUpdatedAt() { return updatedAt; }

    private void bumpVersion() { this.version++; this.updatedAt = Instant.now(); }
}
