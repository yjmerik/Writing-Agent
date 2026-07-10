package com.writing.novel.domain;

import java.time.Instant;
import java.util.UUID;

/** 伏笔条目：埋设与回收追踪 */
public class Foreshadowing {
    private String id;
    private String projectId;
    private String description;
    private String plantedContext;   // 埋设时的上下文说明
    private int plantedAtChapter;    // 埋设于第几章
    private boolean resolved;
    private Integer resolvedAtChapter; // 回收于第几章
    private Instant updatedAt;

    public Foreshadowing(String projectId, String description, String plantedContext, int plantedAtChapter) {
        this.id = UUID.randomUUID().toString();
        this.projectId = projectId;
        this.description = description;
        this.plantedContext = plantedContext;
        this.plantedAtChapter = plantedAtChapter;
        this.resolved = false;
        this.updatedAt = Instant.now();
    }

    public void resolve(int atChapter) {
        this.resolved = true;
        this.resolvedAtChapter = atChapter;
        this.updatedAt = Instant.now();
    }

    public String getId() { return id; }
    public String getProjectId() { return projectId; }
    public String getDescription() { return description; }
    public void setDescription(String d) { this.description = d; }
    public String getPlantedContext() { return plantedContext; }
    public int getPlantedAtChapter() { return plantedAtChapter; }
    public boolean isResolved() { return resolved; }
    public Integer getResolvedAtChapter() { return resolvedAtChapter; }
    public Instant getUpdatedAt() { return updatedAt; }
}
