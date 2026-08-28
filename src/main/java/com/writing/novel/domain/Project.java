package com.writing.novel.domain;

import java.time.Instant;
import java.util.UUID;

/** 一次小说写作项目的根聚合 */
public class Project {
    private String id;
    private String title;
    private String concept;       // 构思简介
    private String tags;          // 逗号分隔的类型标签，如 "玄幻,热血"
    private ProjectMode mode;
    private NovelState state;
    private GenreProfile genre;   // 题材类型（p5-d1: 自动推断）
    private Instant createdAt;
    private Instant updatedAt;
    /** 当前已写到第几章（全局递增） */
    private int currentChapterIndex;
    /** C2: 全书摘要——卷末压缩的≤3句全书梗概，注入上下文最顶部（修复记忆地平线） */
    private String bookSummary;

    public Project(String title, String concept, String tags) {
        this.id = UUID.randomUUID().toString();
        this.title = title;
        this.concept = concept;
        this.tags = tags;
        this.mode = ProjectMode.SOLO;
        this.state = NovelState.CONCEIVING;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
        this.currentChapterIndex = 0;
    }

    public void touch() {
        this.updatedAt = Instant.now();
    }

    // --- getters / setters ---
    public String getId() { return id; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; touch(); }
    public String getConcept() { return concept; }
    public String getTags() { return tags; }
    public ProjectMode getMode() { return mode; }
    public void setMode(ProjectMode mode) { this.mode = mode; touch(); }
    public NovelState getState() { return state; }
    public void setState(NovelState state) { this.state = state; touch(); }
    public GenreProfile getGenre() { return genre; }
    public void setGenre(GenreProfile genre) { this.genre = genre; touch(); }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public int getCurrentChapterIndex() { return currentChapterIndex; }
    public void setCurrentChapterIndex(int i) { this.currentChapterIndex = i; touch(); }
    public String getBookSummary() { return bookSummary; }
    public void setBookSummary(String s) { this.bookSummary = s; touch(); }

    /** p19-03: 全书结构化快照（JSON：主线进度/活跃阵营/关键人物状态/时间线锚点/已跨里程碑） */
    private String bookSnapshot;
    public String getBookSnapshot() { return bookSnapshot; }
    public void setBookSnapshot(String s) { this.bookSnapshot = s; touch(); }
}
