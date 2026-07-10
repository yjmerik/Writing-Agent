package com.writing.novel.domain;

import java.time.Instant;
import java.util.UUID;

/** 已写成的章节正文 */
public class Chapter {
    private String id;
    private String projectId;
    private int chapterIndex;
    private String title;
    private String content;
    private int wordCount;
    private int version;
    private Instant createdAt;
    private Instant updatedAt;

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

    public String getId() { return id; }
    public String getProjectId() { return projectId; }
    public int getChapterIndex() { return chapterIndex; }
    public String getTitle() { return title; }
    public void setTitle(String t) { this.title = t; }
    public String getContent() { return content; }
    public int getWordCount() { return wordCount; }
    public int getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
