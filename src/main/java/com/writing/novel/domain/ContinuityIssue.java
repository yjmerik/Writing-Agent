package com.writing.novel.domain;

import java.time.Instant;
import java.util.UUID;

/** 连续性矛盾报告 */
public class ContinuityIssue {
    public enum Type { CHARACTER, WORLDBUILDING, TIMELINE, FORESHADOWING }
    public enum Severity { LOW, MEDIUM, HIGH }

    private String id;
    private String projectId;
    private int detectedAtChapter;
    private Type type;
    private Severity severity;
    private String description;
    private boolean resolved;
    private Instant createdAt;

    public ContinuityIssue(String projectId, int detectedAtChapter, Type type,
                           Severity severity, String description) {
        this.id = UUID.randomUUID().toString();
        this.projectId = projectId;
        this.detectedAtChapter = detectedAtChapter;
        this.type = type;
        this.severity = severity;
        this.description = description;
        this.resolved = false;
        this.createdAt = Instant.now();
    }

    public void resolve() { this.resolved = true; }

    public String getId() { return id; }
    public String getProjectId() { return projectId; }
    public int getDetectedAtChapter() { return detectedAtChapter; }
    public Type getType() { return type; }
    public Severity getSeverity() { return severity; }
    public String getDescription() { return description; }
    public boolean isResolved() { return resolved; }
    public Instant getCreatedAt() { return createdAt; }
}
