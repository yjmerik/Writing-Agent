package com.writing.novel.domain;

import java.util.ArrayList;
import java.util.List;

/** 全书大纲：总纲 + 多卷 */
public class Outline {
    private String projectId;
    private String premise;   // 总纲/主旨
    private List<Volume> volumes;

    public Outline(String projectId, String premise) {
        this.projectId = projectId;
        this.premise = premise;
        this.volumes = new ArrayList<>();
    }

    public void addVolume(Volume v) { this.volumes.add(v); }

    public String getProjectId() { return projectId; }
    public String getPremise() { return premise; }
    public void setPremise(String p) { this.premise = p; }
    public List<Volume> getVolumes() { return volumes; }
}
