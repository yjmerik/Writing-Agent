package com.writing.novel.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * NCS-026（方案 §13/Reflexion·L4）：运行经验——修订接受/拒绝样本与原因。
 * 同作品同任务的后续修订提示注入（适用范围明确）；不是故事事实、不写真典；
 * 作者可删除；模型自我反思只是假设，不修改全局约束。
 */
public class RevisionOutcome {

    public static final String ACCEPTED = "ACCEPTED";
    public static final String REJECTED = "REJECTED";

    private String id;
    private String projectId;
    private String changeClass;   // DelegationPolicyService.ChangeClass.name()
    private String decision;      // ACCEPTED / REJECTED
    private String instructions;  // 当时的修改指令
    private String summary;       // 候选摘要/结果概述
    private String reason;        // 作者理由（拒绝时必填；接受可空）
    private Instant createdAt;

    public RevisionOutcome() {
        this.id = UUID.randomUUID().toString();
        this.createdAt = Instant.now();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }
    public String getChangeClass() { return changeClass; }
    public void setChangeClass(String changeClass) { this.changeClass = changeClass; }
    public String getDecision() { return decision; }
    public void setDecision(String decision) { this.decision = decision; }
    public String getInstructions() { return instructions; }
    public void setInstructions(String instructions) { this.instructions = instructions; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
