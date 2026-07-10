# Plan 1: 项目骨架 + 持久化 SPI + Domain 模型

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 搭建 Spring Boot 项目骨架，定义全部领域模型与持久化 SPI 接口，并提供 SQLite + 嵌入式向量库的 MVP 实现，使后续 Agent/Orchestrator/API 层有稳固地基。

**Architecture:** 分层架构：Domain（POJO 领域模型，无依赖）→ SPI 接口（NovelRepository / VectorStore / EmbeddingProvider）→ 实现（SQLite via JDBC / 嵌入式向量库本地文件 / 云 API embedding）。业务层只依赖接口，通过 `application.yml` 配置切换实现。

**Tech Stack:** Java 17, Spring Boot 3.x, Maven, SQLite (via `sqlite-jdbc` + `sqlg` 或原生 JDBC), JUnit 5, 嵌入式向量检索（自实现，本地文件序列化）。

**Spec 参考:** `docs/superpowers/specs/2026-07-10-novel-writing-system-design.md` 第 3 节（持久化 SPI）、第 4 节（领域对象来源）。

---

## 文件结构

本 plan 产出的文件结构（`com.writing.novel` 为根包）：

```
WritingAgent/
├── pom.xml
├── src/main/java/com/writing/novel/
│   ├── NovelWritingApplication.java          # Spring Boot 启动类
│   ├── domain/                                # 领域模型（POJO，无依赖）
│   │   ├── Project.java
│   │   ├── ProjectMode.java                  # 枚举: SOLO / INTERACTIVE
│   │   ├── NovelState.java                   # 枚举: 状态机各状态
│   │   ├── Worldbuilding.java
│   │   ├── Character.java
│   │   ├── Outline.java
│   │   ├── Volume.java
│   │   ├── ChapterSynopsis.java              # 章节梗概
│   │   ├── Chapter.java
│   │   ├── Foreshadowing.java                # 伏笔条目
│   │   ├── Summary.java                      # 滚动摘要
│   │   ├── StyleProfile.java                 # 风格基线
│   │   ├── ContinuityIssue.java             # 连续性矛盾报告
│   │   ├── Checkpoint.java                   # 审核检查点
│   │   └── KnowledgeEntry.java              # 知识库条目（带类型）
│   ├── spi/                                   # SPI 接口
│   │   ├── NovelRepository.java
│   │   ├── VectorStore.java
│   │   └── EmbeddingProvider.java
│   ├── persistence/                           # SQLite 实现
│   │   ├── sqlite/
│   │   │   ├── SqliteNovelRepository.java
│   │   │   ├── SqliteSchemaInitializer.java
│   │   │   └── ConnectionProvider.java
│   │   └── embedding/
│   │       └── EmbeddedVectorStore.java
│   └── config/
│       └── PersistenceConfig.java            # 按配置装配 SPI 实现
├── src/main/resources/
│   └── application.yml
└── src/test/java/com/writing/novel/
    ├── domain/                                # 领域模型测试
    └── persistence/                           # SPI 实现测试
```

---

## Task 1: Maven 项目骨架

**Files:**
- Create: `pom.xml`
- Create: `src/main/java/com/writing/novel/NovelWritingApplication.java`
- Create: `src/main/resources/application.yml`

- [ ] **Step 1: 创建 `pom.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>3.2.5</version>
        <relativePath/>
    </parent>

    <groupId>com.writing</groupId>
    <artifactId>writing-agent</artifactId>
    <version>0.1.0-SNAPSHOT</version>
    <name>writing-agent</name>
    <description>长篇小说自动写作系统</description>

    <properties>
        <java.version>17</java.version>
    </properties>

    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        <dependency>
            <groupId>org.xerial</groupId>
            <artifactId>sqlite-jdbc</artifactId>
            <version>3.45.1.0</version>
        </dependency>
        <dependency>
            <groupId>com.fasterxml.jackson.core</groupId>
            <artifactId>jackson-databind</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
```

- [ ] **Step 2: 创建 Spring Boot 启动类**

`src/main/java/com/writing/novel/NovelWritingApplication.java`:
```java
package com.writing.novel;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class NovelWritingApplication {
    public static void main(String[] args) {
        SpringApplication.run(NovelWritingApplication.class, args);
    }
}
```

- [ ] **Step 3: 创建 `application.yml`**

`src/main/resources/application.yml`:
```yaml
spring:
  application:
    name: writing-agent

persistence:
  relational: sqlite
  vector: embedded
  embedding: cloud-api
  sqlite:
    db-path: ./data/novel.db
  vector-store:
    index-path: ./data/vectors.idx
  embedding:
    api-base-url: ${EMBEDDING_API_URL:}
    api-key: ${EMBEDDING_API_KEY:}
    model: ${EMBEDDING_MODEL:text-embedding-v2}
```

- [ ] **Step 4: 验证可编译启动**

Run: `mvn clean compile`
Expected: BUILD SUCCESS

Run: `mvn spring-boot:run`（启动后 Ctrl+C 退出即可）
Expected: 看到 Spring Boot 启动日志，无报错

- [ ] **Step 5: 提交**

```bash
git init && git add -A
git commit -m "chore: 初始化 Spring Boot 项目骨架与配置"
```

---

## Task 2: 领域模型 — 枚举与基础类

**Files:**
- Create: `src/main/java/com/writing/novel/domain/ProjectMode.java`
- Create: `src/main/java/com/writing/novel/domain/NovelState.java`
- Create: `src/main/java/com/writing/novel/domain/Project.java`
- Test: `src/test/java/com/writing/novel/domain/ProjectTest.java`

- [ ] **Step 1: 写失败测试 — Project 创建与状态流转**

`src/test/java/com/writing/novel/domain/ProjectTest.java`:
```java
package com.writing.novel.domain;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProjectTest {

    @Test
    void 新建项目默认为构思状态且Solo模式() {
        Project p = new Project("测试小说", "一个测试简介", "玄幻,热血");
        assertEquals(NovelState.CONCEIVING, p.getState());
        assertEquals(ProjectMode.SOLO, p.getMode());
        assertEquals("测试小说", p.getTitle());
        assertEquals("一个测试简介", p.getConcept());
    }

    @Test
    void 项目支持切换模式() {
        Project p = new Project("t", "c", "tag");
        p.setMode(ProjectMode.INTERACTIVE);
        assertEquals(ProjectMode.INTERACTIVE, p.getMode());
    }

    @Test
    void 项目支持状态转移() {
        Project p = new Project("t", "c", "tag");
        p.setState(NovelState.PLANNING);
        assertEquals(NovelState.PLANNING, p.getState());
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn test -Dtest=ProjectTest`
Expected: FAIL（类不存在）

- [ ] **Step 3: 创建枚举**

`src/main/java/com/writing/novel/domain/ProjectMode.java`:
```java
package com.writing.novel.domain;

/** 运行模式：全自动 vs 人机交互 */
public enum ProjectMode {
    SOLO,
    INTERACTIVE
}
```

`src/main/java/com/writing/novel/domain/NovelState.java`:
```java
package com.writing.novel.domain;

/** 小说生命周期状态机 */
public enum NovelState {
    CONCEIVING,      // 构思：生成世界观/人物/大纲
    PLANNING,        // 规划：细化大纲
    WRITING,         // 创作中
    VOLUME_DONE,     // 单卷完结
    FINAL,           // 全书完结
    PAUSED_AT_CHECKPOINT  // 交互模式暂停于检查点
}
```

- [ ] **Step 4: 创建 Project 领域类**

`src/main/java/com/writing/novel/domain/Project.java`:
```java
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
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public int getCurrentChapterIndex() { return currentChapterIndex; }
    public void setCurrentChapterIndex(int i) { this.currentChapterIndex = i; touch(); }
}
```

- [ ] **Step 5: 运行测试确认通过**

Run: `mvn test -Dtest=ProjectTest`
Expected: 3 个测试 PASS

- [ ] **Step 6: 提交**

```bash
git add -A
git commit -m "feat(domain): Project 聚合根与状态/模式枚举"
```

---

## Task 3: 领域模型 — 世界观、人物、知识条目

**Files:**
- Create: `src/main/java/com/writing/novel/domain/KnowledgeEntry.java`
- Create: `src/main/java/com/writing/novel/domain/Worldbuilding.java`
- Create: `src/main/java/com/writing/novel/domain/Character.java`
- Test: `src/test/java/com/writing/novel/domain/KnowledgeModelTest.java`

- [ ] **Step 1: 写失败测试**

`src/test/java/com/writing/novel/domain/KnowledgeModelTest.java`:
```java
package com.writing.novel.domain;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class KnowledgeModelTest {

    @Test
    void 世界观由多个知识条目组成() {
        Worldbuilding wb = new Worldbuilding("proj-1");
        wb.addEntry(new KnowledgeEntry("proj-1", "GEOGRAPHY", "玄黄大陆",
                "故事发生的主大陆，分为东玄西黄两域。"));
        wb.addEntry(new KnowledgeEntry("proj-1", "POWER_SYSTEM", "灵气体系",
                "修炼者吸收天地灵气淬炼己身，境界分九重。"));
        assertEquals(2, wb.getEntries().size());
        assertEquals("玄黄大陆", wb.getEntries().get(0).getName());
    }

    @Test
    void 人物卡包含核心属性() {
        Character c = new Character("proj-1", "张三", "主角");
        c.setPersonality("火爆、重情义");
        c.setAbility("金丹期剑修");
        c.setMotivation("为师门复仇");
        assertEquals("张三", c.getName());
        assertEquals("主角", c.getRole());
        assertEquals("火爆、重情义", c.getPersonality());
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn test -Dtest=KnowledgeModelTest`
Expected: FAIL（类不存在）

- [ ] **Step 3: 创建 KnowledgeEntry**

`src/main/java/com/writing/novel/domain/KnowledgeEntry.java`:
```java
package com.writing.novel.domain;

import java.time.Instant;
import java.util.UUID;

/** 结构化知识库的单个条目，是人物/世界观/物品等的统一载体 */
public class KnowledgeEntry {
    /** 类型：地理/势力/力量体系/历史/规则/物品 等 */
    public enum Type {
        GEOGRAPHY, FACTION, POWER_SYSTEM, HISTORY, RULE, ITEM, CUSTOM
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
    public String getName() { return name; }
    public void setName(String n) { this.name = n; bumpVersion(); }
    public String getContent() { return content; }
    public void setContent(String c) { this.content = c; bumpVersion(); }
    public int getVersion() { return version; }
    public Instant getUpdatedAt() { return updatedAt; }

    private void bumpVersion() { this.version++; this.updatedAt = Instant.now(); }
}
```

- [ ] **Step 4: 创建 Worldbuilding**

`src/main/java/com/writing/novel/domain/Worldbuilding.java`:
```java
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
```

- [ ] **Step 5: 创建 Character**

`src/main/java/com/writing/novel/domain/Character.java`:
```java
package com.writing.novel.domain;

import java.time.Instant;
import java.util.UUID;

/** 人物卡：一个角色的核心设定 */
public class Character {
    private String id;
    private String projectId;
    private String name;
    private String role;          // 主角/配角/反派 等
    private String personality;
    private String ability;       // 能力/境界
    private String motivation;    // 动机
    private String appearance;    // 外貌
    private String relationships; // 人物关系（自然语言或结构化）
    private String growthArc;     // 成长弧
    private int version;
    private Instant updatedAt;

    public Character(String projectId, String name, String role) {
        this.id = UUID.randomUUID().toString();
        this.projectId = projectId;
        this.name = name;
        this.role = role;
        this.version = 1;
        this.updatedAt = Instant.now();
    }

    public String getId() { return id; }
    public String getProjectId() { return projectId; }
    public String getName() { return name; }
    public void setName(String n) { this.name = n; bumpVersion(); }
    public String getRole() { return role; }
    public void setRole(String r) { this.role = r; bumpVersion(); }
    public String getPersonality() { return personality; }
    public void setPersonality(String p) { this.personality = p; bumpVersion(); }
    public String getAbility() { return ability; }
    public void setAbility(String a) { this.ability = a; bumpVersion(); }
    public String getMotivation() { return motivation; }
    public void setMotivation(String m) { this.motivation = m; bumpVersion(); }
    public String getAppearance() { return appearance; }
    public void setAppearance(String a) { this.appearance = a; bumpVersion(); }
    public String getRelationships() { return relationships; }
    public void setRelationships(String r) { this.relationships = r; bumpVersion(); }
    public String getGrowthArc() { return growthArc; }
    public void setGrowthArc(String g) { this.growthArc = g; bumpVersion(); }
    public int getVersion() { return version; }
    public Instant getUpdatedAt() { return updatedAt; }

    private void bumpVersion() { this.version++; this.updatedAt = Instant.now(); }
}
```

- [ ] **Step 6: 运行测试确认通过**

Run: `mvn test -Dtest=KnowledgeModelTest`
Expected: 2 个测试 PASS

- [ ] **Step 7: 提交**

```bash
git add -A
git commit -m "feat(domain): 世界观、人物卡、知识条目模型"
```

---

## Task 4: 领域模型 — 大纲、卷、章节、伏笔

**Files:**
- Create: `src/main/java/com/writing/novel/domain/ChapterSynopsis.java`
- Create: `src/main/java/com/writing/novel/domain/Volume.java`
- Create: `src/main/java/com/writing/novel/domain/Outline.java`
- Create: `src/main/java/com/writing/novel/domain/Foreshadowing.java`
- Create: `src/main/java/com/writing/novel/domain/Chapter.java`
- Test: `src/test/java/com/writing/novel/domain/OutlineModelTest.java`

- [ ] **Step 1: 写失败测试**

`src/test/java/com/writing/novel/domain/OutlineModelTest.java`:
```java
package com.writing.novel.domain;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OutlineModelTest {

    @Test
    void 大纲由总纲与多卷组成() {
        Outline o = new Outline("proj-1", "主角从凡人修炼至大乘的史诗。");
        Volume v1 = new Volume("proj-1", 1, "初入仙门", "主角拜入青云宗。");
        v1.addSynopsis(new ChapterSynopsis("proj-1", 1, "第一章 灵根测试",
                "张三参加灵根测试，意外觉醒上古剑骨。"));
        o.addVolume(v1);

        assertEquals("主角从凡人修炼至大乘的史诗。", o.getPremise());
        assertEquals(1, o.getVolumes().size());
        assertEquals("初入仙门", o.getVolumes().get(0).getTitle());
        assertEquals(1, o.getVolumes().get(0).getSynopses().size());
    }

    @Test
    void 伏笔可标记为已回收() {
        Foreshadowing f = new Foreshadowing("proj-1", "主角血脉之谜",
                "第1章主角觉醒剑骨暗示其特殊血脉。", 1);
        assertFalse(f.isResolved());
        f.resolve(45);  // 第45章回收
        assertTrue(f.isResolved());
        assertEquals(45, f.getResolvedAtChapter());
    }

    @Test
    void 章节包含正文与字数() {
        Chapter c = new Chapter("proj-1", 1, "第一章 灵根测试");
        c.setContent("这是正文内容。");
        assertEquals("第一章 灵根测试", c.getTitle());
        assertEquals("这是正文内容。", c.getContent());
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn test -Dtest=OutlineModelTest`
Expected: FAIL（类不存在）

- [ ] **Step 3: 创建 ChapterSynopsis**

`src/main/java/com/writing/novel/domain/ChapterSynopsis.java`:
```java
package com.writing.novel.domain;

import java.util.UUID;

/** 单章的梗概（大纲的最小单元） */
public class ChapterSynopsis {
    private String id;
    private String projectId;
    private int chapterIndex;   // 全局章节序号
    private String title;
    private String synopsis;    // 本章要写什么
    private String writingNotes; // 写作要求：字数/爽点/节奏（可选）

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
}
```

- [ ] **Step 4: 创建 Volume**

`src/main/java/com/writing/novel/domain/Volume.java`:
```java
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

    public String getId() { return id; }
    public String getProjectId() { return projectId; }
    public int getVolumeIndex() { return volumeIndex; }
    public String getTitle() { return title; }
    public void setTitle(String t) { this.title = t; }
    public String getSummary() { return summary; }
    public void setSummary(String s) { this.summary = s; }
    public List<ChapterSynopsis> getSynopses() { return synopses; }
}
```

- [ ] **Step 5: 创建 Outline**

`src/main/java/com/writing/novel/domain/Outline.java`:
```java
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
```

- [ ] **Step 6: 创建 Foreshadowing**

`src/main/java/com/writing/novel/domain/Foreshadowing.java`:
```java
package com.writing.novel.domain;

import java.time.Instant;
import java.util.UUID;

/** 伏笔条目：埋设与回收追踪 */
public class Foreshadowing {
    private String id;
    private String projectId;
    private String description;
    private String plantedContext;   // 埋设时的上下文说明
    private int plantedAtChapter;    // 埋设于第几章
    private boolean resolved;
    private Integer resolvedAtChapter; // 回收于第几章
    private Instant updatedAt;

    public Foreshadowing(String projectId, String description, String plantedContext, int plantedAtChapter) {
        this.id = UUID.randomUUID().toString();
        this.projectId = projectId;
        this.description = description;
        this.plantedContext = plantedContext;
        this.plantedAtChapter = plantedAtChapter;
        this.resolved = false;
        this.updatedAt = Instant.now();
    }

    public void resolve(int atChapter) {
        this.resolved = true;
        this.resolvedAtChapter = atChapter;
        this.updatedAt = Instant.now();
    }

    public String getId() { return id; }
    public String getProjectId() { return projectId; }
    public String getDescription() { return description; }
    public void setDescription(String d) { this.description = d; }
    public String getPlantedContext() { return plantedContext; }
    public int getPlantedAtChapter() { return plantedAtChapter; }
    public boolean isResolved() { return resolved; }
    public Integer getResolvedAtChapter() { return resolvedAtChapter; }
    public Instant getUpdatedAt() { return updatedAt; }
}
```

- [ ] **Step 7: 创建 Chapter**

`src/main/java/com/writing/novel/domain/Chapter.java`:
```java
package com.writing.novel.domain;

import java.time.Instant;
import java.util.UUID;

/** 已写成的章节正文 */
public class Chapter {
    private String id;
    private String projectId;
    private int chapterIndex;
    private String title;
    private String content;
    private int wordCount;
    private int version;
    private Instant createdAt;
    private Instant updatedAt;

    public Chapter(String projectId, int chapterIndex, String title) {
        this.id = UUID.randomUUID().toString();
        this.projectId = projectId;
        this.chapterIndex = chapterIndex;
        this.title = title;
        this.content = "";
        this.wordCount = 0;
        this.version = 1;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public void setContent(String content) {
        this.content = content;
        this.wordCount = content == null ? 0 : content.length();
        this.version++;
        this.updatedAt = Instant.now();
    }

    public String getId() { return id; }
    public String getProjectId() { return projectId; }
    public int getChapterIndex() { return chapterIndex; }
    public String getTitle() { return title; }
    public void setTitle(String t) { this.title = t; }
    public String getContent() { return content; }
    public int getWordCount() { return wordCount; }
    public int getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
```

- [ ] **Step 8: 运行测试确认通过**

Run: `mvn test -Dtest=OutlineModelTest`
Expected: 3 个测试 PASS

- [ ] **Step 9: 提交**

```bash
git add -A
git commit -m "feat(domain): 大纲、卷、章节梗概、伏笔、章节模型"
```

---

## Task 5: 领域模型 — 摘要、风格、连续性、检查点

**Files:**
- Create: `src/main/java/com/writing/novel/domain/Summary.java`
- Create: `src/main/java/com/writing/novel/domain/StyleProfile.java`
- Create: `src/main/java/com/writing/novel/domain/ContinuityIssue.java`
- Create: `src/main/java/com/writing/novel/domain/Checkpoint.java`
- Test: `src/test/java/com/writing/novel/domain/SupportingModelTest.java`

- [ ] **Step 1: 写失败测试**

`src/test/java/com/writing/novel/domain/SupportingModelTest.java`:
```java
package com.writing.novel.domain;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SupportingModelTest {

    @Test
    void 摘要覆盖一段章节范围() {
        Summary s = new Summary("proj-1", 1, 5,
                "第1-5章：主角拜入青云宗，通过灵根测试，与师兄结怨。");
        assertEquals(1, s.getFromChapter());
        assertEquals(5, s.getToChapter());
        assertTrue(s.covers(3));
        assertFalse(s.covers(6));
    }

    @Test
    void 风格基线记录关键词与样本() {
        StyleProfile sp = new StyleProfile("proj-1");
        sp.setTone("热血、紧凑");
        sp.addSampleChapter(1);
        assertEquals("热血、紧凑", sp.getTone());
        assertTrue(sp.getSampleChapterIndices().contains(1));
    }

    @Test
    void 连续性问题分严重级别() {
        ContinuityIssue issue = new ContinuityIssue("proj-1", 12,
                ContinuityIssue.Type.CHARACTER, ContinuityIssue.Severity.HIGH,
                "张三的佩剑名前后不一致：第12章叫霜寒，第3章叫断水。");
        assertEquals(ContinuityIssue.Severity.HIGH, issue.getSeverity());
        assertFalse(issue.isResolved());
        issue.resolve();
        assertTrue(issue.isResolved());
    }

    @Test
    void 检查点可被解决() {
        Checkpoint cp = new Checkpoint("proj-1", Checkpoint.Type.AFTER_INITIAL_SETUP,
                "设定产物待审核");
        assertEquals(Checkpoint.Status.PENDING, cp.getStatus());
        cp.approve();
        assertEquals(Checkpoint.Status.APPROVED, cp.getStatus());
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn test -Dtest=SupportingModelTest`
Expected: FAIL（类不存在）

- [ ] **Step 3: 创建 Summary**

`src/main/java/com/writing/novel/domain/Summary.java`:
```java
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

    public Summary(String projectId, int fromChapter, int toChapter, String content) {
        this.id = UUID.randomUUID().toString();
        this.projectId = projectId;
        this.fromChapter = fromChapter;
        this.toChapter = toChapter;
        this.content = content;
        this.updatedAt = Instant.now();
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
}
```

- [ ] **Step 4: 创建 StyleProfile**

`src/main/java/com/writing/novel/domain/StyleProfile.java`:
```java
package com.writing.novel.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** 风格基线：定义全书文风锚点 */
public class StyleProfile {
    private String projectId;
    private String tone;          // 基调：热血/暗黑/幽默
    private String voice;         // 叙事声音说明
    private String dosAndDonts;   // 风格注意事项
    private List<Integer> sampleChapterIndices;  // 作为风格样本的章节号
    private Instant updatedAt;

    public StyleProfile(String projectId) {
        this.projectId = projectId;
        this.sampleChapterIndices = new ArrayList<>();
        this.updatedAt = Instant.now();
    }

    public void addSampleChapter(int chapterIndex) {
        if (!sampleChapterIndices.contains(chapterIndex)) {
            sampleChapterIndices.add(chapterIndex);
        }
        this.updatedAt = Instant.now();
    }

    public String getProjectId() { return projectId; }
    public String getTone() { return tone; }
    public void setTone(String t) { this.tone = t; this.updatedAt = Instant.now(); }
    public String getVoice() { return voice; }
    public void setVoice(String v) { this.voice = v; this.updatedAt = Instant.now(); }
    public String getDosAndDonts() { return dosAndDonts; }
    public void setDosAndDonts(String d) { this.dosAndDonts = d; this.updatedAt = Instant.now(); }
    public List<Integer> getSampleChapterIndices() { return sampleChapterIndices; }
    public Instant getUpdatedAt() { return updatedAt; }
}
```

- [ ] **Step 5: 创建 ContinuityIssue**

`src/main/java/com/writing/novel/domain/ContinuityIssue.java`:
```java
package com.writing.novel.domain;

import java.time.Instant;
import java.util.UUID;

/** 连续性矛盾报告 */
public class ContinuityIssue {
    public enum Type { CHARACTER, WORLDBUILDING, TIMELINE, FORESHADOWING }
    public enum Severity { LOW, MEDIUM, HIGH }

    private String id;
    private String projectId;
    private int detectedAtChapter;
    private Type type;
    private Severity severity;
    private String description;
    private boolean resolved;
    private Instant createdAt;

    public ContinuityIssue(String projectId, int detectedAtChapter, Type type,
                           Severity severity, String description) {
        this.id = UUID.randomUUID().toString();
        this.projectId = projectId;
        this.detectedAtChapter = detectedAtChapter;
        this.type = type;
        this.severity = severity;
        this.description = description;
        this.resolved = false;
        this.createdAt = Instant.now();
    }

    public void resolve() { this.resolved = true; }

    public String getId() { return id; }
    public String getProjectId() { return projectId; }
    public int getDetectedAtChapter() { return detectedAtChapter; }
    public Type getType() { return type; }
    public Severity getSeverity() { return severity; }
    public String getDescription() { return description; }
    public boolean isResolved() { return resolved; }
    public Instant getCreatedAt() { return createdAt; }
}
```

- [ ] **Step 6: 创建 Checkpoint**

`src/main/java/com/writing/novel/domain/Checkpoint.java`:
```java
package com.writing.novel.domain;

import java.time.Instant;
import java.util.UUID;

/** 交互模式的审核检查点 */
public class Checkpoint {
    public enum Type {
        AFTER_INITIAL_SETUP,   // 初始设定生成后
        AFTER_VOLUME_OUTLINE,  // 每卷大纲细化后
        AFTER_CHAPTER,         // 每章写完后
        ON_CONTINUITY_FIX      // 发现矛盾需修正
    }
    public enum Status { PENDING, APPROVED, MODIFIED, REJECTED }

    private String id;
    private String projectId;
    private Type type;
    private String description;
    private Status status;
    private Instant createdAt;

    public Checkpoint(String projectId, Type type, String description) {
        this.id = UUID.randomUUID().toString();
        this.projectId = projectId;
        this.type = type;
        this.description = description;
        this.status = Status.PENDING;
        this.createdAt = Instant.now();
    }

    public void approve() { this.status = Status.APPROVED; }
    public void modify() { this.status = Status.MODIFIED; }
    public void reject() { this.status = Status.REJECTED; }

    public String getId() { return id; }
    public String getProjectId() { return projectId; }
    public Type getType() { return type; }
    public String getDescription() { return description; }
    public Status getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
}
```

- [ ] **Step 7: 运行测试确认通过**

Run: `mvn test -Dtest=SupportingModelTest`
Expected: 4 个测试 PASS

- [ ] **Step 8: 提交**

```bash
git add -A
git commit -m "feat(domain): 摘要、风格基线、连续性问题、检查点模型"
```

---

## Task 6: SPI 接口 — NovelRepository

**Files:**
- Create: `src/main/java/com/writing/novel/spi/NovelRepository.java`
- Test: `src/test/java/com/writing/novel/spi/NovelRepositoryContractTest.java`

**说明:** 本 task 只定义接口与契约测试（抽象基类测试），具体 SQLite 实现在 Task 8。契约测试保证未来换实现时行为一致。

- [ ] **Step 1: 定义 NovelRepository 接口**

`src/main/java/com/writing/novel/spi/NovelRepository.java`:
```java
package com.writing.novel.spi;

import com.writing.novel.domain.*;

import java.util.List;
import java.util.Optional;

/**
 * 小说数据的持久化 SPI。业务层只依赖此接口。
 * MVP 实现：SqliteNovelRepository；云端实现：Mysql/Postgres 版本。
 */
public interface NovelRepository {

    // --- Project ---
    String saveProject(Project project);
    Optional<Project> findProject(String id);
    List<Project> findAllProjects();
    void deleteProject(String id);

    // --- Worldbuilding / KnowledgeEntry ---
    void saveKnowledgeEntry(KnowledgeEntry entry);
    List<KnowledgeEntry> findKnowledgeEntriesByProject(String projectId);
    Optional<KnowledgeEntry> findKnowledgeEntry(String id);
    List<KnowledgeEntry> findKnowledgeEntriesByType(String projectId, String type);

    // --- Character ---
    String saveCharacter(Character character);
    Optional<Character> findCharacter(String id);
    List<Character> findCharactersByProject(String projectId);

    // --- Outline / Volume / Synopsis ---
    void saveOutline(Outline outline);
    Optional<Outline> findOutlineByProject(String projectId);

    // --- Chapter ---
    String saveChapter(Chapter chapter);
    Optional<Chapter> findChapter(String id);
    Optional<Chapter> findChapterByIndex(String projectId, int chapterIndex);
    List<Chapter> findChaptersByProject(String projectId);
    List<Chapter> findRecentChapters(String projectId, int count);

    // --- Summary ---
    void saveSummary(Summary summary);
    List<Summary> findSummariesByProject(String projectId);

    // --- Foreshadowing ---
    void saveForeshadowing(Foreshadowing f);
    List<Foreshadowing> findForeshadowingsByProject(String projectId);
    List<Foreshadowing> findUnresolvedForeshadowings(String projectId);

    // --- StyleProfile ---
    void saveStyleProfile(StyleProfile profile);
    Optional<StyleProfile> findStyleProfileByProject(String projectId);

    // --- ContinuityIssue ---
    void saveContinuityIssue(ContinuityIssue issue);
    List<ContinuityIssue> findContinuityIssuesByProject(String projectId);
    List<ContinuityIssue> findUnresolvedIssues(String projectId);

    // --- Checkpoint ---
    String saveCheckpoint(Checkpoint checkpoint);
    Optional<Checkpoint> findCheckpoint(String id);
    List<Checkpoint> findPendingCheckpoints(String projectId);
}
```

- [ ] **Step 2: 写契约测试基类（抽象，供实现测试继承）**

`src/test/java/com/writing/novel/spi/NovelRepositoryContractTest.java`:
```java
package com.writing.novel.spi;

import com.writing.novel.domain.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * NovelRepository 契约测试基类。任何实现都应继承此类，
 * 用相同断言验证行为一致（保证 SQLite/MySQL/Postgres 实现可互换）。
 */
public abstract class NovelRepositoryContractTest {

    /** 子类提供被测实例 */
    protected abstract NovelRepository repository();

    @Test
    void 项目的保存与查询() {
        Project p = new Project("契约测试", "简介", "玄幻");
        String id = repository().saveProject(p);

        Optional<Project> found = repository().findProject(id);
        assertTrue(found.isPresent());
        assertEquals("契约测试", found.get().getTitle());
    }

    @Test
    void 人物的保存与按项目查询() {
        Project p = new Project("t", "c", "tag");
        repository().saveProject(p);
        Character c = new Character(p.getId(), "李四", "配角");
        repository().saveCharacter(c);

        List<Character> chars = repository().findCharactersByProject(p.getId());
        assertEquals(1, chars.size());
        assertEquals("李四", chars.get(0).getName());
    }

    @Test
    void 章节按序号查询与最近章节() {
        Project p = new Project("t", "c", "tag");
        repository().saveProject(p);
        Chapter c1 = new Chapter(p.getId(), 1, "第一章");
        c1.setContent("内容一");
        Chapter c2 = new Chapter(p.getId(), 2, "第二章");
        c2.setContent("内容二");
        repository().saveChapter(c1);
        repository().saveChapter(c2);

        Optional<Chapter> byIndex = repository().findChapterByIndex(p.getId(), 2);
        assertTrue(byIndex.isPresent());
        assertEquals("第二章", byIndex.get().getTitle());

        List<Chapter> recent = repository().findRecentChapters(p.getId(), 1);
        assertEquals(1, recent.size());
        assertEquals(2, recent.get(0).getChapterIndex());
    }

    @Test
    void 伏笔未回收查询() {
        Project p = new Project("t", "c", "tag");
        repository().saveProject(p);
        Foreshadowing resolved = new Foreshadowing(p.getId(), "已回收", "ctx", 1);
        resolved.resolve(10);
        Foreshadowing open = new Foreshadowing(p.getId(), "未回收", "ctx", 2);
        repository().saveForeshadowing(resolved);
        repository().saveForeshadowing(open);

        List<Foreshadowing> pending = repository().findUnresolvedForeshadowings(p.getId());
        assertEquals(1, pending.size());
        assertEquals("未回收", pending.get(0).getDescription());
    }
}
```

- [ ] **Step 3: 确认编译通过**

Run: `mvn test-compile`
Expected: BUILD SUCCESS（接口与测试基类编译通过，尚无实现故不运行）

- [ ] **Step 4: 提交**

```bash
git add -A
git commit -m "feat(spi): NovelRepository 接口与契约测试基类"
```

---

## Task 7: SPI 接口 — VectorStore 与 EmbeddingProvider

**Files:**
- Create: `src/main/java/com/writing/novel/spi/VectorStore.java`
- Create: `src/main/java/com/writing/novel/spi/EmbeddingProvider.java`
- Create: `src/main/java/com/writing/novel/spi/VectorSearchResult.java`
- Test: `src/test/java/com/writing/novel/spi/VectorStoreContractTest.java`

- [ ] **Step 1: 定义 VectorSearchResult**

`src/main/java/com/writing/novel/spi/VectorSearchResult.java`:
```java
package com.writing.novel.spi;

/** 向量检索的单条结果 */
public record VectorSearchResult(String id, double score) {}
```

- [ ] **Step 2: 定义 EmbeddingProvider 接口**

`src/main/java/com/writing/novel/spi/EmbeddingProvider.java`:
```java
package com.writing.novel.spi;

/** 文本向量化 SPI。MVP 调云 API；可换本地模型。 */
public interface EmbeddingProvider {
    /** 将文本转为向量（浮点数组） */
    float[] embed(String text);

    /** 向量维度 */
    int dimension();
}
```

- [ ] **Step 3: 定义 VectorStore 接口**

`src/main/java/com/writing/novel/spi/VectorStore.java`:
```java
package com.writing.novel.spi;

import java.util.List;

/**
 * 向量存储与检索 SPI。
 * MVP 实现：EmbeddedVectorStore（本地文件）；云端：Qdrant/Milvus。
 */
public interface VectorStore {

    /**
     * 存入一个文本块及其向量。
     * @param id         唯一标识（如 chapterId / entryId）
     * @param projectId  所属项目（用于隔离检索范围）
     * @param text       原文（便于取回展示）
     * @param vector     向量
     */
    void upsert(String id, String projectId, String text, float[] vector);

    /** 删除 */
    void delete(String id);

    /**
     * 语义检索 top-K 最相似的文本块。
     * @param queryVector 查询向量
     * @param projectId   限定项目范围
     * @param topK        返回条数
     */
    List<VectorSearchResult> search(float[] queryVector, String projectId, int topK);

    /** 取回某 id 的原文 */
    String getText(String id);
}
```

- [ ] **Step 4: 写 VectorStore 契约测试基类**

`src/test/java/com/writing/novel/spi/VectorStoreContractTest.java`:
```java
package com.writing.novel.spi;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public abstract class VectorStoreContractTest {

    protected abstract VectorStore store();

    @Test
    void 存入并取回原文() {
        float[] v = {0.1f, 0.2f, 0.3f};
        store().upsert("c1", "proj-1", "玄黄大陆位于东方", v);
        assertEquals("玄黄大陆位于东方", store().getText("c1"));
    }

    @Test
    void 检索返回相似度排序结果() {
        store().upsert("e1", "proj-1", "剑修张三", new float[]{1.0f, 0.0f});
        store().upsert("e2", "proj-1", "丹师李四", new float[]{0.0f, 1.0f});

        List<VectorSearchResult> results = store().search(new float[]{1.0f, 0.0f}, "proj-1", 2);
        assertFalse(results.isEmpty());
        // 最相似的应为 e1
        assertEquals("e1", results.get(0).id());
        assertTrue(results.get(0).score() >= results.get(1).score());
    }

    @Test
    void 检索按项目隔离() {
        store().upsert("e1", "proj-1", "a", new float[]{1.0f});
        store().upsert("e2", "proj-2", "b", new float[]{1.0f});

        List<VectorSearchResult> results = store().search(new float[]{1.0f}, "proj-1", 10);
        assertEquals(1, results.size());
        assertEquals("e1", results.get(0).id());
    }
}
```

- [ ] **Step 5: 确认编译通过**

Run: `mvn test-compile`
Expected: BUILD SUCCESS

- [ ] **Step 6: 提交**

```bash
git add -A
git commit -m "feat(spi): VectorStore 与 EmbeddingProvider 接口及契约测试"
```

---

## Task 8: SQLite 实现 — Schema 与 ConnectionProvider

**Files:**
- Create: `src/main/java/com/writing/novel/persistence/sqlite/ConnectionProvider.java`
- Create: `src/main/java/com/writing/novel/persistence/sqlite/SqliteSchemaInitializer.java`
- Test: `src/test/java/com/writing/novel/persistence/sqlite/SqliteSchemaInitializerTest.java`

- [ ] **Step 1: 写失败测试 — schema 初始化后表存在**

`src/test/java/com/writing/novel/persistence/sqlite/SqliteSchemaInitializerTest.java`:
```java
package com.writing.novel.persistence.sqlite;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.*;

import static org.junit.jupiter.api.Assertions.*;

class SqliteSchemaInitializerTest {

    @TempDir
    Path tempDir;

    @Test
    void 初始化后所有表存在() throws SQLException {
        String dbPath = tempDir.resolve("test.db").toString();
        ConnectionProvider cp = new ConnectionProvider(dbPath);
        SqliteSchemaInitializer init = new SqliteSchemaInitializer(cp);
        init.initialize();

        try (Connection conn = cp.getConnection();
             Statement st = conn.createStatement()) {
            for (String table : new String[]{
                    "projects", "knowledge_entries", "characters",
                    "outlines", "volumes", "chapter_synopses",
                    "chapters", "summaries", "foreshadowings",
                    "style_profiles", "continuity_issues", "checkpoints"}) {
                ResultSet rs = st.executeQuery(
                        "SELECT name FROM sqlite_master WHERE type='table' AND name='" + table + "'");
                assertTrue(rs.next(), "表应存在: " + table);
            }
        }
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn test -Dtest=SqliteSchemaInitializerTest`
Expected: FAIL（类不存在）

- [ ] **Step 3: 创建 ConnectionProvider**

`src/main/java/com/writing/novel/persistence/sqlite/ConnectionProvider.java`:
```java
package com.writing.novel.persistence.sqlite;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/** SQLite 连接提供者。单连接 + 线程局部，适合本地单进程 MVP。 */
public class ConnectionProvider {
    private final String dbPath;

    public ConnectionProvider(String dbPath) {
        this.dbPath = dbPath;
    }

    public Connection getConnection() throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + dbPath);
    }

    public String getDbPath() { return dbPath; }
}
```

- [ ] **Step 4: 创建 SqliteSchemaInitializer**

`src/main/java/com/writing/novel/persistence/sqlite/SqliteSchemaInitializer.java`:
```java
package com.writing.novel.persistence.sqlite;

import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/** 启动时初始化 SQLite schema（如库不存在则建表）。 */
@Component
public class SqliteSchemaInitializer {

    private static final String[] DDL = {
        "CREATE TABLE IF NOT EXISTS projects (" +
            "id TEXT PRIMARY KEY, title TEXT, concept TEXT, tags TEXT, " +
            "mode TEXT, state TEXT, current_chapter_index INTEGER, " +
            "created_at TEXT, updated_at TEXT)",

        "CREATE TABLE IF NOT EXISTS knowledge_entries (" +
            "id TEXT PRIMARY KEY, project_id TEXT, type TEXT, name TEXT, " +
            "content TEXT, version INTEGER, updated_at TEXT)",

        "CREATE TABLE IF NOT EXISTS characters (" +
            "id TEXT PRIMARY KEY, project_id TEXT, name TEXT, role TEXT, " +
            "personality TEXT, ability TEXT, motivation TEXT, appearance TEXT, " +
            "relationships TEXT, growth_arc TEXT, version INTEGER, updated_at TEXT)",

        "CREATE TABLE IF NOT EXISTS outlines (" +
            "project_id TEXT PRIMARY KEY, premise TEXT)",

        "CREATE TABLE IF NOT EXISTS volumes (" +
            "id TEXT PRIMARY KEY, project_id TEXT, volume_index INTEGER, " +
            "title TEXT, summary TEXT)",

        "CREATE TABLE IF NOT EXISTS chapter_synopses (" +
            "id TEXT PRIMARY KEY, project_id TEXT, volume_id TEXT, " +
            "chapter_index INTEGER, title TEXT, synopsis TEXT, writing_notes TEXT)",

        "CREATE TABLE IF NOT EXISTS chapters (" +
            "id TEXT PRIMARY KEY, project_id TEXT, chapter_index INTEGER, " +
            "title TEXT, content TEXT, word_count INTEGER, version INTEGER, " +
            "created_at TEXT, updated_at TEXT)",

        "CREATE TABLE IF NOT EXISTS summaries (" +
            "id TEXT PRIMARY KEY, project_id TEXT, from_chapter INTEGER, " +
            "to_chapter INTEGER, content TEXT, updated_at TEXT)",

        "CREATE TABLE IF NOT EXISTS foreshadowings (" +
            "id TEXT PRIMARY KEY, project_id TEXT, description TEXT, " +
            "planted_context TEXT, planted_at_chapter INTEGER, " +
            "resolved INTEGER, resolved_at_chapter INTEGER, updated_at TEXT)",

        "CREATE TABLE IF NOT EXISTS style_profiles (" +
            "project_id TEXT PRIMARY KEY, tone TEXT, voice TEXT, " +
            "dos_and_donts TEXT, sample_chapter_indices TEXT, updated_at TEXT)",

        "CREATE TABLE IF NOT EXISTS continuity_issues (" +
            "id TEXT PRIMARY KEY, project_id TEXT, detected_at_chapter INTEGER, " +
            "type TEXT, severity TEXT, description TEXT, resolved INTEGER, created_at TEXT)",

        "CREATE TABLE IF NOT EXISTS checkpoints (" +
            "id TEXT PRIMARY KEY, project_id TEXT, type TEXT, " +
            "description TEXT, status TEXT, created_at TEXT)"
    };

    private final ConnectionProvider cp;

    public SqliteSchemaInitializer(ConnectionProvider cp) {
        this.cp = cp;
    }

    public void initialize() {
        try (Connection conn = cp.getConnection();
             Statement st = conn.createStatement()) {
            for (String ddl : DDL) {
                st.execute(ddl);
            }
        } catch (SQLException e) {
            throw new RuntimeException("SQLite schema 初始化失败", e);
        }
    }
}
```

- [ ] **Step 5: 运行测试确认通过**

Run: `mvn test -Dtest=SqliteSchemaInitializerTest`
Expected: PASS

- [ ] **Step 6: 提交**

```bash
git add -A
git commit -m "feat(persistence): SQLite ConnectionProvider 与 Schema 初始化"
```

---

## Task 9: SQLite 实现 — SqliteNovelRepository（核心 CRUD）

**Files:**
- Create: `src/main/java/com/writing/novel/persistence/sqlite/SqliteNovelRepository.java`
- Test: `src/test/java/com/writing/novel/persistence/sqlite/SqliteNovelRepositoryTest.java`

**说明:** 此 task 较大，将实现 Project/Character/Chapter/KnowledgeEntry 的 CRUD；其余实体（Outline/Summary 等）在 Task 10 补全。

- [ ] **Step 1: 写失败测试 — 继承契约测试**

`src/test/java/com/writing/novel/persistence/sqlite/SqliteNovelRepositoryTest.java`:
```java
package com.writing.novel.persistence.sqlite;

import com.writing.novel.spi.NovelRepository;
import com.writing.novel.spi.NovelRepositoryContractTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

class SqliteNovelRepositoryTest extends NovelRepositoryContractTest {

    @TempDir
    Path tempDir;
    private NovelRepository repository;

    @BeforeEach
    void setUp() {
        ConnectionProvider cp = new ConnectionProvider(tempDir.resolve("t.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        repository = new SqliteNovelRepository(cp);
    }

    @Override
    protected NovelRepository repository() {
        return repository;
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn test -Dtest=SqliteNovelRepositoryTest`
Expected: FAIL（SqliteNovelRepository 不存在）

- [ ] **Step 3: 实现 SqliteNovelRepository（先实现 Contract 测试覆盖的方法）**

`src/main/java/com/writing/novel/persistence/sqlite/SqliteNovelRepository.java`:
```java
package com.writing.novel.persistence.sqlite;

import com.writing.novel.domain.*;
import com.writing.novel.spi.NovelRepository;

import java.sql.*;
import java.time.Instant;
import java.util.*;

public class SqliteNovelRepository implements NovelRepository {

    private final ConnectionProvider cp;

    public SqliteNovelRepository(ConnectionProvider cp) {
        this.cp = cp;
    }

    @Override
    public String saveProject(Project p) {
        String sql = "INSERT INTO projects (id,title,concept,tags,mode,state,current_chapter_index,created_at,updated_at) " +
                "VALUES (?,?,?,?,?,?,?,?,?) " +
                "ON CONFLICT(id) DO UPDATE SET title=excluded.title, concept=excluded.concept, " +
                "tags=excluded.tags, mode=excluded.mode, state=excluded.state, " +
                "current_chapter_index=excluded.current_chapter_index, updated_at=excluded.updated_at";
        try (Connection conn = cp.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, p.getId());
            ps.setString(2, p.getTitle());
            ps.setString(3, p.getConcept());
            ps.setString(4, p.getTags());
            ps.setString(5, p.getMode().name());
            ps.setString(6, p.getState().name());
            ps.setInt(7, p.getCurrentChapterIndex());
            ps.setString(8, p.getCreatedAt().toString());
            ps.setString(9, p.getUpdatedAt().toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return p.getId();
    }

    @Override
    public Optional<Project> findProject(String id) {
        String sql = "SELECT * FROM projects WHERE id=?";
        try (Connection conn = cp.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, id);
            ResultSet rs = ps.executeQuery();
            if (!rs.next()) return Optional.empty();
            Project p = new Project(rs.getString("title"),
                    rs.getString("concept"), rs.getString("tags"));
            setField(p, "id", id);
            p.setMode(ProjectMode.valueOf(rs.getString("mode")));
            p.setState(NovelState.valueOf(rs.getString("state")));
            p.setCurrentChapterIndex(rs.getInt("current_chapter_index"));
            return Optional.of(p);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public List<Project> findAllProjects() {
        List<Project> list = new ArrayList<>();
        try (Connection conn = cp.getConnection();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT id FROM projects")) {
            while (rs.next()) {
                findProject(rs.getString("id")).ifPresent(list::add);
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return list;
    }

    @Override
    public void deleteProject(String id) {
        try (Connection conn = cp.getConnection();
             Statement st = conn.createStatement()) {
            for (String t : new String[]{"projects","knowledge_entries","characters","chapters",
                    "summaries","foreshadowings","style_profiles","continuity_issues","checkpoints"}) {
                st.execute("DELETE FROM " + t + " WHERE project_id='" + id.replace("'","''") + "'");
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void saveKnowledgeEntry(KnowledgeEntry e) {
        String sql = "INSERT INTO knowledge_entries (id,project_id,type,name,content,version,updated_at) " +
                "VALUES (?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET type=excluded.type, name=excluded.name, " +
                "content=excluded.content, version=excluded.version, updated_at=excluded.updated_at";
        try (Connection conn = cp.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, e.getId()); ps.setString(2, e.getProjectId());
            ps.setString(3, e.getType()); ps.setString(4, e.getName());
            ps.setString(5, e.getContent()); ps.setInt(6, e.getVersion());
            ps.setString(7, e.getUpdatedAt().toString());
            ps.executeUpdate();
        } catch (SQLException ex) { throw new RuntimeException(ex); }
    }

    @Override
    public List<KnowledgeEntry> findKnowledgeEntriesByProject(String projectId) {
        List<KnowledgeEntry> list = new ArrayList<>();
        try (Connection conn = cp.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT * FROM knowledge_entries WHERE project_id=?")) {
            ps.setString(1, projectId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) list.add(mapEntry(rs));
        } catch (SQLException e) { throw new RuntimeException(e); }
        return list;
    }

    @Override
    public Optional<KnowledgeEntry> findKnowledgeEntry(String id) {
        try (Connection conn = cp.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT * FROM knowledge_entries WHERE id=?")) {
            ps.setString(1, id);
            ResultSet rs = ps.executeQuery();
            return rs.next() ? Optional.of(mapEntry(rs)) : Optional.empty();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    @Override
    public List<KnowledgeEntry> findKnowledgeEntriesByType(String projectId, String type) {
        List<KnowledgeEntry> list = new ArrayList<>();
        try (Connection conn = cp.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT * FROM knowledge_entries WHERE project_id=? AND type=?")) {
            ps.setString(1, projectId); ps.setString(2, type);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) list.add(mapEntry(rs));
        } catch (SQLException e) { throw new RuntimeException(e); }
        return list;
    }

    @Override
    public String saveCharacter(Character c) {
        String sql = "INSERT INTO characters (id,project_id,name,role,personality,ability,motivation," +
                "appearance,relationships,growth_arc,version,updated_at) " +
                "VALUES (?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET " +
                "name=excluded.name, role=excluded.role, personality=excluded.personality, " +
                "ability=excluded.ability, motivation=excluded.motivation, appearance=excluded.appearance, " +
                "relationships=excluded.relationships, growth_arc=excluded.growth_arc, " +
                "version=excluded.version, updated_at=excluded.updated_at";
        try (Connection conn = cp.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, c.getId()); ps.setString(2, c.getProjectId());
            ps.setString(3, c.getName()); ps.setString(4, c.getRole());
            ps.setString(5, c.getPersonality()); ps.setString(6, c.getAbility());
            ps.setString(7, c.getMotivation()); ps.setString(8, c.getAppearance());
            ps.setString(9, c.getRelationships()); ps.setString(10, c.getGrowthArc());
            ps.setInt(11, c.getVersion()); ps.setString(12, c.getUpdatedAt().toString());
            ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
        return c.getId();
    }

    @Override
    public Optional<Character> findCharacter(String id) {
        try (Connection conn = cp.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT * FROM characters WHERE id=?")) {
            ps.setString(1, id);
            ResultSet rs = ps.executeQuery();
            return rs.next() ? Optional.of(mapCharacter(rs)) : Optional.empty();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    @Override
    public List<Character> findCharactersByProject(String projectId) {
        List<Character> list = new ArrayList<>();
        try (Connection conn = cp.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT * FROM characters WHERE project_id=?")) {
            ps.setString(1, projectId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) list.add(mapCharacter(rs));
        } catch (SQLException e) { throw new RuntimeException(e); }
        return list;
    }

    @Override
    public String saveChapter(Chapter c) {
        String sql = "INSERT INTO chapters (id,project_id,chapter_index,title,content,word_count," +
                "version,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?) " +
                "ON CONFLICT(id) DO UPDATE SET title=excluded.title, content=excluded.content, " +
                "word_count=excluded.word_count, version=excluded.version, updated_at=excluded.updated_at";
        try (Connection conn = cp.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, c.getId()); ps.setString(2, c.getProjectId());
            ps.setInt(3, c.getChapterIndex()); ps.setString(4, c.getTitle());
            ps.setString(5, c.getContent()); ps.setInt(6, c.getWordCount());
            ps.setInt(7, c.getVersion());
            ps.setString(8, c.getCreatedAt().toString());
            ps.setString(9, c.getUpdatedAt().toString());
            ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
        return c.getId();
    }

    @Override
    public Optional<Chapter> findChapter(String id) {
        try (Connection conn = cp.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT * FROM chapters WHERE id=?")) {
            ps.setString(1, id);
            ResultSet rs = ps.executeQuery();
            return rs.next() ? Optional.of(mapChapter(rs)) : Optional.empty();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    @Override
    public Optional<Chapter> findChapterByIndex(String projectId, int chapterIndex) {
        try (Connection conn = cp.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT * FROM chapters WHERE project_id=? AND chapter_index=?")) {
            ps.setString(1, projectId); ps.setInt(2, chapterIndex);
            ResultSet rs = ps.executeQuery();
            return rs.next() ? Optional.of(mapChapter(rs)) : Optional.empty();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    @Override
    public List<Chapter> findChaptersByProject(String projectId) {
        List<Chapter> list = new ArrayList<>();
        try (Connection conn = cp.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT * FROM chapters WHERE project_id=? ORDER BY chapter_index")) {
            ps.setString(1, projectId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) list.add(mapChapter(rs));
        } catch (SQLException e) { throw new RuntimeException(e); }
        return list;
    }

    @Override
    public List<Chapter> findRecentChapters(String projectId, int count) {
        List<Chapter> all = findChaptersByProject(projectId);
        if (all.size() <= count) return all;
        return all.subList(all.size() - count, all.size());
    }

    // --- 以下方法在 Task 10 实现 ---
    @Override public void saveOutline(Outline o) { throw new UnsupportedOperationException("Task 10"); }
    @Override public Optional<Outline> findOutlineByProject(String pid) { throw new UnsupportedOperationException("Task 10"); }
    @Override public void saveSummary(Summary s) { throw new UnsupportedOperationException("Task 10"); }
    @Override public List<Summary> findSummariesByProject(String pid) { throw new UnsupportedOperationException("Task 10"); }
    @Override public void saveForeshadowing(Foreshadowing f) { throw new UnsupportedOperationException("Task 10"); }
    @Override public List<Foreshadowing> findForeshadowingsByProject(String pid) { throw new UnsupportedOperationException("Task 10"); }
    @Override public List<Foreshadowing> findUnresolvedForeshadowings(String pid) { throw new UnsupportedOperationException("Task 10"); }
    @Override public void saveStyleProfile(StyleProfile p) { throw new UnsupportedOperationException("Task 10"); }
    @Override public Optional<StyleProfile> findStyleProfileByProject(String pid) { throw new UnsupportedOperationException("Task 10"); }
    @Override public void saveContinuityIssue(ContinuityIssue i) { throw new UnsupportedOperationException("Task 10"); }
    @Override public List<ContinuityIssue> findContinuityIssuesByProject(String pid) { throw new UnsupportedOperationException("Task 10"); }
    @Override public List<ContinuityIssue> findUnresolvedIssues(String pid) { throw new UnsupportedOperationException("Task 10"); }
    @Override public String saveCheckpoint(Checkpoint c) { throw new UnsupportedOperationException("Task 10"); }
    @Override public Optional<Checkpoint> findCheckpoint(String id) { throw new UnsupportedOperationException("Task 10"); }
    @Override public List<Checkpoint> findPendingCheckpoints(String pid) { throw new UnsupportedOperationException("Task 10"); }

    // --- 映射辅助 ---
    private KnowledgeEntry mapEntry(ResultSet rs) throws SQLException {
        KnowledgeEntry e = new KnowledgeEntry(rs.getString("project_id"),
                rs.getString("type"), rs.getString("name"), rs.getString("content"));
        setField(e, "id", rs.getString("id"));
        setField(e, "version", rs.getInt("version"));
        return e;
    }

    private Character mapCharacter(ResultSet rs) throws SQLException {
        Character c = new Character(rs.getString("project_id"),
                rs.getString("name"), rs.getString("role"));
        setField(c, "id", rs.getString("id"));
        c.setPersonality(rs.getString("personality"));
        c.setAbility(rs.getString("ability"));
        c.setMotivation(rs.getString("motivation"));
        c.setAppearance(rs.getString("appearance"));
        c.setRelationships(rs.getString("relationships"));
        c.setGrowthArc(rs.getString("growth_arc"));
        return c;
    }

    private Chapter mapChapter(ResultSet rs) throws SQLException {
        Chapter c = new Chapter(rs.getString("project_id"),
                rs.getInt("chapter_index"), rs.getString("title"));
        setField(c, "id", rs.getString("id"));
        String content = rs.getString("content");
        if (content != null) c.setContent(content);
        return c;
    }

    /** 用反射设置 id（领域对象构造时生成，从库读出需覆盖） */
    @SuppressWarnings("unchecked")
    private static void setField(Object target, String fieldName, Object value) {
        try {
            java.lang.reflect.Field f = target.getClass().getDeclaredField(fieldName);
            f.setAccessible(true);
            f.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException("无法设置字段 " + fieldName, e);
        }
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn test -Dtest=SqliteNovelRepositoryTest`
Expected: 契约测试中 Project/Character/Chapter/Foreshadowing 相关通过。
**注意：** Foreshadowing 测试会因 `saveForeshadowing` 抛 UnsupportedOperationException 而失败 —— 这是预期的，我们将在 Task 10 实现。先确认 Project/Character/Chapter 相关通过即可。
**修订：** Foreshadowing 契约测试在 Task 10 完成后才会通过。本步骤验证 Project/Character/Chapter 测试，Foreshadowing 那条暂挂。

- [ ] **Step 5: 提交**

```bash
git add -A
git commit -m "feat(persistence): SqliteNovelRepository 核心 CRUD（Project/Character/Chapter/Entry）"
```

---

## Task 10: SQLite 实现 — 补全剩余实体 CRUD

**Files:**
- Modify: `src/main/java/com/writing/novel/persistence/sqlite/SqliteNovelRepository.java`

- [ ] **Step 1: 实现 Outline/Volume/Synopsis 的存取**

替换 `SqliteNovelRepository` 中 `saveOutline` 和 `findOutlineByProject` 的 UnsupportedOperationException：

```java
    @Override
    public void saveOutline(Outline o) {
        try (Connection conn = cp.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO outlines (project_id, premise) VALUES (?,?) " +
                     "ON CONFLICT(project_id) DO UPDATE SET premise=excluded.premise")) {
            ps.setString(1, o.getProjectId()); ps.setString(2, o.getPremise());
            ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
        for (Volume v : o.getVolumes()) {
            saveVolume(conn0(), v);
        }
    }

    private Connection conn0() {
        try { return cp.getConnection(); } catch (SQLException e) { throw new RuntimeException(e); }
    }

    private void saveVolume(Connection conn, Volume v) {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO volumes (id,project_id,volume_index,title,summary) VALUES (?,?,?,?,?) " +
                "ON CONFLICT(id) DO UPDATE SET title=excluded.title, summary=excluded.summary")) {
            ps.setString(1, v.getId()); ps.setString(2, v.getProjectId());
            ps.setInt(3, v.getVolumeIndex()); ps.setString(4, v.getTitle());
            ps.setString(5, v.getSummary());
            ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
        for (ChapterSynopsis s : v.getSynopses()) {
            saveSynopsis(conn, v.getId(), s);
        }
    }

    private void saveSynopsis(Connection conn, String volumeId, ChapterSynopsis s) {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO chapter_synopses (id,project_id,volume_id,chapter_index,title,synopsis,writing_notes) " +
                "VALUES (?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET title=excluded.title, " +
                "synopsis=excluded.synopsis, writing_notes=excluded.writing_notes")) {
            ps.setString(1, s.getId()); ps.setString(2, s.getProjectId());
            ps.setString(3, volumeId); ps.setInt(4, s.getChapterIndex());
            ps.setString(5, s.getTitle()); ps.setString(6, s.getSynopsis());
            ps.setString(7, s.getWritingNotes());
            ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    @Override
    public Optional<Outline> findOutlineByProject(String projectId) {
        try (Connection conn = cp.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT premise FROM outlines WHERE project_id=?")) {
            ps.setString(1, projectId);
            ResultSet rs = ps.executeQuery();
            if (!rs.next()) return Optional.empty();
            Outline o = new Outline(projectId, rs.getString("premise"));
            try (PreparedStatement vps = conn.prepareStatement(
                    "SELECT * FROM volumes WHERE project_id=? ORDER BY volume_index")) {
                vps.setString(1, projectId);
                ResultSet vrs = vps.executeQuery();
                while (vrs.next()) {
                    Volume v = new Volume(projectId, vrs.getInt("volume_index"),
                            vrs.getString("title"), vrs.getString("summary"));
                    setField(v, "id", vrs.getString("id"));
                    try (PreparedStatement sps = conn.prepareStatement(
                            "SELECT * FROM chapter_synopses WHERE volume_id=? ORDER BY chapter_index")) {
                        sps.setString(1, v.getId());
                        ResultSet srs = sps.executeQuery();
                        while (srs.next()) {
                            ChapterSynopsis s = new ChapterSynopsis(projectId,
                                    srs.getInt("chapter_index"), srs.getString("title"),
                                    srs.getString("synopsis"));
                            setField(s, "id", srs.getString("id"));
                            s.setWritingNotes(srs.getString("writing_notes"));
                            v.addSynopsis(s);
                        }
                    }
                    o.addVolume(v);
                }
            }
            return Optional.of(o);
        } catch (SQLException e) { throw new RuntimeException(e); }
    }
```

- [ ] **Step 2: 实现 Summary 存取**

替换两个 UnsupportedOperationException：
```java
    @Override
    public void saveSummary(Summary s) {
        try (Connection conn = cp.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO summaries (id,project_id,from_chapter,to_chapter,content,updated_at) " +
                     "VALUES (?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET from_chapter=excluded.from_chapter, " +
                     "to_chapter=excluded.to_chapter, content=excluded.content, updated_at=excluded.updated_at")) {
            ps.setString(1, s.getId()); ps.setString(2, s.getProjectId());
            ps.setInt(3, s.getFromChapter()); ps.setInt(4, s.getToChapter());
            ps.setString(5, s.getContent()); ps.setString(6, s.getUpdatedAt().toString());
            ps.executeUpdate();
        } catch (SQLException e) { throw RuntimeException(e); }
    }

    @Override
    public List<Summary> findSummariesByProject(String projectId) {
        List<Summary> list = new ArrayList<>();
        try (Connection conn = cp.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT * FROM summaries WHERE project_id=? ORDER BY from_chapter")) {
            ps.setString(1, projectId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                Summary s = new Summary(projectId, rs.getInt("from_chapter"),
                        rs.getInt("to_chapter"), rs.getString("content"));
                setField(s, "id", rs.getString("id"));
                list.add(s);
            }
        } catch (SQLException e) { throw new RuntimeException(e); }
        return list;
    }
```

> ⚠️ 上方 `saveSummary` 有笔误：`throw RuntimeException(e)` 缺 `new`。修正为 `throw new RuntimeException(e);`。

- [ ] **Step 3: 实现 Foreshadowing 存取**

替换三个 UnsupportedOperationException：
```java
    @Override
    public void saveForeshadowing(Foreshadowing f) {
        try (Connection conn = cp.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO foreshadowings (id,project_id,description,planted_context," +
                     "planted_at_chapter,resolved,resolved_at_chapter,updated_at) VALUES (?,?,?,?,?,?,?,?) " +
                     "ON CONFLICT(id) DO UPDATE SET description=excluded.description, " +
                     "planted_context=excluded.planted_context, resolved=excluded.resolved, " +
                     "resolved_at_chapter=excluded.resolved_at_chapter, updated_at=excluded.updated_at")) {
            ps.setString(1, f.getId()); ps.setString(2, f.getProjectId());
            ps.setString(3, f.getDescription()); ps.setString(4, f.getPlantedContext());
            ps.setInt(5, f.getPlantedAtChapter());
            ps.setInt(6, f.isResolved() ? 1 : 0);
            if (f.getResolvedAtChapter() != null) ps.setInt(7, f.getResolvedAtChapter());
            else ps.setNull(7, Types.INTEGER);
            ps.setString(8, f.getUpdatedAt().toString());
            ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    @Override
    public List<Foreshadowing> findForeshadowingsByProject(String projectId) {
        List<Foreshadowing> list = new ArrayList<>();
        try (Connection conn = cp.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT * FROM foreshadowings WHERE project_id=?")) {
            ps.setString(1, projectId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) list.add(mapForeshadowing(rs));
        } catch (SQLException e) { throw new RuntimeException(e); }
        return list;
    }

    @Override
    public List<Foreshadowing> findUnresolvedForeshadowings(String projectId) {
        List<Foreshadowing> list = new ArrayList<>();
        try (Connection conn = cp.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT * FROM foreshadowings WHERE project_id=? AND resolved=0")) {
            ps.setString(1, projectId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) list.add(mapForeshadowing(rs));
        } catch (SQLException e) { throw new RuntimeException(e); }
        return list;
    }

    private Foreshadowing mapForeshadowing(ResultSet rs) throws SQLException {
        Foreshadowing f = new Foreshadowing(rs.getString("project_id"),
                rs.getString("description"), rs.getString("planted_context"),
                rs.getInt("planted_at_chapter"));
        setField(f, "id", rs.getString("id"));
        if (rs.getInt("resolved") == 1) f.resolve(rs.getInt("resolved_at_chapter"));
        return f;
    }
```

- [ ] **Step 4: 实现 StyleProfile 存取**

替换两个 UnsupportedOperationException：
```java
    @Override
    public void saveStyleProfile(StyleProfile p) {
        try (Connection conn = cp.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO style_profiles (project_id,tone,voice,dos_and_donts,sample_chapter_indices,updated_at) " +
                     "VALUES (?,?,?,?,?,?) ON CONFLICT(project_id) DO UPDATE SET tone=excluded.tone, " +
                     "voice=excluded.voice, dos_and_donts=excluded.dos_and_donts, " +
                     "sample_chapter_indices=excluded.sample_chapter_indices, updated_at=excluded.updated_at")) {
            ps.setString(1, p.getProjectId()); ps.setString(2, p.getTone());
            ps.setString(3, p.getVoice()); ps.setString(4, p.getDosAndDonts());
            ps.setString(5, String.join(",", p.getSampleChapterIndices().stream()
                    .map(String::valueOf).toList()));
            ps.setString(6, p.getUpdatedAt().toString());
            ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    @Override
    public Optional<StyleProfile> findStyleProfileByProject(String projectId) {
        try (Connection conn = cp.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT * FROM style_profiles WHERE project_id=?")) {
            ps.setString(1, projectId);
            ResultSet rs = ps.executeQuery();
            if (!rs.next()) return Optional.empty();
            StyleProfile p = new StyleProfile(projectId);
            p.setTone(rs.getString("tone"));
            p.setVoice(rs.getString("voice"));
            p.setDosAndDonts(rs.getString("dos_and_donts"));
            String indices = rs.getString("sample_chapter_indices");
            if (indices != null && !indices.isEmpty()) {
                for (String s : indices.split(",")) {
                    if (!s.isEmpty()) p.addSampleChapter(Integer.parseInt(s.trim()));
                }
            }
            return Optional.of(p);
        } catch (SQLException e) { throw new RuntimeException(e); }
    }
```

- [ ] **Step 5: 实现 ContinuityIssue 存取**

替换三个 UnsupportedOperationException：
```java
    @Override
    public void saveContinuityIssue(ContinuityIssue i) {
        try (Connection conn = cp.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO continuity_issues (id,project_id,detected_at_chapter,type,severity,description,resolved,created_at) " +
                     "VALUES (?,?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET resolved=excluded.resolved")) {
            ps.setString(1, i.getId()); ps.setString(2, i.getProjectId());
            ps.setInt(3, i.getDetectedAtChapter());
            ps.setString(4, i.getType().name()); ps.setString(5, i.getSeverity().name());
            ps.setString(6, i.getDescription());
            ps.setInt(7, i.isResolved() ? 1 : 0);
            ps.setString(8, i.getCreatedAt().toString());
            ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    @Override
    public List<ContinuityIssue> findContinuityIssuesByProject(String projectId) {
        List<ContinuityIssue> list = new ArrayList<>();
        try (Connection conn = cp.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT * FROM continuity_issues WHERE project_id=? ORDER BY created_at DESC")) {
            ps.setString(1, projectId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) list.add(mapIssue(rs));
        } catch (SQLException e) { throw new RuntimeException(e); }
        return list;
    }

    @Override
    public List<ContinuityIssue> findUnresolvedIssues(String projectId) {
        List<ContinuityIssue> list = new ArrayList<>();
        try (Connection conn = cp.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT * FROM continuity_issues WHERE project_id=? AND resolved=0")) {
            ps.setString(1, projectId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) list.add(mapIssue(rs));
        } catch (SQLException e) { throw new RuntimeException(e); }
        return list;
    }

    private ContinuityIssue mapIssue(ResultSet rs) throws SQLException {
        ContinuityIssue i = new ContinuityIssue(rs.getString("project_id"),
                rs.getInt("detected_at_chapter"),
                ContinuityIssue.Type.valueOf(rs.getString("type")),
                ContinuityIssue.Severity.valueOf(rs.getString("severity")),
                rs.getString("description"));
        setField(i, "id", rs.getString("id"));
        if (rs.getInt("resolved") == 1) i.resolve();
        return i;
    }
```

- [ ] **Step 6: 实现 Checkpoint 存取**

替换三个 UnsupportedOperationException：
```java
    @Override
    public String saveCheckpoint(Checkpoint c) {
        try (Connection conn = cp.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO checkpoints (id,project_id,type,description,status,created_at) " +
                     "VALUES (?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET status=excluded.status")) {
            ps.setString(1, c.getId()); ps.setString(2, c.getProjectId());
            ps.setString(3, c.getType().name()); ps.setString(4, c.getDescription());
            ps.setString(5, c.getStatus().name());
            ps.setString(6, c.getCreatedAt().toString());
            ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
        return c.getId();
    }

    @Override
    public Optional<Checkpoint> findCheckpoint(String id) {
        try (Connection conn = cp.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT * FROM checkpoints WHERE id=?")) {
            ps.setString(1, id);
            ResultSet rs = ps.executeQuery();
            return rs.next() ? Optional.of(mapCheckpoint(rs)) : Optional.empty();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    @Override
    public List<Checkpoint> findPendingCheckpoints(String projectId) {
        List<Checkpoint> list = new ArrayList<>();
        try (Connection conn = cp.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT * FROM checkpoints WHERE project_id=? AND status='PENDING' ORDER BY created_at")) {
            ps.setString(1, projectId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) list.add(mapCheckpoint(rs));
        } catch (SQLException e) { throw new RuntimeException(e); }
        return list;
    }

    private Checkpoint mapCheckpoint(ResultSet rs) throws SQLException {
        Checkpoint c = new Checkpoint(rs.getString("project_id"),
                Checkpoint.Type.valueOf(rs.getString("type")),
                rs.getString("description"));
        setField(c, "id", rs.getString("id"));
        switch (Checkpoint.Status.valueOf(rs.getString("status"))) {
            case APPROVED -> c.approve();
            case MODIFIED -> c.modify();
            case REJECTED -> c.reject();
        }
        return c;
    }
```

- [ ] **Step 7: 运行全部契约测试确认通过**

Run: `mvn test -Dtest=SqliteNovelRepositoryTest`
Expected: 全部 4 个契约测试 PASS（包括之前挂起的 Foreshadowing 测试）

- [ ] **Step 8: 提交**

```bash
git add -A
git commit -m "feat(persistence): 补全 SQLite 实现（大纲/摘要/伏笔/风格/连续性/检查点）"
```

---

## Task 11: 嵌入式向量库实现 — EmbeddedVectorStore

**Files:**
- Create: `src/main/java/com/writing/novel/persistence/embedding/EmbeddedVectorStore.java`
- Test: `src/test/java/com/writing/novel/persistence/embedding/EmbeddedVectorStoreTest.java`

**说明:** MVP 用内存 + 文件持久化的暴力余弦相似度检索。数据量在 MVP 范围内（几千条）足够，云端换 Qdrant/Milvus。

- [ ] **Step 1: 写失败测试 — 继承契约测试**

`src/test/java/com/writing/novel/persistence/embedding/EmbeddedVectorStoreTest.java`:
```java
package com.writing.novel.persistence.embedding;

import com.writing.novel.spi.VectorStore;
import com.writing.novel.spi.VectorStoreContractTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

class EmbeddedVectorStoreTest extends VectorStoreContractTest {

    @TempDir
    Path tempDir;
    private VectorStore store;

    @BeforeEach
    void setUp() {
        store = new EmbeddedVectorStore(tempDir.resolve("vecs.idx").toString());
    }

    @Override
    protected VectorStore store() {
        return store;
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn test -Dtest=EmbeddedVectorStoreTest`
Expected: FAIL（类不存在）

- [ ] **Step 3: 实现 EmbeddedVectorStore**

`src/main/java/com/writing/novel/persistence/embedding/EmbeddedVectorStore.java`:
```java
package com.writing.novel.persistence.embedding;

import com.writing.novel.spi.VectorSearchResult;
import com.writing.novel.spi.VectorStore;

import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 嵌入式本地向量库实现。
 * 内存索引 + 文件持久化，暴力余弦相似度检索。
 * MVP 足够；云端部署换 Qdrant/Milvus 实现。
 */
public class EmbeddedVectorStore implements VectorStore {

    /** 单条记录 */
    private record Entry(String id, String projectId, String text, float[] vector) {}

    private final String indexPath;
    private final Map<String, Entry> index = new ConcurrentHashMap<>();

    public EmbeddedVectorStore(String indexPath) {
        this.indexPath = indexPath;
        load();
    }

    @Override
    public synchronized void upsert(String id, String projectId, String text, float[] vector) {
        index.put(id, new Entry(id, projectId, text, vector.clone()));
        persist();
    }

    @Override
    public synchronized void delete(String id) {
        index.remove(id);
        persist();
    }

    @Override
    public List<VectorSearchResult> search(float[] queryVector, String projectId, int topK) {
        List<VectorSearchResult> results = new ArrayList<>();
        for (Entry e : index.values()) {
            if (!e.projectId.equals(projectId)) continue;
            double score = cosineSimilarity(queryVector, e.vector);
            results.add(new VectorSearchResult(e.id, score));
        }
        results.sort((a, b) -> Double.compare(b.score(), a.score()));
        return results.size() <= topK ? results : results.subList(0, topK);
    }

    @Override
    public String getText(String id) {
        Entry e = index.get(id);
        return e == null ? null : e.text;
    }

    /** 余弦相似度 */
    private static double cosineSimilarity(float[] a, float[] b) {
        if (a.length != b.length) return 0.0;
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        if (normA == 0 || normB == 0) return 0.0;
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    // --- 文件持久化 ---
    @SuppressWarnings("unchecked")
    private void load() {
        File f = new File(indexPath);
        if (!f.exists()) return;
        try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(f))) {
            List<Entry> list = (List<Entry>) ois.readObject();
            for (Entry e : list) index.put(e.id(), e);
        } catch (Exception e) {
            throw new RuntimeException("向量索引加载失败: " + indexPath, e);
        }
    }

    private void persist() {
        File f = new File(indexPath);
        f.getParentFile().mkdirs();
        try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(f))) {
            oos.writeObject(new ArrayList<>(index.values()));
        } catch (IOException e) {
            throw new RuntimeException("向量索引持久化失败", e);
        }
    }
}
```

> ⚠️ `Entry` record 实现了 Serializable 的需求：record 的组件需可序列化。`float[]` 可序列化，String 可序列化。但为安全，持久化时 `new ArrayList<>(index.values())` 包含 Entry 对象——Entry record 默认可序列化只要组件可序列化。若反序列化报错，可在测试中验证。

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn test -Dtest=EmbeddedVectorStoreTest`
Expected: 3 个契约测试 PASS

- [ ] **Step 5: 追加持久化测试 — 重启后数据保留**

在 `EmbeddedVectorStoreTest` 中追加：
```java
    @Test
    void 重启后数据保留() {
        store().upsert("x1", "p1", "文本A", new float[]{1.0f, 0.0f});
        // 用同一路径重新加载
        EmbeddedVectorStore reloaded = new EmbeddedVectorStore(indexPath.toString());
        assertEquals("文本A", reloaded.getText("x1"));
    }
```

需要在文件顶部加 `import org.junit.jupiter.api.Test;` 和 `import static org.junit.jupiter.api.Assertions.*;`（如未有）。注意 `@TempDir Path tempDir;` 字段在测试方法间共享，`indexPath` 引用该字段。

Run: `mvn test -Dtest=EmbeddedVectorStoreTest`
Expected: 4 个测试 PASS

- [ ] **Step 6: 提交**

```bash
git add -A
git commit -m "feat(persistence): EmbeddedVectorStore 嵌入式向量库（余弦相似度+文件持久化）"
```

---

## Task 12: EmbeddingProvider 占位实现与配置装配

**Files:**
- Create: `src/main/java/com/writing/novel/persistence/embedding/StubEmbeddingProvider.java`
- Create: `src/main/java/com/writing/novel/config/PersistenceConfig.java`
- Create: `src/main/java/com/writing/novel/config/PersistenceProperties.java`
- Test: `src/test/java/com/writing/novel/config/PersistenceConfigTest.java`

**说明:** 真实云 API embedding 将在 Plan 2（LLM Gateway）接入。本 task 提供一个确定性 Stub 实现（基于文本哈希生成固定维度向量），保证记忆层测试可独立运行；同时完成配置装配，按 `application.yml` 选择实现。

- [ ] **Step 1: 写失败测试 — 配置装配按 profile 选择实现**

`src/test/java/com/writing/novel/config/PersistenceConfigTest.java`:
```java
package com.writing.novel.config;

import com.writing.novel.persistence.embedding.EmbeddedVectorStore;
import com.writing.novel.persistence.sqlite.SqliteNovelRepository;
import com.writing.novel.spi.NovelRepository;
import com.writing.novel.spi.VectorStore;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class PersistenceConfigTest {

    @Test
    void 默认装配SQLite与嵌入式向量库() {
        // 默认 application.yml: relational=sqlite, vector=embedded
        assertNotNull(context.getBean(NovelRepository.class));
        assertTrue(context.getBean(NovelRepository.class) instanceof SqliteNovelRepository);
        assertTrue(context.getBean(VectorStore.class) instanceof EmbeddedVectorStore);
    }

    @org.springframework.beans.factory.annotation.Autowired
    org.springframework.context.ApplicationContext context;
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn test -Dtest=PersistenceConfigTest`
Expected: FAIL（无配置类装配 Bean）

- [ ] **Step 3: 创建 PersistenceProperties（绑定 yml）**

`src/main/java/com/writing/novel/config/PersistenceProperties.java`:
```java
package com.writing.novel.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "persistence")
public class PersistenceProperties {
    private String relational = "sqlite";
    private String vector = "embedded";
    private String embedding = "cloud-api";
    private Sqlite sqlite = new Sqlite();
    private VectorStoreCfg vectorStore = new VectorStoreCfg();
    private EmbeddingCfg embeddingCfg = new EmbeddingCfg();

    public static class Sqlite {
        private String dbPath = "./data/novel.db";
        public String getDbPath() { return dbPath; }
        public void setDbPath(String p) { this.dbPath = p; }
    }
    public static class VectorStoreCfg {
        private String indexPath = "./data/vectors.idx";
        public String getIndexPath() { return indexPath; }
        public void setIndexPath(String p) { this.indexPath = p; }
    }
    public static class EmbeddingCfg {
        private String apiBaseUrl;
        private String apiKey;
        private String model = "text-embedding-v2";
        public String getApiBaseUrl() { return apiBaseUrl; }
        public void setApiBaseUrl(String u) { this.apiBaseUrl = u; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String k) { this.apiKey = k; }
        public String getModel() { return model; }
        public void setModel(String m) { this.model = m; }
    }

    public String getRelational() { return relational; }
    public void setRelational(String r) { this.relational = r; }
    public String getVector() { return vector; }
    public void setVector(String v) { this.vector = v; }
    public String getEmbedding() { return embedding; }
    public void setEmbedding(String e) { this.embedding = e; }
    public Sqlite getSqlite() { return sqlite; }
    public void setSqlite(Sqlite s) { this.sqlite = s; }
    public VectorStoreCfg getVectorStore() { return vectorStore; }
    public void setVectorStore(VectorStoreCfg v) { this.vectorStore = v; }
    public EmbeddingCfg getEmbeddingCfg() { return embeddingCfg; }
    public void setEmbeddingCfg(EmbeddingCfg e) { this.embeddingCfg = e; }
}
```

- [ ] **Step 4: 创建 StubEmbeddingProvider**

`src/main/java/com/writing/novel/persistence/embedding/StubEmbeddingProvider.java`:
```java
package com.writing.novel.persistence.embedding;

import com.writing.novel.spi.EmbeddingProvider;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * 确定性的占位 Embedding 实现。
 * 基于文本字节哈希生成固定 64 维向量，仅用于测试与离线开发。
 * 真实实现在 Plan 2（LLM Gateway）接入云 API。
 */
public class StubEmbeddingProvider implements EmbeddingProvider {

    private static final int DIM = 64;

    @Override
    public float[] embed(String text) {
        float[] v = new float[DIM];
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < bytes.length; i++) {
            v[i % DIM] += bytes[i];
        }
        // 归一化
        double norm = Arrays.stream(v).map(Math::abs).sum();
        if (norm == 0) return v;
        for (int i = 0; i < DIM; i++) v[i] /= norm;
        return v;
    }

    @Override
    public int dimension() { return DIM; }
}
```

- [ ] **Step 5: 创建 PersistenceConfig（按配置装配 Bean）**

`src/main/java/com/writing/novel/config/PersistenceConfig.java`:
```java
package com.writing.novel.config;

import com.writing.novel.persistence.embedding.EmbeddedVectorStore;
import com.writing.novel.persistence.embedding.StubEmbeddingProvider;
import com.writing.novel.persistence.sqlite.ConnectionProvider;
import com.writing.novel.persistence.sqlite.SqliteNovelRepository;
import com.writing.novel.persistence.sqlite.SqliteSchemaInitializer;
import com.writing.novel.spi.EmbeddingProvider;
import com.writing.novel.spi.NovelRepository;
import com.writing.novel.spi.VectorStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;

@Configuration
public class PersistenceConfig {

    // --- SQLite ---
    @Bean
    @ConditionalOnProperty(name = "persistence.relational", havingValue = "sqlite", matchIfMissing = true)
    public ConnectionProvider sqliteConnectionProvider(PersistenceProperties props) {
        return new ConnectionProvider(props.getSqlite().getDbPath());
    }

    @Bean
    @ConditionalOnProperty(name = "persistence.relational", havingValue = "sqlite", matchIfMissing = true)
    public SqliteSchemaInitializer sqliteSchemaInitializer(ConnectionProvider cp) {
        return new SqliteSchemaInitializer(cp);
    }

    @Bean
    @ConditionalOnProperty(name = "persistence.relational", havingValue = "sqlite", matchIfMissing = true)
    public NovelRepository sqliteNovelRepository(ConnectionProvider cp,
                                                  SqliteSchemaInitializer schemaInit) {
        schemaInit.initialize();
        return new SqliteNovelRepository(cp);
    }

    // --- Embedded VectorStore ---
    @Bean
    @ConditionalOnProperty(name = "persistence.vector", havingValue = "embedded", matchIfMissing = true)
    public VectorStore embeddedVectorStore(PersistenceProperties props) {
        return new EmbeddedVectorStore(props.getVectorStore().getIndexPath());
    }

    // --- Embedding: 默认用 Stub（真实云 API 在 Plan 2 接入） ---
    @Bean
    @ConditionalOnProperty(name = "persistence.embedding", havingValue = "stub", matchIfMissing = false)
    public EmbeddingProvider stubEmbeddingProvider() {
        return new StubEmbeddingProvider();
    }
}
```

- [ ] **Step 6: 修改 application.yml 默认 embedding 为 stub（Plan 2 前用）**

修改 `src/main/resources/application.yml`，将 embedding 默认改为 stub：
```yaml
persistence:
  relational: sqlite
  vector: embedded
  embedding: stub        # Plan 2 接入云 API 前用 stub
  sqlite:
    db-path: ./data/novel.db
  vector-store:
    index-path: ./data/vectors.idx
  embedding:
    api-base-url: ${EMBEDDING_API_URL:}
    api-key: ${EMBEDDING_API_KEY:}
    model: ${EMBEDDING_MODEL:text-embedding-v2}
```

- [ ] **Step 7: 运行测试确认通过**

Run: `mvn test -Dtest=PersistenceConfigTest`
Expected: PASS（默认装配 SQLite + EmbeddedVectorStore）

- [ ] **Step 8: 运行全部测试确认无回归**

Run: `mvn test`
Expected: 全部测试 PASS

- [ ] **Step 9: 提交**

```bash
git add -A
git commit -m "feat(config): PersistenceConfig 按 yml 装配 SPI 实现 + Stub Embedding"
```

---

## Task 13: 端到端冒烟测试 — 持久化全链路

**Files:**
- Test: `src/test/java/com/writing/novel/PersistenceSmokeTest.java`

**目标:** 验证通过 Spring 装配的 Bean 能完成「创建项目→存设定→存章节→存摘要→查询」全链路，证明地基稳固。

- [ ] **Step 1: 写冒烟测试**

`src/test/java/com/writing/novel/PersistenceSmokeTest.java`:
```java
package com.writing.novel;

import com.writing.novel.domain.*;
import com.writing.novel.spi.NovelRepository;
import com.writing.novel.spi.VectorStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@TestPropertySource(properties = {
        "persistence.sqlite.db-path=./data/test-smoke.db",
        "persistence.vector-store.index-path=./data/test-smoke-vecs.idx"
})
class PersistenceSmokeTest {

    @Autowired NovelRepository repo;
    @Autowired VectorStore vectors;

    @Test
    void 全链路存取() {
        // 1. 创建项目
        Project p = new Project("冒烟测试", "剑修复仇的故事", "玄幻,热血");
        repo.saveProject(p);

        // 2. 世界观
        repo.saveKnowledgeEntry(new KnowledgeEntry(p.getId(), "POWER_SYSTEM",
                "灵气体系", "修炼者吸收天地灵气。"));
        repo.saveKnowledgeEntry(new KnowledgeEntry(p.getId(), "GEOGRAPHY",
                "玄黄大陆", "故事主大陆。"));

        // 3. 人物
        Character mc = new Character(p.getId(), "张三", "主角");
        mc.setPersonality("火爆");
        mc.setAbility("剑修");
        repo.saveCharacter(mc);

        // 4. 章节
        Chapter c1 = new Chapter(p.getId(), 1, "第一章");
        c1.setContent("张三踏上复仇之路。");
        repo.saveChapter(c1);

        // 5. 摘要
        repo.saveSummary(new Summary(p.getId(), 1, 1, "主角踏上复仇之路。"));

        // 6. 向量
        vectors.upsert("mc-char", p.getId(), "主角张三火爆剑修", new float[]{1.0f});

        // --- 验证查询 ---
        Project found = repo.findProject(p.getId()).orElseThrow();
        assertEquals("冒烟测试", found.getTitle());

        List<KnowledgeEntry> entries = repo.findKnowledgeEntriesByProject(p.getId());
        assertEquals(2, entries.size());

        List<Character> chars = repo.findCharactersByProject(p.getId());
        assertEquals(1, chars.size());
        assertEquals("火爆", chars.get(0).getPersonality());

        List<Chapter> chapters = repo.findChaptersByProject(p.getId());
        assertEquals(1, chapters.size());

        List<Summary> summaries = repo.findSummariesByProject(p.getId());
        assertEquals(1, summaries.size());

        assertEquals("主角张三火爆剑修", vectors.getText("mc-char"));
    }
}
```

- [ ] **Step 2: 运行确认通过**

Run: `mvn test -Dtest=PersistenceSmokeTest`
Expected: PASS

- [ ] **Step 3: 运行全量测试**

Run: `mvn clean test`
Expected: 全部测试 PASS（domain + persistence + config + smoke）

- [ ] **Step 4: 提交**

```bash
git add -A
git commit -m "test: 持久化全链路冒烟测试"
```

- [ ] **Step 5: 验收检查 — mvn verify**

Run: `mvn clean verify`
Expected: BUILD SUCCESS

```bash
git add -A
git commit -m "chore: Plan 1 完成验收" --allow-empty
```

---

## 自审记录

**Spec 覆盖检查（对照 spec 第 3 节持久化 SPI）：**
- ✅ 三个 SPI 接口（NovelRepository / VectorStore / EmbeddingProvider）— Task 6, 7
- ✅ SQLite 实现 — Task 8, 9, 10
- ✅ 嵌入式向量库实现 — Task 11
- ✅ EmbeddingProvider 占位实现 — Task 12（真实云 API 留 Plan 2）
- ✅ 配置切换机制 — Task 12

**Spec 覆盖检查（对照 spec 第 4 节领域对象）：**
- ✅ Project + 模式 + 状态 — Task 2
- ✅ Worldbuilding / KnowledgeEntry / Character — Task 3
- ✅ Outline / Volume / ChapterSynopsis / Chapter / Foreshadowing — Task 4
- ✅ Summary / StyleProfile / ContinuityIssue / Checkpoint — Task 5

**未覆盖项（明确属于后续 Plan）：**
- LLM Gateway 与云 Embedding（→ Plan 2）
- 三层记忆的服务层与上下文组装（→ Plan 3）
- 7 个 Agent（→ Plan 4）
- Orchestrator 状态机（→ Plan 5）
- REST API（→ Plan 6）

**类型一致性：** 各领域对象的属性/方法在 Task 间引用一致（Character.getName/getPersonality、Chapter.getContent/getWordCount 等），SPI 方法签名在实现与测试间一致。✅

---

## 后续 Plan 预告

| Plan | 内容 | 依赖 |
|------|------|------|
| **Plan 2** | LLM Gateway + 多模型路由 + 真实 Embedding 云 API | Plan 1 |
| **Plan 3** | 三层记忆层（知识库服务/摘要服务/向量检索 + 上下文组装） | Plan 1, 2 |
| **Plan 4** | 7 个 Agent 实现 | Plan 2, 3 |
| **Plan 5** | Orchestrator 状态机 + 事件调度 + Solo/交互/checkpoint | Plan 3, 4 |
| **Plan 6** | REST API + SSE 层 | Plan 3, 4, 5 |
