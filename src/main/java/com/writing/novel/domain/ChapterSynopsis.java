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
    private String plotPurpose;      // 本章推进的剧情线（如"主角获得力量"或"揭示反派身份"）
    private int tensionLevel;        // 张力等级 1-10（1=平静过渡，10=全书高潮）
    private String volumeArcPosition; // 卷内位置：SETUP/RISING/CLIMAX/RESOLUTION

    // p10-c1: 大纲层伏笔规划——本章应埋设 / 应回收的伏笔
    private String foreshadowingsToPlant;   // 本章应埋设的伏笔（分号分隔）
    private String foreshadowingsToResolve; // 本章应回收的伏笔描述（分号分隔）

    public ChapterSynopsis(String projectId, int chapterIndex, String title, String synopsis) {
        this.id = UUID.randomUUID().toString();
        this.projectId = projectId;
        this.chapterIndex = chapterIndex;
        this.title = title;
        this.synopsis = synopsis;
        this.tensionLevel = 5; // 默认中等张力
    }

    /** 为受保护的已写章创建脱离持久化对象的完整副本，同时保留其可信身份。 */
    public ChapterSynopsis copyPreservingIdentity() {
        ChapterSynopsis copy = new ChapterSynopsis(projectId, chapterIndex, title, synopsis);
        copy.id = id;
        copy.writingNotes = writingNotes;
        copy.beats = beats;
        copy.climax = climax;
        copy.cast = cast;
        copy.plotPurpose = plotPurpose;
        copy.tensionLevel = tensionLevel;
        copy.volumeArcPosition = volumeArcPosition;
        copy.foreshadowingsToPlant = foreshadowingsToPlant;
        copy.foreshadowingsToResolve = foreshadowingsToResolve;
        return copy;
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
    public String getPlotPurpose() { return plotPurpose; }
    public void setPlotPurpose(String p) { this.plotPurpose = p; }
    public int getTensionLevel() { return tensionLevel; }
    public void setTensionLevel(int t) { this.tensionLevel = Math.max(1, Math.min(10, t)); }
    public String getVolumeArcPosition() { return volumeArcPosition; }
    public void setVolumeArcPosition(String v) { this.volumeArcPosition = v; }
    public String getForeshadowingsToPlant() { return foreshadowingsToPlant; }
    public void setForeshadowingsToPlant(String f) { this.foreshadowingsToPlant = f; }
    public String getForeshadowingsToResolve() { return foreshadowingsToResolve; }
    public void setForeshadowingsToResolve(String f) { this.foreshadowingsToResolve = f; }
}
