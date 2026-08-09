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
    /** mc-p2-01: 派生来源章节版本——生成时取覆盖范围最新章（toChapter）的版本号 */
    private int sourceChapterVersion;
    /** mc-p2-01: 脏标记——源章节被重写后置 true，组装时过滤，重算后清 false */
    private boolean dirty;

    public Summary(String projectId, int fromChapter, int toChapter, String content) {
        this.id = UUID.randomUUID().toString();
        this.projectId = projectId;
        this.fromChapter = fromChapter;
        this.toChapter = toChapter;
        this.content = content;
        this.updatedAt = Instant.now();
        this.sourceChapterVersion = 0;
        this.dirty = false;
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
    public int getSourceChapterVersion() { return sourceChapterVersion; }
    public void setSourceChapterVersion(int v) { this.sourceChapterVersion = v; }
    public boolean isDirty() { return dirty; }
    public void setDirty(boolean dirty) { this.dirty = dirty; }
}
