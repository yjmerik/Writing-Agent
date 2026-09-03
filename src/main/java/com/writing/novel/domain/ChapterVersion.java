package com.writing.novel.domain;

import java.time.Instant;

/** An immutable snapshot of a chapter before its content changes. */
public final class ChapterVersion {
    private final String id;
    private final String projectId;
    private final int chapterIndex;
    private final int version;
    private final String title;
    private final String content;
    private final String source;
    private final Instant createdAt;

    public ChapterVersion(String id, String projectId, int chapterIndex, int version,
                          String title, String content, String source, Instant createdAt) {
        this.id = id;
        this.projectId = projectId;
        this.chapterIndex = chapterIndex;
        this.version = version;
        this.title = title;
        this.content = content;
        this.source = source;
        this.createdAt = createdAt;
    }

    public String getId() { return id; }
    public String getProjectId() { return projectId; }
    public int getChapterIndex() { return chapterIndex; }
    public int getVersion() { return version; }
    public String getTitle() { return title; }
    public String getContent() { return content; }
    public String getSource() { return source; }
    public Instant getCreatedAt() { return createdAt; }
}
