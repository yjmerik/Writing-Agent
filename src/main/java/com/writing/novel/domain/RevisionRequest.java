package com.writing.novel.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * NCS-013（方案 §12/F07/F09）：统一修订请求——六类修改入口的归一登记。
 * 直接编辑/选区润色/整章重写/设定变更/计划调整/恢复旧稿统一转为 RevisionRequest，
 * 携带基准版本集、目标锚点、意图、保护项与来源。范围批准（scopeDecision）与
 * 内容采纳（acceptance）是两个独立决定，分别记录、可追溯。
 */
public class RevisionRequest {

    /** 六类修改入口（方案 §12 统一六类入口表） */
    public enum Origin { DIRECT_EDIT, PASSAGE_POLISH, CHAPTER_REWRITE, SETTING_CHANGE, PLAN_ADJUST, PLAN_DEVIATION, RESTORE }

    /** OPEN → SCOPE_APPROVED → COMPLETED；另有 CANCELLED */
    public enum Status { OPEN, SCOPE_APPROVED, COMPLETED, CANCELLED }

    private String id;
    private String projectId;
    private Origin origin;
    private String intent;            // 修改意图（作者原话）
    private String targetAnchorsJson; // 目标锚点（章/段落/对象）
    private String baseVersionsJson;  // 基准版本集（章→稿版本）
    private String protectAnchorsJson;// 保护项（不可改范围与理由）
    private Status status;
    private String scopeDecision;     // 范围批准记录（独立于内容采纳）
    private String acceptance;        // 内容采纳记录（采纳了什么、commandId）
    private String cancelReason;
    private Instant createdAt;
    private Instant updatedAt;

    public RevisionRequest() {
        this.id = UUID.randomUUID().toString();
        this.status = Status.OPEN;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public void approveScope(String decision) {
        if (this.status != Status.OPEN) {
            throw new IllegalStateException("只有 OPEN 状态的请求可批准范围，当前: " + this.status);
        }
        this.scopeDecision = decision;
        this.status = Status.SCOPE_APPROVED;
        this.updatedAt = Instant.now();
    }

    public void accept(String acceptanceRecord) {
        if (this.status == Status.CANCELLED) {
            throw new IllegalStateException("已取消的请求不能采纳");
        }
        this.acceptance = acceptanceRecord;
        this.status = Status.COMPLETED;
        this.updatedAt = Instant.now();
    }

    public void cancel(String reason) {
        if (this.status == Status.COMPLETED) {
            throw new IllegalStateException("已完成的请求不能取消");
        }
        this.cancelReason = reason;
        this.status = Status.CANCELLED;
        this.updatedAt = Instant.now();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }
    public Origin getOrigin() { return origin; }
    public void setOrigin(Origin origin) { this.origin = origin; }
    public String getIntent() { return intent; }
    public void setIntent(String intent) { this.intent = intent; }
    public String getTargetAnchorsJson() { return targetAnchorsJson; }
    public void setTargetAnchorsJson(String v) { this.targetAnchorsJson = v; }
    public String getBaseVersionsJson() { return baseVersionsJson; }
    public void setBaseVersionsJson(String v) { this.baseVersionsJson = v; }
    public String getProtectAnchorsJson() { return protectAnchorsJson; }
    public void setProtectAnchorsJson(String v) { this.protectAnchorsJson = v; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public String getScopeDecision() { return scopeDecision; }
    public void setScopeDecision(String scopeDecision) { this.scopeDecision = scopeDecision; }
    public String getAcceptance() { return acceptance; }
    public void setAcceptance(String acceptance) { this.acceptance = acceptance; }
    public String getCancelReason() { return cancelReason; }
    public void setCancelReason(String cancelReason) { this.cancelReason = cancelReason; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
