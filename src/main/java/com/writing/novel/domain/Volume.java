package com.writing.novel.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 一卷：含标题、梗概与若干章节梗概 */
public class Volume {
    private String id;
    private String projectId;
    private int volumeIndex;
    private String title;
    private String summary;
    private List<ChapterSynopsis> synopses;

    public Volume(String projectId, int volumeIndex, String title, String summary) {
        this.id = UUID.randomUUID().toString();
        this.projectId = projectId;
        this.volumeIndex = volumeIndex;
        this.title = title;
        this.summary = summary;
        this.synopses = new ArrayList<>();
    }

    public void addSynopsis(ChapterSynopsis s) { this.synopses.add(s); }

    /** 为受保护的已写卷创建脱离持久化对象的副本，同时保留其可信身份。 */
    public Volume copyPreservingIdentity() {
        Volume copy = new Volume(projectId, volumeIndex, title, summary);
        copy.id = id;
        return copy;
    }

    public String getId() { return id; }
    public String getProjectId() { return projectId; }
    public int getVolumeIndex() { return volumeIndex; }
    public String getTitle() { return title; }
    public void setTitle(String t) { this.title = t; }
    public String getSummary() { return summary; }
    public void setSummary(String s) { this.summary = s; }
    public List<ChapterSynopsis> getSynopses() { return synopses; }
}
