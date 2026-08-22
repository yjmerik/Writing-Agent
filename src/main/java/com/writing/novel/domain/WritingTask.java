package com.writing.novel.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * p12-K2: 异步写作任务——后台执行写作循环，解决 HTTP 超时问题。
 * 状态机：RUNNING → PAUSED / COMPLETED / FAILED / CANCELLED
 * p15-05: 任务类型（CONCEIVE 构思 / WRITE_CHAPTER 单章 / WRITE_ALL 全卷）
 */
public class WritingTask {
    public enum Status { RUNNING, PAUSED, COMPLETED, FAILED, CANCELLED }
    public enum Type { CONCEIVE, WRITE_CHAPTER, WRITE_ALL }

    private String id;
    private String projectId;
    private Type type;
    private Status status;
    private int currentChapter;
    private int totalChapters;
    private int chaptersWritten;
    private int totalWords;
    private String errorMessage;
    private Instant createdAt;
    private Instant updatedAt;

    public WritingTask(String projectId, int totalChapters) {
        this(projectId, totalChapters, Type.WRITE_ALL);
    }

    public WritingTask(String projectId, int totalChapters, Type type) {
        this.id = UUID.randomUUID().toString();
        this.projectId = projectId;
        this.type = type;
        this.status = Status.RUNNING;
        this.totalChapters = totalChapters;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public String getId() { return id; }
    public String getProjectId() { return projectId; }
    public Type getType() { return type; }
    public Status getStatus() { return status; }
    public void setStatus(Status s) { this.status = s; this.updatedAt = Instant.now(); }
    public int getCurrentChapter() { return currentChapter; }
    public void setCurrentChapter(int c) { this.currentChapter = c; this.updatedAt = Instant.now(); }
    public int getTotalChapters() { return totalChapters; }
    public int getChaptersWritten() { return chaptersWritten; }
    public void setChaptersWritten(int c) { this.chaptersWritten = c; }
    public int getTotalWords() { return totalWords; }
    public void setTotalWords(int w) { this.totalWords = w; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String e) { this.errorMessage = e; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
