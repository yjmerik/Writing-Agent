# Plan 3: 三层记忆层 + 上下文组装

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 构建三层记忆服务（结构化知识库 + 滚动摘要 + 向量检索），并实现「上下文组装器」——把风格锚点、世界观、近期摘要、检索到的相关人物/设定/伏笔拼装成 ChapterWriter 所需的完整 prompt 上下文。这是长篇一致性的核心。

**Architecture:** 三个记忆服务各自封装一层记忆的读写，`ContextAssembler` 是它们的消费者——按 spec 第 5 节的公式组装写作上下文。所有服务依赖 Plan 1 的 SPI（NovelRepository + VectorStore + EmbeddingProvider），不依赖 LLM（摘要生成在 Plan 4 的 MemoryManager Agent 里，本 Plan 只做"读取与组装"）。摘要的"写入"由 Agent 触发，本 Plan 提供 Service 的写入接口。

**Tech Stack:** Spring Boot 3, Java 17, 依赖 Plan 1 的 SPI。

**Spec 参考:** `docs/superpowers/specs/2026-07-10-novel-writing-system-design.md` 第 5 节（三层记忆策略 + 上下文组装公式）。

**依赖:** Plan 1（NovelRepository / VectorStore / EmbeddingProvider）, Plan 2（LlmGateway，本 Plan 仅在索引服务中用于 embedding）。

---

## 文件结构

```
src/main/java/com/writing/novel/memory/            # 三层记忆 + 上下文组装
├── KnowledgeBaseService.java          # 第一层：结构化知识库（CRUD + 按类型/项目查询）
├── SummaryService.java                # 第二层：滚动摘要（读 + 写入接口）
├── VectorRetrievalService.java        # 第三层：向量检索（向量化文本 + 语义召回）
├── ContextAssembler.java              # 上下文组装器（核心！把三层 + 风格拼成写作 prompt）
├── WritingContext.java                # 组装结果 DTO（结构化的上下文各部分）
├── IndexedText.java                   # 待索引文本块（id/projectId/text/sourceType）
├── MemoryConfig.java                  # Bean 装配
```

测试：
```
src/test/java/com/writing/novel/memory/
├── KnowledgeBaseServiceTest.java
├── SummaryServiceTest.java
├── VectorRetrievalServiceTest.java
└── ContextAssemblerTest.java          # 核心：验证组装公式
```

---

## Task 1: IndexedText DTO + VectorRetrievalService（第三层：向量检索）

**Files:**
- Create: `src/main/java/com/writing/novel/memory/IndexedText.java`
- Create: `src/main/java/com/writing/novel/memory/VectorRetrievalService.java`
- Test: `src/test/java/com/writing/novel/memory/VectorRetrievalServiceTest.java`

**说明:** 向量检索层。向量化文本时用 EmbeddingProvider 生成向量存入 VectorStore；检索时也用 EmbeddingProvider 把查询文本向量化，再 search。这层封装了"文本→向量→存储/检索"的完整流程，上层不直接碰 VectorStore/EmbeddingProvider。

- [ ] **Step 1: 写失败测试**

`src/test/java/com/writing/novel/memory/VectorRetrievalServiceTest.java`:
```java
package com.writing.novel.memory;

import com.writing.novel.spi.EmbeddingProvider;
import com.writing.novel.spi.VectorSearchResult;
import com.writing.novel.spi.VectorStore;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class VectorRetrievalServiceTest {

    /** 简单确定性 embedding：把文本首字符的 ASCII 值放到向量第一维 */
    private EmbeddingProvider simpleEmbedding() {
        return new EmbeddingProvider() {
            @Override
            public float[] embed(String text) {
                float[] v = new float[2];
                v[0] = text.isEmpty() ? 0 : text.charAt(0);
                v[1] = text.length();
                return v;
            }
            @Override
            public int dimension() { return 2; }
        };
    }

    @Test
    void 索引文本后可按语义检索() {
        VectorStore store = new com.writing.novel.persistence.embedding.EmbeddedVectorStore(
                java.nio.file.Files.createTempFile("vec", ".idx").toString());
        VectorRetrievalService svc = new VectorRetrievalService(store, simpleEmbedding());

        svc.index(new IndexedText("c1", "proj-1", "主角张三是火爆剑修", "CHARACTER"));
        svc.index(new IndexedText("c2", "proj-1", "玄黄大陆位于东方", "WORLDBUILDING"));

        // 用与"主角"首字相同的查询
        List<VectorSearchResult> results = svc.search("主张相关", "proj-1", 2);
        assertFalse(results.isEmpty());
        assertEquals("c1", results.get(0).id());  // "主"字开头最相似
    }

    @Test
    void 检索按项目隔离() {
        VectorStore store = new com.writing.novel.persistence.embedding.EmbeddedVectorStore(
                java.nio.file.Files.createTempFile("vec2", ".idx").toString());
        VectorRetrievalService svc = new VectorRetrievalService(store, simpleEmbedding());

        svc.index(new IndexedText("a", "proj-1", "内容A", "CHAPTER"));
        svc.index(new IndexedText("b", "proj-2", "内容A", "CHAPTER"));

        List<VectorSearchResult> results = svc.search("内容A", "proj-1", 10);
        assertEquals(1, results.size());
        assertEquals("a", results.get(0).id());
    }

    @Test
    void 取回原文() {
        VectorStore store = new com.writing.novel.persistence.embedding.EmbeddedVectorStore(
                java.nio.file.Files.createTempFile("vec3", ".idx").toString());
        VectorRetrievalService svc = new VectorRetrievalService(store, simpleEmbedding());

        svc.index(new IndexedText("x1", "p1", "这是要被检索的原文", "CHAPTER"));
        assertEquals("这是要被检索的原文", svc.getText("x1"));
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn test -Dtest=VectorRetrievalServiceTest`
Expected: FAIL（类不存在）

- [ ] **Step 3: 创建 IndexedText**

`src/main/java/com/writing/novel/memory/IndexedText.java`:
```java
package com.writing.novel.memory;

/**
 * 待索引的文本块。sourceType 标识来源（CHARACTER/WORLDBUILDING/CHAPTER/FORESHADOWING）。
 */
public record IndexedText(String id, String projectId, String text, String sourceType) {}
```

- [ ] **Step 4: 创建 VectorRetrievalService**

`src/main/java/com/writing/novel/memory/VectorRetrievalService.java`:
```java
package com.writing.novel.memory;

import com.writing.novel.spi.EmbeddingProvider;
import com.writing.novel.spi.VectorSearchResult;
import com.writing.novel.spi.VectorStore;

import java.util.List;

/**
 * 第三层记忆：向量检索服务。
 * 封装"文本→向量→存储/检索"流程，上层不直接碰 VectorStore/EmbeddingProvider。
 */
public class VectorRetrievalService {

    private final VectorStore vectorStore;
    private final EmbeddingProvider embeddingProvider;

    public VectorRetrievalService(VectorStore vectorStore, EmbeddingProvider embeddingProvider) {
        this.vectorStore = vectorStore;
        this.embeddingProvider = embeddingProvider;
    }

    /** 索引一段文本（自动向量化并存储） */
    public void index(IndexedText text) {
        float[] vector = embeddingProvider.embed(text.text());
        vectorStore.upsert(text.id(), text.projectId(), text.text(), vector);
    }

    /** 语义检索 top-K 最相关的文本块 */
    public List<VectorSearchResult> search(String queryText, String projectId, int topK) {
        float[] queryVector = embeddingProvider.embed(queryText);
        return vectorStore.search(queryVector, projectId, topK);
    }

    /** 按 id 取回原文 */
    public String getText(String id) {
        return vectorStore.getText(id);
    }

    /** 删除索引 */
    public void remove(String id) {
        vectorStore.delete(id);
    }
}
```

- [ ] **Step 5: 运行测试确认通过**

Run: `mvn test -Dtest=VectorRetrievalServiceTest`
Expected: 3 个测试 PASS

- [ ] **Step 6: 提交**

```bash
git add src/main/java/com/writing/novel/memory/IndexedText.java \
        src/main/java/com/writing/novel/memory/VectorRetrievalService.java \
        src/test/java/com/writing/novel/memory/VectorRetrievalServiceTest.java
git commit -m "feat(memory): 向量检索服务（第三层记忆）"
```

---

## Task 2: KnowledgeBaseService（第一层：结构化知识库）

**Files:**
- Create: `src/main/java/com/writing/novel/memory/KnowledgeBaseService.java`
- Test: `src/test/java/com/writing/novel/memory/KnowledgeBaseServiceTest.java`

**说明:** 结构化知识库的服务层。封装 NovelRepository 对 KnowledgeEntry/Character 的查询，提供"获取项目所有人物"、"按类型查世界观条目"等便捷方法。同时支持索引到向量库（写入知识时同步索引）。

- [ ] **Step 1: 写失败测试**

`src/test/java/com/writing/novel/memory/KnowledgeBaseServiceTest.java`:
```java
package com.writing.novel.memory;

import com.writing.novel.domain.Character;
import com.writing.novel.domain.KnowledgeEntry;
import com.writing.novel.domain.Project;
import com.writing.novel.persistence.sqlite.ConnectionProvider;
import com.writing.novel.persistence.sqlite.SqliteNovelRepository;
import com.writing.novel.persistence.sqlite.SqliteSchemaInitializer;
import com.writing.novel.spi.EmbeddingProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class KnowledgeBaseServiceTest {

    @TempDir
    Path tempDir;
    private KnowledgeBaseService service;
    private SqliteNovelRepository repo;

    @BeforeEach
    void setUp() {
        ConnectionProvider cp = new ConnectionProvider(tempDir.resolve("kb.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        repo = new SqliteNovelRepository(cp);

        // 简单 embedding（不实际用，但 VectorRetrievalService 需要）
        EmbeddingProvider emb = new EmbeddingProvider() {
            @Override public float[] embed(String text) { return new float[]{1.0f}; }
            @Override public int dimension() { return 1; }
        };
        var store = new com.writing.novel.persistence.embedding.EmbeddedVectorStore(
                tempDir.resolve("kb.idx").toString());
        service = new KnowledgeBaseService(repo, new VectorRetrievalService(store, emb));
    }

    @Test
    void 保存知识条目并按类型查询() {
        KnowledgeEntry geo = new KnowledgeEntry("p1", "GEOGRAPHY", "玄黄大陆", "东方主大陆");
        KnowledgeEntry power = new KnowledgeEntry("p1", "POWER_SYSTEM", "灵气体系", "九重境界");
        service.saveEntry(geo);
        service.saveEntry(power);

        List<KnowledgeEntry> geoEntries = service.findEntriesByType("p1", "GEOGRAPHY");
        assertEquals(1, geoEntries.size());
        assertEquals("玄黄大陆", geoEntries.get(0).getName());
    }

    @Test
    void 保存人物并查询全部() {
        Character c1 = new Character("p1", "张三", "主角");
        c1.setPersonality("火爆");
        Character c2 = new Character("p1", "李四", "配角");
        service.saveCharacter(c1);
        service.saveCharacter(c2);

        List<Character> chars = service.findAllCharacters("p1");
        assertEquals(2, chars.size());
    }

    @Test
    void 保存知识时同步索引到向量库() {
        KnowledgeEntry entry = new KnowledgeEntry("p1", "GEOGRAPHY", "青云宗",
                "位于玄黄大陆东部的修仙宗门");
        service.saveEntry(entry);

        // 验证已被索引（通过 getText 能取回）
        String indexed = service.getIndexedText(entry.getId());
        assertNotNull(indexed);
        assertTrue(indexed.contains("青云宗"));
    }

    @Test
    void 按名称查人物() {
        Character c = new Character("p1", "王五", "反派");
        service.saveCharacter(c);

        var found = service.findCharacterByName("p1", "王五");
        assertTrue(found.isPresent());
        assertEquals("反派", found.get().getRole());
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn test -Dtest=KnowledgeBaseServiceTest`
Expected: FAIL

- [ ] **Step 3: 创建 KnowledgeBaseService**

`src/main/java/com/writing/novel/memory/KnowledgeBaseService.java`:
```java
package com.writing.novel.memory;

import com.writing.novel.domain.Character;
import com.writing.novel.domain.KnowledgeEntry;
import com.writing.novel.spi.NovelRepository;

import java.util.List;
import java.util.Optional;

/**
 * 第一层记忆：结构化知识库服务。
 * 封装世界观条目与人物的 CRUD，写入时同步索引到向量库。
 */
public class KnowledgeBaseService {

    private final NovelRepository repository;
    private final VectorRetrievalService vectorRetrieval;

    public KnowledgeBaseService(NovelRepository repository, VectorRetrievalService vectorRetrieval) {
        this.repository = repository;
        this.vectorRetrieval = vectorRetrieval;
    }

    // --- 知识条目（世界观） ---
    public void saveEntry(KnowledgeEntry entry) {
        repository.saveKnowledgeEntry(entry);
        // 同步索引到向量库，便于语义检索
        vectorRetrieval.index(new IndexedText(
                entry.getId(), entry.getProjectId(),
                entry.getName() + "：" + entry.getContent(), "WORLDBUILDING"));
    }

    public List<KnowledgeEntry> findAllEntries(String projectId) {
        return repository.findKnowledgeEntriesByProject(projectId);
    }

    public List<KnowledgeEntry> findEntriesByType(String projectId, String type) {
        return repository.findKnowledgeEntriesByType(projectId, type);
    }

    public String getIndexedText(String id) {
        return vectorRetrieval.getText(id);
    }

    // --- 人物 ---
    public void saveCharacter(Character character) {
        repository.saveCharacter(character);
        // 索引人物卡：名字 + 性格 + 能力，便于写作时检索
        String indexText = character.getName() + "（" + character.getRole() + "）"
                + nullSafe(character.getPersonality()) + nullSafe(character.getAbility());
        vectorRetrieval.index(new IndexedText(
                character.getId(), character.getProjectId(), indexText, "CHARACTER"));
    }

    public List<Character> findAllCharacters(String projectId) {
        return repository.findCharactersByProject(projectId);
    }

    public Optional<Character> findCharacterByName(String projectId, String name) {
        return repository.findCharactersByProject(projectId).stream()
                .filter(c -> name.equals(c.getName()))
                .findFirst();
    }

    public Optional<Character> findCharacter(String id) {
        return repository.findCharacter(id);
    }

    private static String nullSafe(String s) {
        return s == null ? "" : "，" + s;
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn test -Dtest=KnowledgeBaseServiceTest`
Expected: 4 个测试 PASS

- [ ] **Step 5: 提交**

```bash
git add src/main/java/com/writing/novel/memory/KnowledgeBaseService.java \
        src/test/java/com/writing/novel/memory/KnowledgeBaseServiceTest.java
git commit -m "feat(memory): 结构化知识库服务（第一层记忆）"
```

---

## Task 3: SummaryService（第二层：滚动摘要）

**Files:**
- Create: `src/main/java/com/writing/novel/memory/SummaryService.java`
- Test: `src/test/java/com/writing/novel/memory/SummaryServiceTest.java`

**说明:** 滚动摘要服务。提供"保存摘要"、"获取项目的所有摘要"、"获取覆盖某章节的摘要"、"获取最近 N 章的摘要"等查询。摘要的内容生成由 Plan 4 的 MemoryManager Agent 负责（调 LLM），本 Service 只负责存储与查询。

- [ ] **Step 1: 写失败测试**

`src/test/java/com/writing/novel/memory/SummaryServiceTest.java`:
```java
package com.writing.novel.memory;

import com.writing.novel.domain.Summary;
import com.writing.novel.persistence.sqlite.ConnectionProvider;
import com.writing.novel.persistence.sqlite.SqliteNovelRepository;
import com.writing.novel.persistence.sqlite.SqliteSchemaInitializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SummaryServiceTest {

    @TempDir
    Path tempDir;
    private SummaryService service;

    @BeforeEach
    void setUp() {
        ConnectionProvider cp = new ConnectionProvider(tempDir.resolve("sum.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        service = new SummaryService(new SqliteNovelRepository(cp));
    }

    @Test
    void 保存并查询摘要() {
        service.save(new Summary("p1", 1, 5, "第1-5章摘要"));
        service.save(new Summary("p1", 6, 10, "第6-10章摘要"));

        List<Summary> all = service.findAll("p1");
        assertEquals(2, all.size());
    }

    @Test
    void 获取覆盖指定章节的摘要() {
        service.save(new Summary("p1", 1, 5, "前期"));
        service.save(new Summary("p1", 6, 10, "中期"));

        List<Summary> covering = service.findCovering("p1", 7);
        assertEquals(1, covering.size());
        assertEquals("中期", covering.get(0).getContent());
    }

    @Test
    void 获取最近一段章节的摘要() {
        service.save(new Summary("p1", 1, 5, "前期"));
        service.save(new Summary("p1", 6, 10, "中期"));
        service.save(new Summary("p1", 11, 15, "后期"));

        // 当前写到第 15 章，取最近覆盖的摘要（最近 5 章 → 覆盖 11-15）
        List<Summary> recent = service.findRecent("p1", 15, 5);
        assertFalse(recent.isEmpty());
        // 应至少包含覆盖 11-15 的摘要
        assertTrue(recent.stream().anyMatch(s -> s.getContent().equals("后期")));
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn test -Dtest=SummaryServiceTest`
Expected: FAIL

- [ ] **Step 3: 创建 SummaryService**

`src/main/java/com/writing/novel/memory/SummaryService.java`:
```java
package com.writing.novel.memory;

import com.writing.novel.domain.Summary;
import com.writing.novel.spi.NovelRepository;

import java.util.List;

/**
 * 第二层记忆：滚动摘要服务。
 * 摘要内容的生成由 MemoryManager Agent（Plan 4）负责，本类只负责存储与查询。
 */
public class SummaryService {

    private final NovelRepository repository;

    public SummaryService(NovelRepository repository) {
        this.repository = repository;
    }

    public void save(Summary summary) {
        repository.saveSummary(summary);
    }

    public List<Summary> findAll(String projectId) {
        return repository.findSummariesByProject(projectId);
    }

    /** 获取覆盖指定章节的所有摘要 */
    public List<Summary> findCovering(String projectId, int chapterIndex) {
        return repository.findSummariesByProject(projectId).stream()
                .filter(s -> s.covers(chapterIndex))
                .toList();
    }

    /**
     * 获取最近一段章节范围的摘要。
     * 返回 fromChapter 范围 [currentChapter - window, currentChapter] 有交集的摘要。
     */
    public List<Summary> findRecent(String projectId, int currentChapter, int window) {
        int from = Math.max(1, currentChapter - window + 1);
        int to = currentChapter;
        return repository.findSummariesByProject(projectId).stream()
                .filter(s -> s.getFromChapter() <= to && s.getToChapter() >= from)
                .toList();
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn test -Dtest=SummaryServiceTest`
Expected: 3 个测试 PASS

- [ ] **Step 5: 提交**

```bash
git add src/main/java/com/writing/novel/memory/SummaryService.java \
        src/test/java/com/writing/novel/memory/SummaryServiceTest.java
git commit -m "feat(memory): 滚动摘要服务（第二层记忆）"
```

---

## Task 4: WritingContext DTO + ContextAssembler（核心！上下文组装）

**Files:**
- Create: `src/main/java/com/writing/novel/memory/WritingContext.java`
- Create: `src/main/java/com/writing/novel/memory/ContextAssembler.java`
- Test: `src/test/java/com/writing/novel/memory/ContextAssemblerTest.java`

**说明:** 这是整个 Plan 3 的核心。实现 spec 第 5 节的上下文组装公式：
```
[全局] 风格锚点 + 世界观核心规则
+ [本卷] 当前卷大纲 + 本卷主线摘要
+ [近期] 前 1~2 章摘要 + 当前章前 ~2000 字原文
+ [检索] 向量检索 top-K 相关：本章出场人物卡 + 涉及地点/物品设定 + 相关伏笔
+ [任务] 本章梗概 + 写作要求
```

- [ ] **Step 1: 写失败测试**

`src/test/java/com/writing/novel/memory/ContextAssemblerTest.java`:
```java
package com.writing.novel.memory;

import com.writing.novel.domain.*;
import com.writing.novel.persistence.sqlite.ConnectionProvider;
import com.writing.novel.persistence.sqlite.SqliteNovelRepository;
import com.writing.novel.persistence.sqlite.SqliteSchemaInitializer;
import com.writing.novel.spi.EmbeddingProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ContextAssemblerTest {

    @TempDir
    Path tempDir;
    private ContextAssembler assembler;
    private SqliteNovelRepository repo;
    private KnowledgeBaseService kbService;
    private SummaryService summaryService;

    @BeforeEach
    void setUp() {
        ConnectionProvider cp = new ConnectionProvider(tempDir.resolve("ctx.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        repo = new SqliteNovelRepository(cp);

        EmbeddingProvider emb = new EmbeddingProvider() {
            @Override public float[] embed(String text) { return new float[]{1.0f}; }
            @Override public int dimension() { return 1; }
        };
        var store = new com.writing.novel.persistence.embedding.EmbeddedVectorStore(
                tempDir.resolve("ctx.idx").toString());
        VectorRetrievalService vectorSvc = new VectorRetrievalService(store, emb);
        kbService = new KnowledgeBaseService(repo, vectorSvc);
        summaryService = new SummaryService(repo);
        assembler = new ContextAssembler(repo, kbService, summaryService, vectorSvc);
    }

    @Test
    void 组装包含风格锚点与世界规则() {
        Project p = new Project("测试", "简介", "玄幻");
        repo.saveProject(p);
        // 风格
        StyleProfile style = new StyleProfile(p.getId());
        style.setTone("热血、紧凑");
        style.setDosAndDonts("多用短句，避免大段说明");
        repo.saveStyleProfile(style);
        // 世界观
        kbService.saveEntry(new KnowledgeEntry(p.getId(), "POWER_SYSTEM", "灵气体系", "修炼九重境界"));

        WritingContext ctx = assembler.assemble(p.getId(), 1, "第一章 灵根测试",
                "张三参加灵根测试", "写 3000 字，节奏紧凑");

        assertNotNull(ctx);
        assertTrue(ctx.getStyleSection().contains("热血"));
        assertTrue(ctx.getWorldbuildingSection().contains("灵气体系"));
    }

    @Test
    void 组装包含近期章节摘要与原文() {
        Project p = new Project("t", "c", "tag");
        repo.saveProject(p);

        // 前 1 章原文
        Chapter prev = new Chapter(p.getId(), 1, "第一章");
        prev.setContent("上一章的正文内容，张三踏入山门。");
        repo.saveChapter(prev);

        // 摘要
        summaryService.save(new Summary(p.getId(), 1, 1, "张三拜入青云宗。"));

        WritingContext ctx = assembler.assemble(p.getId(), 2, "第二章",
                "张三开始修炼", null);

        assertTrue(ctx.getRecentSummarySection().contains("张三拜入青云宗"));
        assertTrue(ctx.getRecentTextSection().contains("上一章的正文内容"));
    }

    @Test
    void 组装包含检索到的人物卡() {
        Project p = new Project("t", "c", "tag");
        repo.saveProject(p);
        Character mc = new Character(p.getId(), "张三", "主角");
        mc.setPersonality("火爆");
        mc.setAbility("剑修");
        kbService.saveCharacter(mc);

        // 用"张三"作为查询能检索到人物
        WritingContext ctx = assembler.assemble(p.getId(), 1, "第一章",
                "张三参加灵根测试", null);

        assertFalse(ctx.getRelevantCharacters().isEmpty(), "应检索到相关人物");
        assertEquals("张三", ctx.getRelevantCharacters().get(0).getName());
    }

    @Test
    void 组装包含任务章节梗概() {
        Project p = new Project("t", "c", "tag");
        repo.saveProject(p);

        WritingContext ctx = assembler.assemble(p.getId(), 1, "第一章 灵根测试",
                "本章：张三觉醒上古剑骨，震惊全场。", "字数 3000，结尾留悬念");

        assertTrue(ctx.getTaskSection().contains("张三觉醒上古剑骨"));
        assertTrue(ctx.getTaskSection().contains("3000"));
    }

    @Test
    void 组装为完整的system和user prompt() {
        Project p = new Project("t", "c", "tag");
        repo.saveProject(p);
        StyleProfile style = new StyleProfile(p.getId());
        style.setTone("热血");
        repo.saveStyleProfile(style);
        kbService.saveEntry(new KnowledgeEntry(p.getId(), "RULE", "天道", "天道酬勤"));
        Character c = new Character(p.getId(), "张三", "主角");
        c.setPersonality("火爆");
        kbService.saveCharacter(c);

        WritingContext ctx = assembler.assemble(p.getId(), 1, "第一章", "张三登场", null);

        String system = ctx.toSystemPrompt();
        String user = ctx.toUserPrompt();

        assertNotNull(system);
        assertNotNull(user);
        assertTrue(system.contains("热血"));      // 风格在 system
        assertTrue(system.contains("天道"));      // 世界观在 system
        assertTrue(user.contains("张三"));         // 章节梗概在 user
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn test -Dtest=ContextAssemblerTest`
Expected: FAIL

- [ ] **Step 3: 创建 WritingContext**

`src/main/java/com/writing/novel/memory/WritingContext.java`:
```java
package com.writing.novel.memory;

import com.writing.novel.domain.Character;

import java.util.List;

/**
 * 写作上下文 — ContextAssembler 的组装结果。
 * 包含 spec 第 5 节公式的各部分，可转为 system/user prompt。
 */
public class WritingContext {

    // [全局]
    private String styleSection;           // 风格锚点
    private String worldbuildingSection;   // 世界观核心规则
    // [本卷]
    private String volumeOutlineSection;   // 当前卷大纲
    // [近期]
    private String recentSummarySection;   // 前 1~2 章摘要
    private String recentTextSection;      // 当前章前 ~2000 字原文
    // [检索]
    private List<Character> relevantCharacters;  // 检索到的相关人物
    private String relevantSettings;       // 检索到的地点/物品设定
    private String relevantForeshadowings; // 相关伏笔
    // [任务]
    private String taskSection;            // 本章梗概 + 写作要求

    // --- 组装为 prompt ---

    /** system prompt：风格 + 世界观 + 相关设定（稳定不变的上下文） */
    public String toSystemPrompt() {
        StringBuilder sb = new StringBuilder();
        if (styleSection != null && !styleSection.isBlank()) {
            sb.append("【文风要求】\n").append(styleSection).append("\n\n");
        }
        if (worldbuildingSection != null && !worldbuildingSection.isBlank()) {
            sb.append("【世界观设定】\n").append(worldbuildingSection).append("\n\n");
        }
        if (relevantSettings != null && !relevantSettings.isBlank()) {
            sb.append("【相关设定】\n").append(relevantSettings).append("\n\n");
        }
        if (!relevantCharacters.isEmpty()) {
            sb.append("【出场人物】\n");
            for (Character c : relevantCharacters) {
                sb.append("- ").append(c.getName()).append("（").append(c.getRole()).append("）");
                if (c.getPersonality() != null) sb.append("：").append(c.getPersonality());
                if (c.getAbility() != null) sb.append("，").append(c.getAbility());
                sb.append("\n");
            }
            sb.append("\n");
        }
        return sb.toString().trim();
    }

    /** user prompt：近期上下文 + 任务（本次写作的具体指令） */
    public String toUserPrompt() {
        StringBuilder sb = new StringBuilder();
        if (volumeOutlineSection != null && !volumeOutlineSection.isBlank()) {
            sb.append("【本卷大纲】\n").append(volumeOutlineSection).append("\n\n");
        }
        if (recentSummarySection != null && !recentSummarySection.isBlank()) {
            sb.append("【前情摘要】\n").append(recentSummarySection).append("\n\n");
        }
        if (recentTextSection != null && !recentTextSection.isBlank()) {
            sb.append("【上一章结尾（用于衔接）】\n").append(recentTextSection).append("\n\n");
        }
        if (relevantForeshadowings != null && !relevantForeshadowings.isBlank()) {
            sb.append("【待回收伏笔】\n").append(relevantForeshadowings).append("\n\n");
        }
        sb.append("【本章任务】\n").append(taskSection != null ? taskSection : "");
        return sb.toString().trim();
    }

    // --- getters / setters ---
    public String getStyleSection() { return styleSection; }
    public void setStyleSection(String s) { this.styleSection = s; }
    public String getWorldbuildingSection() { return worldbuildingSection; }
    public void setWorldbuildingSection(String s) { this.worldbuildingSection = s; }
    public String getVolumeOutlineSection() { return volumeOutlineSection; }
    public void setVolumeOutlineSection(String s) { this.volumeOutlineSection = s; }
    public String getRecentSummarySection() { return recentSummarySection; }
    public void setRecentSummarySection(String s) { this.recentSummarySection = s; }
    public String getRecentTextSection() { return recentTextSection; }
    public void setRecentTextSection(String s) { this.recentTextSection = s; }
    public List<Character> getRelevantCharacters() { return relevantCharacters; }
    public void setRelevantCharacters(List<Character> c) { this.relevantCharacters = c; }
    public String getRelevantSettings() { return relevantSettings; }
    public void setRelevantSettings(String s) { this.relevantSettings = s; }
    public String getRelevantForeshadowings() { return relevantForeshadowings; }
    public void setRelevantForeshadowings(String s) { this.relevantForeshadowings = s; }
    public String getTaskSection() { return taskSection; }
    public void setTaskSection(String s) { this.taskSection = s; }
}
```

- [ ] **Step 4: 创建 ContextAssembler**

`src/main/java/com/writing/novel/memory/ContextAssembler.java`:
```java
package com.writing.novel.memory;

import com.writing.novel.domain.*;
import com.writing.novel.spi.NovelRepository;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 上下文组装器 — 实现 spec 第 5 节的上下文组装公式。
 * 把三层记忆 + 风格 + 任务拼装成 ChapterWriter 所需的完整上下文。
 *
 * 公式：
 * [全局] 风格锚点 + 世界观核心规则
 * + [本卷] 当前卷大纲
 * + [近期] 前 1~2 章摘要 + 当前章前 ~2000 字原文
 * + [检索] 向量检索 top-K 相关：人物卡 + 设定 + 伏笔
 * + [任务] 本章梗概 + 写作要求
 */
public class ContextAssembler {

    /** 检索相关人物/设定的 top-K */
    private static final int RETRIEVAL_TOP_K = 5;
    /** 近期原文衔接的字数 */
    private static final int RECENT_TEXT_CHARS = 2000;
    /** 近期摘要的章节窗口 */
    private static final int RECENT_SUMMARY_WINDOW = 5;

    private final NovelRepository repository;
    private final KnowledgeBaseService knowledgeBase;
    private final SummaryService summaryService;
    private final VectorRetrievalService vectorRetrieval;

    public ContextAssembler(NovelRepository repository,
                            KnowledgeBaseService knowledgeBase,
                            SummaryService summaryService,
                            VectorRetrievalService vectorRetrieval) {
        this.repository = repository;
        this.knowledgeBase = knowledgeBase;
        this.summaryService = summaryService;
        this.vectorRetrieval = vectorRetrieval;
    }

    /**
     * 组装写某章的完整上下文。
     *
     * @param projectId       项目 id
     * @param chapterIndex    要写的章节序号
     * @param chapterTitle    章节标题
     * @param chapterSynopsis 本章梗概（要写什么）
     * @param writingNotes    写作要求（字数/爽点/节奏，可空）
     */
    public WritingContext assemble(String projectId, int chapterIndex,
                                   String chapterTitle, String chapterSynopsis,
                                   String writingNotes) {

        WritingContext ctx = new WritingContext();

        // [全局] 风格锚点
        repository.findStyleProfileByProject(projectId)
                .ifPresent(style -> ctx.setStyleSection(buildStyleSection(style)));

        // [全局] 世界观核心规则
        ctx.setWorldbuildingSection(buildWorldbuildingSection(projectId));

        // [本卷] 当前卷大纲（查找包含本章节梗概的卷）
        ctx.setVolumeOutlineSection(buildVolumeOutline(projectId, chapterIndex));

        // [近期] 前 1~2 章摘要
        ctx.setRecentSummarySection(buildRecentSummary(projectId, chapterIndex));

        // [近期] 当前章前 ~2000 字原文（上一章结尾）
        ctx.setRecentTextSection(buildRecentText(projectId, chapterIndex));

        // [检索] 向量检索相关人物 + 设定
        String queryText = chapterSynopsis != null ? chapterSynopsis : chapterTitle;
        assembleRetrievedContext(projectId, queryText, ctx);

        // [检索] 未回收伏笔
        ctx.setRelevantForeshadowings(buildForeshadowings(projectId));

        // [任务] 本章梗概 + 写作要求
        ctx.setTaskSection(buildTaskSection(chapterTitle, chapterSynopsis, writingNotes));

        return ctx;
    }

    private String buildStyleSection(StyleProfile style) {
        StringBuilder sb = new StringBuilder();
        if (style.getTone() != null) sb.append("基调：").append(style.getTone()).append("\n");
        if (style.getVoice() != null) sb.append("叙事：").append(style.getVoice()).append("\n");
        if (style.getDosAndDonts() != null) sb.append("注意事项：").append(style.getDosAndDonts());
        return sb.toString();
    }

    private String buildWorldbuildingSection(String projectId) {
        List<KnowledgeEntry> entries = knowledgeBase.findAllEntries(projectId);
        if (entries.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (KnowledgeEntry e : entries) {
            sb.append("【").append(e.getName()).append("】").append(e.getContent()).append("\n");
        }
        return sb.toString().trim();
    }

    private String buildVolumeOutline(String projectId, int chapterIndex) {
        Optional<Outline> outlineOpt = repository.findOutlineByProject(projectId);
        if (outlineOpt.isEmpty()) return "";
        // 找到包含当前章节的卷
        for (Volume v : outlineOpt.get().getVolumes()) {
            boolean contains = v.getSynopses().stream()
                    .anyMatch(s -> s.getChapterIndex() == chapterIndex);
            if (contains) {
                StringBuilder sb = new StringBuilder();
                sb.append("卷：").append(v.getTitle()).append("\n");
                if (v.getSummary() != null) sb.append(v.getSummary()).append("\n");
                // 列出本卷各章梗概
                for (ChapterSynopsis s : v.getSynopses()) {
                    sb.append("  第").append(s.getChapterIndex()).append("章 ")
                            .append(s.getTitle()).append("：").append(s.getSynopsis()).append("\n");
                }
                return sb.toString().trim();
            }
        }
        return "";
    }

    private String buildRecentSummary(String projectId, int chapterIndex) {
        int lookBackTo = Math.max(1, chapterIndex - 1);
        List<Summary> summaries = summaryService.findRecent(projectId, lookBackTo, RECENT_SUMMARY_WINDOW);
        if (summaries.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (Summary s : summaries) {
            sb.append("第").append(s.getFromChapter()).append("-")
                    .append(s.getToChapter()).append("章：")
                    .append(s.getContent()).append("\n");
        }
        return sb.toString().trim();
    }

    private String buildRecentText(String projectId, int chapterIndex) {
        if (chapterIndex <= 1) return "";  // 第一章没有上一章
        Optional<Chapter> prevOpt = repository.findChapterByIndex(projectId, chapterIndex - 1);
        if (prevOpt.isEmpty()) return "";
        String content = prevOpt.get().getContent();
        if (content == null || content.isEmpty()) return "";
        // 取最后 RECENT_TEXT_CHARS 字作为衔接
        if (content.length() > RECENT_TEXT_CHARS) {
            return "..." + content.substring(content.length() - RECENT_TEXT_CHARS);
        }
        return content;
    }

    private void assembleRetrievedContext(String projectId, String queryText, WritingContext ctx) {
        // 检索相关人物
        List<Character> allChars = knowledgeBase.findAllCharacters(projectId);
        // 用向量检索找到最相关的人物 id，再映射回 Character 对象
        var searchResults = vectorRetrieval.search(queryText, projectId, RETRIEVAL_TOP_K);
        List<String> relevantIds = searchResults.stream()
                .map(r -> r.id())
                .toList();

        List<Character> relevantChars = allChars.stream()
                .filter(c -> relevantIds.contains(c.getId()))
                .collect(Collectors.toList());
        ctx.setRelevantCharacters(relevantChars);

        // 检索相关设定（非人物的索引条目）
        StringBuilder settings = new StringBuilder();
        for (String id : relevantIds) {
            // 检查是否是知识条目（非人物）
            repository.findKnowledgeEntry(id).ifPresent(e -> {
                settings.append("【").append(e.getName()).append("】")
                        .append(e.getContent()).append("\n");
            });
        }
        ctx.setRelevantSettings(settings.toString().trim());
    }

    private String buildForeshadowings(String projectId) {
        List<Foreshadowing> unresolved = repository.findUnresolvedForeshadowings(projectId);
        if (unresolved.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (Foreshadowing f : unresolved) {
            sb.append("- ").append(f.getDescription())
                    .append("（埋于第").append(f.getPlantedAtChapter()).append("章）\n");
        }
        return sb.toString().trim();
    }

    private String buildTaskSection(String chapterTitle, String synopsis, String writingNotes) {
        StringBuilder sb = new StringBuilder();
        sb.append("章节：").append(chapterTitle).append("\n");
        sb.append("内容：").append(synopsis != null ? synopsis : "").append("\n");
        if (writingNotes != null && !writingNotes.isBlank()) {
            sb.append("要求：").append(writingNotes);
        }
        return sb.toString().trim();
    }
}
```

- [ ] **Step 5: 运行测试确认通过**

Run: `mvn test -Dtest=ContextAssemblerTest`
Expected: 5 个测试 PASS

- [ ] **Step 6: 提交**

```bash
git add src/main/java/com/writing/novel/memory/WritingContext.java \
        src/main/java/com/writing/novel/memory/ContextAssembler.java \
        src/test/java/com/writing/novel/memory/ContextAssemblerTest.java
git commit -m "feat(memory): 上下文组装器（核心！实现三层记忆的上下文拼装公式）"
```

---

## Task 5: MemoryConfig Bean 装配 + 端到端集成测试

**Files:**
- Create: `src/main/java/com/writing/novel/memory/MemoryConfig.java`
- Test: `src/test/java/com/writing/novel/memory/MemoryIntegrationTest.java`

- [ ] **Step 1: 写失败测试 — Spring 装配 + 端到端**

`src/test/java/com/writing/novel/memory/MemoryIntegrationTest.java`:
```java
package com.writing.novel.memory;

import com.writing.novel.domain.*;
import com.writing.novel.spi.NovelRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.io.File;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@TestPropertySource(properties = {
        "persistence.embedding=stub",
        "persistence.sqlite.db-path=./data/test-mem.db",
        "persistence.vector-store.index-path=./data/test-mem-vecs.idx"
})
class MemoryIntegrationTest {

    @Autowired KnowledgeBaseService knowledgeBase;
    @Autowired SummaryService summaryService;
    @Autowired ContextAssembler contextAssembler;
    @Autowired NovelRepository repository;

    @org.junit.jupiter.api.BeforeAll
    static void createDataDir() { new File("./data").mkdirs(); }

    @Test
    void 全链路_存设定_写章节_组装上下文() {
        // 1. 创建项目 + 设定
        Project p = new Project("记忆集成测试", "剑修故事", "玄幻");
        repository.saveProject(p);

        StyleProfile style = new StyleProfile(p.getId());
        style.setTone("热血");
        repository.saveStyleProfile(style);

        knowledgeBase.saveEntry(new KnowledgeEntry(p.getId(), "POWER_SYSTEM",
                "灵气体系", "修炼九重"));
        Character mc = new Character(p.getId(), "张三", "主角");
        mc.setPersonality("火爆");
        mc.setAbility("剑修");
        knowledgeBase.saveCharacter(mc);

        // 2. 写第一章
        Chapter c1 = new Chapter(p.getId(), 1, "第一章");
        c1.setContent("张三踏入青云宗山门，剑意凛然。");
        repository.saveChapter(c1);
        summaryService.save(new Summary(p.getId(), 1, 1, "张三拜入青云宗。"));

        // 3. 组装第二章上下文
        WritingContext ctx = contextAssembler.assemble(
                p.getId(), 2, "第二章 初次修炼",
                "张三开始修炼基础剑法", "3000 字");

        // 验证上下文包含各部分
        assertNotNull(ctx);
        assertTrue(ctx.getStyleSection().contains("热血"));
        assertTrue(ctx.getWorldbuildingSection().contains("灵气体系"));
        assertTrue(ctx.getRecentSummarySection().contains("青云宗"));
        assertTrue(ctx.getRecentTextSection().contains("剑意凛然"));
        assertFalse(ctx.getRelevantCharacters().isEmpty());
        assertTrue(ctx.getTaskSection().contains("修炼基础剑法"));

        // 验证可转为完整 prompt
        String system = ctx.toSystemPrompt();
        String user = ctx.toUserPrompt();
        assertTrue(system.contains("张三"));
        assertTrue(user.contains("第二章"));
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn test -Dtest=MemoryIntegrationTest`
Expected: FAIL（无 Bean 装配）

- [ ] **Step 3: 创建 MemoryConfig**

`src/main/java/com/writing/novel/memory/MemoryConfig.java`:
```java
package com.writing.novel.memory;

import com.writing.novel.spi.EmbeddingProvider;
import com.writing.novel.spi.NovelRepository;
import com.writing.novel.spi.VectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 记忆模块的 Bean 装配。
 * 装配三个记忆服务 + 上下文组装器。
 */
@Configuration
public class MemoryConfig {

    @Bean
    public VectorRetrievalService vectorRetrievalService(VectorStore vectorStore,
                                                          EmbeddingProvider embeddingProvider) {
        return new VectorRetrievalService(vectorStore, embeddingProvider);
    }

    @Bean
    public KnowledgeBaseService knowledgeBaseService(NovelRepository repository,
                                                      VectorRetrievalService vectorRetrieval) {
        return new KnowledgeBaseService(repository, vectorRetrieval);
    }

    @Bean
    public SummaryService summaryService(NovelRepository repository) {
        return new SummaryService(repository);
    }

    @Bean
    public ContextAssembler contextAssembler(NovelRepository repository,
                                              KnowledgeBaseService knowledgeBase,
                                              SummaryService summaryService,
                                              VectorRetrievalService vectorRetrieval) {
        return new ContextAssembler(repository, knowledgeBase, summaryService, vectorRetrieval);
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn test -Dtest=MemoryIntegrationTest`
Expected: PASS

- [ ] **Step 5: 运行全部测试**

Run: `mvn clean test`
Expected: 全部 PASS

- [ ] **Step 6: mvn verify**

Run: `mvn clean verify`
Expected: BUILD SUCCESS

- [ ] **Step 7: 提交**

```bash
git add src/main/java/com/writing/novel/memory/MemoryConfig.java \
        src/test/java/com/writing/novel/memory/MemoryIntegrationTest.java
git commit -m "feat(memory): MemoryConfig 装配 + 端到端集成测试"
```

---

## 自审记录

**Spec 覆盖检查（对照 spec 第 5 节三层记忆策略）：**
- ✅ 第一层结构化知识库 — Task 2（KnowledgeBaseService）
- ✅ 第二层滚动摘要 — Task 3（SummaryService）
- ✅ 第三层向量检索 — Task 1（VectorRetrievalService）
- ✅ 上下文组装公式（全部 5 部分）— Task 4（ContextAssembler）
  - [全局] 风格锚点 + 世界观 ✅
  - [本卷] 当前卷大纲 ✅
  - [近期] 前章摘要 + 上章原文 ✅
  - [检索] 人物卡 + 设定 + 伏笔 ✅
  - [任务] 章节梗概 + 写作要求 ✅
- ✅ 摘要更新时机 — 摘要内容由 Plan 4 的 MemoryManager Agent 生成后调用 SummaryService.save()，本 Plan 提供接口 ✅

**类型一致性检查：**
- VectorRetrievalService 依赖 VectorStore + EmbeddingProvider（Plan 1 SPI）— 一致 ✅
- KnowledgeBaseService 依赖 NovelRepository + VectorRetrievalService — 一致 ✅
- ContextAssembler 的 assemble() 参数签名在测试和实现间一致 ✅
- WritingContext 的 toSystemPrompt()/toUserPrompt() 分工明确（system=稳定上下文，user=本次任务）✅

**与 Plan 4 的衔接点（已预留）：**
- ContextAssembler.assemble() 是 Plan 4 ChapterWriter 的核心依赖
- KnowledgeBaseService.saveEntry/saveCharacter 是 WorldBuilder/CharacterDesigner Agent 的依赖
- SummaryService.save() 是 MemoryManager Agent 的依赖
- VectorRetrievalService.index() 可在章节写完后索引正文（Plan 4 实现）

**未覆盖项（属 Plan 4）：**
- 摘要的实际生成（调 LLM 压缩章节）— MemoryManager Agent
- 章节正文写完后的自动索引 — ChapterWriter 流程的一部分

---

## 后续 Plan 预告

| Plan | 内容 | 依赖 |
|------|------|------|
| **Plan 4** | 7 个 Agent 实现 | Plan 2, 3 |
| **Plan 5** | Orchestrator 状态机 + 事件调度 + Solo/交互/checkpoint | Plan 3, 4 |
| **Plan 6** | REST API + SSE 层 | Plan 3, 4, 5 |
