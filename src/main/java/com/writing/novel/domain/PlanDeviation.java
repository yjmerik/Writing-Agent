package com.writing.novel.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * NCS-019（方案 §06）：计划偏离记录——章实际正文与其计划（梗概/合同）的对照。
 * 决定三选一：UPDATE_PLAN_FROM_TEXT（按正文更新计划）/ REVISE_TEXT（修订正文，
 * 创建修订请求）/ DEFER（稍后决定）。已写章正文不因计划更新被改写。
 */
public class PlanDeviation {

    public static final String UPDATE_PLAN_FROM_TEXT = "UPDATE_PLAN_FROM_TEXT";
    public static final String REVISE_TEXT = "REVISE_TEXT";
    public static final String DEFER = "DEFER";

    private String id;
    private String projectId;
    private int chapterIndex;
    private int chapterVersion;
    private String deviationJson;
    private String decision;
    private Instant decidedAt;
    private Instant createdAt;

    public PlanDeviation() {
        this.id = UUID.randomUUID().toString();
        this.createdAt = Instant.now();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }
    public int getChapterIndex() { return chapterIndex; }
    public void setChapterIndex(int chapterIndex) { this.chapterIndex = chapterIndex; }
    public int getChapterVersion() { return chapterVersion; }
    public void setChapterVersion(int chapterVersion) { this.chapterVersion = chapterVersion; }
    public String getDeviationJson() { return deviationJson; }
    public void setDeviationJson(String deviationJson) { this.deviationJson = deviationJson; }
    public String getDecision() { return decision; }
    public void setDecision(String decision) { this.decision = decision; }
    public Instant getDecidedAt() { return decidedAt; }
    public void setDecidedAt(Instant decidedAt) { this.decidedAt = decidedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
