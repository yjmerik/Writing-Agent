package com.writing.novel.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * p12-K2: 异步写作任务——后台执行写作循环，解决 HTTP 超时问题。
 * 状态机：RUNNING → PAUSED / COMPLETED / FAILED / CANCELLED
 */
public class WritingTask {
    public enum Status { RUNNING, PAUSED, COMPLETED, FAILED, CANCELLED }

    private String id;
    private String projectId;
    private Status status;
    private int currentChapter;
    private int totalChapters;
    private int chaptersWritten;
    private int totalWords;
    private String errorMessage;
    private Instant createdAt;
    private Instant updatedAt;

    public WritingTask(String projectId, int totalChapters) {
        this.id = UUID.randomUUID().toString();
        this.projectId = projectId;
        this.status = Status.RUNNING;
        this.totalChapters = totalChapters;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public String getId() { return id; }
    public String getProjectId() { return projectId; }
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
