package com.writing.novel.domain;

import java.time.Instant;
import java.util.UUID;

/** 伏笔条目：埋设与回收追踪 */
public class Foreshadowing {

    public enum Priority { HIGH, MEDIUM, LOW }

    private String id;
    private String projectId;
    private String description;
    private String plantedContext;   // 埋设时的上下文说明
    private int plantedAtChapter;    // 埋设于第几章
    private boolean resolved;
    private Integer resolvedAtChapter; // 回收于第几章
    /** p32-08: 回收证据——解释谜题的具体依据（detector 给出的 reason/人工确认说明），NULL=功能前旧数据 */
    private String resolveEvidence;
    private Instant updatedAt;

    // p10-c1: 重要性 / 截止章节 / 必须回收标记
    private Priority priority = Priority.MEDIUM;
    private Integer expectedResolveChapter; // 预计回收章节（由大纲规划）
    private boolean mustResolve;            // 必须回收（HIGH 且关键主线）

    public Foreshadowing(String projectId, String description, String plantedContext, int plantedAtChapter) {
        this.id = UUID.randomUUID().toString();
        this.projectId = projectId;
        this.description = description;
        this.plantedContext = plantedContext;
        this.plantedAtChapter = plantedAtChapter;
        this.resolved = false;
        this.updatedAt = Instant.now();
    }

    /** p32-08: 人工/兼容路径回收——标注来源为人工确认。 */
    public void resolve(int atChapter) {
        resolve(atChapter, "人工回收确认（作者）");
    }

    /** p32-08: 仅供行映射从库恢复状态使用（不产生来源标注）。 */
    public void resolveRaw(int atChapter) {
        this.resolved = true;
        this.resolvedAtChapter = atChapter;
        this.updatedAt = Instant.now();
    }

    /** p32-08: 回收必须携带证据（哪段正文/哪条解释兑现了该伏笔）。 */
    public void resolve(int atChapter, String evidence) {
        this.resolved = true;
        this.resolvedAtChapter = atChapter;
        this.resolveEvidence = evidence;
        this.updatedAt = Instant.now();
    }

    /** 挂起章节数（当前章 - 埋设章），用于排序"挂得最久"的伏笔 */
    public int suspenseChapters(int currentChapter) {
        return Math.max(0, currentChapter - plantedAtChapter);
    }

    /** 是否已超过预计回收章节仍未回收 */
    public boolean isOverdue(int currentChapter) {
        return !resolved && expectedResolveChapter != null && currentChapter > expectedResolveChapter;
    }

    /** p11.1-J3: 是否悬置过久（不依赖 expectedResolveChapter，只看悬置章节数） */
    public boolean isStale(int currentChapter, int threshold) {
        return !resolved && suspenseChapters(currentChapter) >= threshold;
    }

    public String getId() { return id; }
    public String getProjectId() { return projectId; }
    public String getDescription() { return description; }
    public void setDescription(String d) { this.description = d; }
    public String getPlantedContext() { return plantedContext; }
    public int getPlantedAtChapter() { return plantedAtChapter; }
    public boolean isResolved() { return resolved; }
    public Integer getResolvedAtChapter() { return resolvedAtChapter; }
    public String getResolveEvidence() { return resolveEvidence; }
    public void setResolveEvidence(String e) { this.resolveEvidence = e; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Priority getPriority() { return priority; }
    public void setPriority(Priority p) { this.priority = p; }
    public Integer getExpectedResolveChapter() { return expectedResolveChapter; }
    public void setExpectedResolveChapter(Integer c) { this.expectedResolveChapter = c; }
    public boolean isMustResolve() { return mustResolve; }
    public void setMustResolve(boolean m) { this.mustResolve = m; }
}
