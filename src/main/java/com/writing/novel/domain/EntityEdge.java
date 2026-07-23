package com.writing.novel.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * 实体间的关系边——构成关系图谱。
 * 每条边有类型、权重和元数据，支持图遍历召回。
 *
 * 时间作用域：validFromChapter/validToChapter 标记边的有效章节范围。
 * 关系变更时旧边 validToChapter=N，新边 validFromChapter=N+1。
 * validToChapter=0（默认）表示当前仍然有效。
 */
public class EntityEdge {
    private final String id;
    private final String projectId;
    private final String sourceId;
    private final String targetId;
    private RelationshipType relationship;
    private double weight;
    private String metadata;       // JSON {"evidence":"原文引用"}
    private Instant createdAt;
    private int validFromChapter;  // 关系开始生效的章节（0=从最初）
    private int validToChapter;    // 关系失效的章节（0=当前仍有效）

    public EntityEdge(String projectId, String sourceId, String targetId,
                      RelationshipType relationship) {
        this.id = UUID.randomUUID().toString();
        this.projectId = projectId;
        this.sourceId = sourceId;
        this.targetId = targetId;
        this.relationship = relationship;
        this.weight = 1.0;
        this.createdAt = Instant.now();
        this.validFromChapter = 0;
        this.validToChapter = 0;
    }

    /** 标记此边在指定章节失效（关系变更时调用） */
    public void expire(int atChapter) {
        this.validToChapter = atChapter;
    }

    /** 此边在指定章节是否有效 */
    public boolean isValidAt(int chapterIndex) {
        if (validFromChapter > 0 && chapterIndex < validFromChapter) return false;
        if (validToChapter > 0 && chapterIndex > validToChapter) return false;
        return true;
    }

    /** 此边当前是否有效（validToChapter=0） */
    public boolean isCurrent() {
        return validToChapter == 0;
    }

    public String getId() { return id; }
    public String getProjectId() { return projectId; }
    public String getSourceId() { return sourceId; }
    public String getTargetId() { return targetId; }
    public RelationshipType getRelationship() { return relationship; }
    public void setRelationship(RelationshipType r) { this.relationship = r; }
    public double getWeight() { return weight; }
    public void setWeight(double w) { this.weight = w; }
    public String getMetadata() { return metadata; }
    public void setMetadata(String m) { this.metadata = m; }
    public Instant getCreatedAt() { return createdAt; }
    public int getValidFromChapter() { return validFromChapter; }
    public void setValidFromChapter(int c) { this.validFromChapter = c; }
    public int getValidToChapter() { return validToChapter; }
    public void setValidToChapter(int c) { this.validToChapter = c; }
}
