package com.writing.novel.domain;

import java.util.UUID;

/** 单章的梗概（大纲的最小单元） */
public class ChapterSynopsis {
    private String id;
    private String projectId;
    private int chapterIndex;   // 全局章节序号
    private String title;
    private String synopsis;    // 本章场景节拍核心描述
    private String writingNotes; // 写作要求：字数/节奏（可选）
    private String beats;       // 场景节拍（分号分隔，如"被嘲讽→遭遇危机→觉醒→反击"）
    private String climax;      // 本章爽点设计
    private String cast;        // 出场人物

    public ChapterSynopsis(String projectId, int chapterIndex, String title, String synopsis) {
        this.id = UUID.randomUUID().toString();
        this.projectId = projectId;
        this.chapterIndex = chapterIndex;
        this.title = title;
        this.synopsis = synopsis;
    }

    public String getId() { return id; }
    public String getProjectId() { return projectId; }
    public int getChapterIndex() { return chapterIndex; }
    public String getTitle() { return title; }
    public void setTitle(String t) { this.title = t; }
    public String getSynopsis() { return synopsis; }
    public void setSynopsis(String s) { this.synopsis = s; }
    public String getWritingNotes() { return writingNotes; }
    public void setWritingNotes(String n) { this.writingNotes = n; }
    public String getBeats() { return beats; }
    public void setBeats(String b) { this.beats = b; }
    public String getClimax() { return climax; }
    public void setClimax(String c) { this.climax = c; }
    public String getCast() { return cast; }
    public void setCast(String c) { this.cast = c; }
}
