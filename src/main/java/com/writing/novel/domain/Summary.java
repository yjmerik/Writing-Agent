package com.writing.novel.domain;

import java.time.Instant;
import java.util.UUID;

/** 滚动摘要：覆盖一段章节范围 */
public class Summary {
    private String id;
    private String projectId;
    private int fromChapter;
    private int toChapter;
    private String content;
    private Instant updatedAt;

    public Summary(String projectId, int fromChapter, int toChapter, String content) {
        this.id = UUID.randomUUID().toString();
        this.projectId = projectId;
        this.fromChapter = fromChapter;
        this.toChapter = toChapter;
        this.content = content;
        this.updatedAt = Instant.now();
    }

    /** 该摘要是否覆盖某章 */
    public boolean covers(int chapterIndex) {
        return chapterIndex >= fromChapter && chapterIndex <= toChapter;
    }

    public String getId() { return id; }
    public String getProjectId() { return projectId; }
    public int getFromChapter() { return fromChapter; }
    public int getToChapter() { return toChapter; }
    public String getContent() { return content; }
    public void setContent(String c) { this.content = c; this.updatedAt = Instant.now(); }
    public Instant getUpdatedAt() { return updatedAt; }
}
