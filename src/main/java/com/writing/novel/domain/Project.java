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
}
