package com.writing.novel.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * 叙事线索——追踪贯穿多章的剧情线索。
 * 防止叙事遗漏和线索断裂。
 */
public class NarrativeThread {
    private final String id;
    private final String projectId;
    private String name;                // "主角查账线"、"北境军镇动向"
    private ThreadStatus status;         // ACTIVE / SUSPENDED / RESOLVED
    private int firstSeenChapter;
    private int lastSeenChapter;
    private int suspenseChapters;        // 连续多少章未跟进
    private String description;          // 线索简述
    private final Instant createdAt;
    private Instant updatedAt;

    public NarrativeThread(String projectId, String name, int chapterIndex, String description) {
        this.id = UUID.randomUUID().toString();
        this.projectId = projectId;
        this.name = name;
        this.status = ThreadStatus.ACTIVE;
        this.firstSeenChapter = chapterIndex;
        this.lastSeenChapter = chapterIndex;
        this.suspenseChapters = 0;
        this.description = description;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    /** 本章中线索被推进——清零悬挂计数 */
    public void markActive(int chapterIndex) {
        this.status = ThreadStatus.ACTIVE;
        this.lastSeenChapter = chapterIndex;
        this.suspenseChapters = 0;
        this.updatedAt = Instant.now();
    }

    /** 本章未提及该线索——悬挂计数+1 */
    public void markSuspended(int chapterIndex) {
        this.status = ThreadStatus.SUSPENDED;
        this.suspenseChapters++;
        this.lastSeenChapter = chapterIndex;
        this.updatedAt = Instant.now();
    }

    /** 线索被回收/结束 */
    public void markResolved(int chapterIndex) {
        this.status = ThreadStatus.RESOLVED;
        this.lastSeenChapter = chapterIndex;
        this.updatedAt = Instant.now();
    }

    /** 是否悬挂过久（超过阈值） */
    public boolean isOverdue(int threshold) {
        return status == ThreadStatus.SUSPENDED && suspenseChapters >= threshold;
    }

    // Getters / Setters
    public String getId() { return id; }
    public String getProjectId() { return projectId; }
    public String getName() { return name; }
    public void setName(String n) { this.name = n; this.updatedAt = Instant.now(); }
    public ThreadStatus getStatus() { return status; }
    public void setStatus(ThreadStatus s) { this.status = s; this.updatedAt = Instant.now(); }
    public int getFirstSeenChapter() { return firstSeenChapter; }
    public int getLastSeenChapter() { return lastSeenChapter; }
    public void setLastSeenChapter(int c) { this.lastSeenChapter = c; this.updatedAt = Instant.now(); }
    public int getSuspenseChapters() { return suspenseChapters; }
    public void setSuspenseChapters(int s) { this.suspenseChapters = s; this.updatedAt = Instant.now(); }
    public String getDescription() { return description; }
    public void setDescription(String d) { this.description = d; this.updatedAt = Instant.now(); }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
