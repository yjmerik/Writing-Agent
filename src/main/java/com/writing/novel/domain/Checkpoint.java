package com.writing.novel.domain;

import java.time.Instant;
import java.util.UUID;

/** 交互模式的审核检查点 */
public class Checkpoint {
    public enum Type {
        AFTER_INITIAL_SETUP,   // 初始设定生成后
        AFTER_VOLUME_OUTLINE,  // 每卷大纲细化后
        AFTER_CHAPTER,         // 每章写完后
        ON_CONTINUITY_FIX      // 发现矛盾需修正
    }
    public enum Status { PENDING, APPROVED, MODIFIED, REJECTED }

    private String id;
    private String projectId;
    private Type type;
    private String description;
    private Status status;
    private Instant createdAt;

    public Checkpoint(String projectId, Type type, String description) {
        this.id = UUID.randomUUID().toString();
        this.projectId = projectId;
        this.type = type;
        this.description = description;
        this.status = Status.PENDING;
        this.createdAt = Instant.now();
    }

    public void approve() { this.status = Status.APPROVED; }
    public void modify() { this.status = Status.MODIFIED; }
    public void reject() { this.status = Status.REJECTED; }

    public String getId() { return id; }
    public String getProjectId() { return projectId; }
    public Type getType() { return type; }
    public String getDescription() { return description; }
    public Status getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
}
