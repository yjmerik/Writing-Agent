package com.writing.novel.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 实体节点——图数据库的基本单元。
 * 代表小说中一个有独立身份的名称实体（人物/物品/地点/功法/势力/事件/概念）。
 */
public class Entity {
    private final String id;
    private final String projectId;
    private String name;
    private EntityType type;
    private List<String> aliases;          // 别名（含常见笔误）
    private int firstSeenChapter;
    private int lastSeenChapter;
    private String metadata;               // JSON 扩展
    private Instant createdAt;
    private Instant updatedAt;

    public Entity(String projectId, String name, EntityType type) {
        this.id = UUID.randomUUID().toString();
        this.projectId = projectId;
        this.name = name;
        this.type = type;
        this.aliases = new ArrayList<>();
        this.firstSeenChapter = 0;
        this.lastSeenChapter = 0;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    /** 出现于某章时更新 */
    public void seenAt(int chapterIndex) {
        if (firstSeenChapter == 0 || chapterIndex < firstSeenChapter) {
            firstSeenChapter = chapterIndex;
        }
        if (chapterIndex > lastSeenChapter) {
            lastSeenChapter = chapterIndex;
        }
        this.updatedAt = Instant.now();
    }

    /** 添加别名（去重） */
    public void addAlias(String alias) {
        if (alias == null || alias.isBlank()) return;
        if (!aliases.contains(alias) && !alias.equals(name)) {
            aliases.add(alias);
            this.updatedAt = Instant.now();
        }
    }

    /** 批量添加别名 */
    public void addAliases(List<String> newAliases) {
        if (newAliases == null) return;
        for (String a : newAliases) {
            addAlias(a);
        }
    }

    public String getId() { return id; }
    public String getProjectId() { return projectId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; this.updatedAt = Instant.now(); }
    public EntityType getType() { return type; }
    public void setType(EntityType type) { this.type = type; this.updatedAt = Instant.now(); }
    public List<String> getAliases() { return aliases; }
    public void setAliases(List<String> aliases) { this.aliases = aliases; this.updatedAt = Instant.now(); }
    public int getFirstSeenChapter() { return firstSeenChapter; }
    public void setFirstSeenChapter(int c) { this.firstSeenChapter = c; }
    public int getLastSeenChapter() { return lastSeenChapter; }
    public void setLastSeenChapter(int c) { this.lastSeenChapter = c; }
    public String getMetadata() { return metadata; }
    public void setMetadata(String m) { this.metadata = m; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
