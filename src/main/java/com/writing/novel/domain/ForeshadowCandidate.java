package com.writing.novel.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * p33-07（设计 5.5 / 体验 #15）：候选线索——叙事承诺的前置层。
 * 提取器产出的非 HIGH/无回收窗口细节先进候选层（PENDING），
 * 由作者提升为正式伏笔（PROMOTED）或拒绝（REJECTED）。
 * 台账正式层只保留叙事承诺，细节不再淹没主线。
 */
public class ForeshadowCandidate {

    public enum Status { PENDING, PROMOTED, REJECTED }

    private String id;
    private String projectId;
    private String description;
    private String plantedContext;
    private int plantedAtChapter;
    private Status status = Status.PENDING;
    private Instant createdAt;
    private Instant updatedAt;

    public ForeshadowCandidate(String projectId, String description,
                               String plantedContext, int plantedAtChapter) {
        this.id = UUID.randomUUID().toString();
        this.projectId = projectId;
        this.description = description;
        this.plantedContext = plantedContext;
        this.plantedAtChapter = plantedAtChapter;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    /** 行映射完整构造。 */
    public ForeshadowCandidate(String id, String projectId, String description,
                               String plantedContext, int plantedAtChapter,
                               Status status, Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.projectId = projectId;
        this.description = description;
        this.plantedContext = plantedContext;
        this.plantedAtChapter = plantedAtChapter;
        this.status = status;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public void promote() { this.status = Status.PROMOTED; this.updatedAt = Instant.now(); }
    public void reject() { this.status = Status.REJECTED; this.updatedAt = Instant.now(); }

    public String getId() { return id; }
    public String getProjectId() { return projectId; }
    public String getDescription() { return description; }
    public String getPlantedContext() { return plantedContext; }
    public int getPlantedAtChapter() { return plantedAtChapter; }
    public Status getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
