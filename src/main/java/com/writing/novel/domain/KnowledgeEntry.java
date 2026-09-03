package com.writing.novel.domain;

import java.time.Instant;
import java.util.UUID;

/** 结构化知识库的单个条目，是人物/世界观/物品等的统一载体 */
public class KnowledgeEntry {
    /** 世界观/知识类型；ITEM 保留用于系统生成与存量数据，人工接口另行限制可选类型。 */
    public enum Type {
        GEOGRAPHY, POWER_SYSTEM, FACTION, HISTORY, RULE, CULTURE, ECONOMY, RACE, ITEM, CUSTOM
    }

    private String id;
    private String projectId;
    private String type;
    private String name;
    private String content;
    private int version;
    private Instant updatedAt;

    public KnowledgeEntry(String projectId, String type, String name, String content) {
        this.id = UUID.randomUUID().toString();
        this.projectId = projectId;
        this.type = type;
        this.name = name;
        this.content = content;
        this.version = 1;
        this.updatedAt = Instant.now();
    }

    public String getId() { return id; }
    public String getProjectId() { return projectId; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; bumpVersion(); }
    public String getName() { return name; }
    public void setName(String n) { this.name = n; bumpVersion(); }
    public String getContent() { return content; }
    public void setContent(String c) { this.content = c; bumpVersion(); }
    public int getVersion() { return version; }
    public Instant getUpdatedAt() { return updatedAt; }

    private void bumpVersion() { this.version++; this.updatedAt = Instant.now(); }
}
