package com.writing.novel.domain;

import java.time.Instant;
import java.util.UUID;

/** 连续性矛盾报告 */
public class ContinuityIssue {
    public enum Type { CHARACTER, WORLDBUILDING, TIMELINE, FORESHADOWING, PLOT, PACING, STRUCTURE, DENSITY, STYLE, TEXTURE }
    public enum Severity { LOW, MEDIUM, HIGH }

    private String id;
    private String projectId;
    private int detectedAtChapter;
    private Type type;
    private Severity severity;
    private String description;
    private boolean resolved;
    private Instant createdAt;
    /** p32-05: 产生时点的章节版本与正文指纹（NULL=功能前旧数据），由持久化边界盖章。 */
    private Integer chapterVersion;
    private String contentHash;
    /** p32-05: 质量报告响应期判定——绑定版本是否已非当前（非持久化）。 */
    private boolean stale;

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
    public Integer getChapterVersion() { return chapterVersion; }
    public void setChapterVersion(Integer v) { this.chapterVersion = v; }
    public String getContentHash() { return contentHash; }
    public void setContentHash(String h) { this.contentHash = h; }
    public boolean isStale() { return stale; }
    public void setStale(boolean s) { this.stale = s; }
}
