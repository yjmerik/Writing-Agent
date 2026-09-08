package com.writing.novel.domain;

import java.time.Instant;
import java.util.UUID;

/** 已写成的章节正文 */
public class Chapter {

    /**
     * p10-w1-06: 章节生命周期状态机。
     * DRAFTING（正文生成中）→ POST_PROCESSING（后处理中）→ DONE（可推进索引）。
     * 后处理失败的章节停在 POST_PROCESSING，重启后可识别并重跑后处理；
     * NEEDS_MANUAL_REVIEW 标记早期根源矛盾待人工处理（闭环修复用）。
     */
    public enum Status { DRAFTING, POST_PROCESSING, DONE, NEEDS_MANUAL_REVIEW }

    private String id;
    private String projectId;
    private int chapterIndex;
    private String title;
    private String content;
    private int wordCount;
    private int version;
    /** p10-w1-06: 默认 DONE 兼容旧库（旧章节视为已完成） */
    private Status status = Status.DONE;
    private Instant createdAt;
    private Instant updatedAt;
    /** p32-01: 派生数据（摘要/索引/收割）刷新失败的响应期标记——非持久化，不入库。 */
    private boolean derivedRefreshPending;

    public Chapter(String projectId, int chapterIndex, String title) {
        this.id = UUID.randomUUID().toString();
        this.projectId = projectId;
        this.chapterIndex = chapterIndex;
        this.title = title;
        this.content = "";
        this.wordCount = 0;
        this.version = 1;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public void setContent(String content) {
        this.content = content;
        this.wordCount = content == null ? 0 : content.length();
        this.version++;
        this.updatedAt = Instant.now();
    }

    /**
     * p32-02: 以库中最新稿整体接管本内存对象（含版本号，不额外自增）。
     * 自动重写/状态标记遇作者版本冲突后调用，防止陈旧对象继续整行覆盖。
     */
    public void adoptStateFrom(Chapter latest) {
        this.id = latest.id;
        this.projectId = latest.projectId;
        this.chapterIndex = latest.chapterIndex;
        this.title = latest.title;
        this.content = latest.content;
        this.wordCount = latest.wordCount;
        this.version = latest.version;
        this.status = latest.status;
        this.createdAt = latest.createdAt;
        this.updatedAt = latest.updatedAt;
    }

    public String getId() { return id; }
    public String getProjectId() { return projectId; }
    public int getChapterIndex() { return chapterIndex; }
    public String getTitle() { return title; }
    public void setTitle(String t) { this.title = t; }
    public String getContent() { return content; }
    public int getWordCount() { return wordCount; }
    public int getVersion() { return version; }
    public Status getStatus() { return status; }
    public void setStatus(Status s) { this.status = s; this.updatedAt = Instant.now(); }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public boolean isDerivedRefreshPending() { return derivedRefreshPending; }
    public void setDerivedRefreshPending(boolean p) { this.derivedRefreshPending = p; }
}
