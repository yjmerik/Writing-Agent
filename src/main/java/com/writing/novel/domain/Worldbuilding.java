package com.writing.novel.domain;

import java.util.ArrayList;
import java.util.List;

/** 一个项目的世界观，由若干知识条目构成 */
public class Worldbuilding {
    private String projectId;
    private List<KnowledgeEntry> entries;

    public Worldbuilding(String projectId) {
        this.projectId = projectId;
        this.entries = new ArrayList<>();
    }

    public void addEntry(KnowledgeEntry entry) {
        this.entries.add(entry);
    }

    public String getProjectId() { return projectId; }
    public List<KnowledgeEntry> getEntries() { return entries; }
}
