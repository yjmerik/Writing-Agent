package com.writing.novel.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * p33-01（设计 5.1 / 体验 #18）：人审屏障——阻止依赖它的后续章节生成的持久化对象。
 * 标记 NEEDS_MANUAL_REVIEW 时立即落盘；写作调度检查项目全部 ACTIVE 屏障
 * （不再只查当前章状态字段）；作者放行（resolve-review）后解决。
 */
public class ReviewBarrier {

    public enum Status { ACTIVE, RESOLVED }

    private String id;
    private String projectId;
    private int chapterIndex;
    private int chapterVersion;
    private String reason;
    private Status status = Status.ACTIVE;
    private Instant createdAt;
    private Instant resolvedAt;

    public ReviewBarrier(String projectId, int chapterIndex, int chapterVersion, String reason) {
        this.id = UUID.randomUUID().toString();
        this.projectId = projectId;
        this.chapterIndex = chapterIndex;
        this.chapterVersion = chapterVersion;
        this.reason = reason;
        this.createdAt = Instant.now();
    }

    /** 行映射/测试构造：完整字段恢复。 */
    public ReviewBarrier(String id, String projectId, int chapterIndex, int chapterVersion,
                         String reason, Status status, Instant createdAt, Instant resolvedAt) {
        this.id = id;
        this.projectId = projectId;
        this.chapterIndex = chapterIndex;
        this.chapterVersion = chapterVersion;
        this.reason = reason;
        this.status = status;
        this.createdAt = createdAt;
        this.resolvedAt = resolvedAt;
    }

    public void resolve() {
        this.status = Status.RESOLVED;
        this.resolvedAt = Instant.now();
    }

    public String getId() { return id; }
    public String getProjectId() { return projectId; }
    public int getChapterIndex() { return chapterIndex; }
    public int getChapterVersion() { return chapterVersion; }
    public String getReason() { return reason; }
    public Status getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getResolvedAt() { return resolvedAt; }
}
