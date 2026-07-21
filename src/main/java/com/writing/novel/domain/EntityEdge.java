package com.writing.novel.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * 实体间的关系边——构成关系图谱。
 * 每条边有类型、权重和元数据，支持图遍历召回。
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

    public EntityEdge(String projectId, String sourceId, String targetId,
                      RelationshipType relationship) {
        this.id = UUID.randomUUID().toString();
        this.projectId = projectId;
        this.sourceId = sourceId;
        this.targetId = targetId;
        this.relationship = relationship;
        this.weight = 1.0;
        this.createdAt = Instant.now();
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
}
