package com.writing.novel.domain;

import java.time.Instant;

/** NCS-045: 人物修改影响分析持久记录——绑定基准（规范状态指纹）与逐章检查范围 */
public class CharacterImpactAnalysis {

    private Long id;
    private final String projectId;
    private final String characterId;
    private final String baseVersion;      // 人物规范状态 SHA-256（12 hex）
    private final String scopeJson;        // 逐章 (chapterIndex, chapterVersion)
    private final String listsJson;        // certain/unknown/insufficient 清单
    private final Instant createdAt;

    public CharacterImpactAnalysis(String projectId, String characterId, String baseVersion,
                                   Object scope, Object certain, Object unknown, Object insufficient) {
        this.projectId = projectId;
        this.characterId = characterId;
        this.baseVersion = baseVersion;
        var om = new com.fasterxml.jackson.databind.ObjectMapper();
        try {
            this.scopeJson = om.writeValueAsString(scope);
            this.listsJson = om.writeValueAsString(java.util.Map.of(
                    "certainReferences", certain, "unknownReferences", unknown,
                    "insufficientCoverage", insufficient));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getProjectId() { return projectId; }
    public String getCharacterId() { return characterId; }
    public String getBaseVersion() { return baseVersion; }
    public String getScopeJson() { return scopeJson; }
    public String getListsJson() { return listsJson; }
    public Instant getCreatedAt() { return createdAt; }
}
