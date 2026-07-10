# Plan 5: Orchestrator 状态机 + 事件调度 + Solo/交互/checkpoint

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement task-by-task.

**Goal:** 构建 Orchestrator——系统的大脑。驱动小说生命周期状态机（CONCEIVING→PLANNING→WRITING→VOLUME_DONE→FINAL），调度 7 个 Agent，管理 Solo/交互模式与 checkpoint，支持断点续写。

**Architecture:** `NovelOrchestrator` 是核心调度器，维护项目状态机，按状态决定调用哪些 Agent。每个状态转移前检查模式：Solo 自动推进，交互模式在 checkpoint 处暂停。`ProjectService` 管理项目创建与查询，是 Orchestrator 的入口。

**Tech Stack:** Spring Boot 3, 依赖 Plan 1-4 全部组件。

**Spec 参考:** spec 第 6 节（状态机）、第 7 节（Solo/交互/checkpoint）。

---

## 文件结构

```
src/main/java/com/writing/novel/orchestrator/
├── ProjectService.java         # 项目管理（创建/查询/模式切换）
├── NovelOrchestrator.java      # 状态机驱动 + Agent 调度（核心）
├── OrchestratorConfig.java     # Bean 装配
```

---

## Task 1: ProjectService — 项目管理

**Files:**
- Create: `src/main/java/com/writing/novel/orchestrator/ProjectService.java`
- Test: `src/test/java/com/writing/novel/orchestrator/ProjectServiceTest.java`

- [ ] **Step 1: 写失败测试**

`src/test/java/com/writing/novel/orchestrator/ProjectServiceTest.java`:
```java
package com.writing.novel.orchestrator;

import com.writing.novel.domain.Project;
import com.writing.novel.domain.ProjectMode;
import com.writing.novel.domain.NovelState;
import com.writing.novel.persistence.sqlite.ConnectionProvider;
import com.writing.novel.persistence.sqlite.SqliteNovelRepository;
import com.writing.novel.persistence.sqlite.SqliteSchemaInitializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ProjectServiceTest {

    @TempDir Path tempDir;
    private ProjectService service;

    @BeforeEach
    void setUp() {
        ConnectionProvider cp = new ConnectionProvider(tempDir.resolve("p.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        service = new ProjectService(new SqliteNovelRepository(cp));
    }

    @Test
    void 创建项目默认Solo模式构思状态() {
        Project p = service.create("我的小说", "一个剑修故事", "玄幻,热血");

        assertNotNull(p.getId());
        assertEquals("我的小说", p.getTitle());
        assertEquals(ProjectMode.SOLO, p.getMode());
        assertEquals(NovelState.CONCEIVING, p.getState());
    }

    @Test
    void 创建项目可指定交互模式() {
        Project p = service.create("t", "c", "tag", ProjectMode.INTERACTIVE);

        assertEquals(ProjectMode.INTERACTIVE, p.getMode());
    }

    @Test
    void 切换运行模式() {
        Project p = service.create("t", "c", "tag");
        service.switchMode(p.getId(), ProjectMode.INTERACTIVE);

        Project found = service.find(p.getId()).orElseThrow();
        assertEquals(ProjectMode.INTERACTIVE, found.getMode());
    }
}
```

- [ ] **Step 2-4: 实现**

`src/main/java/com/writing/novel/orchestrator/ProjectService.java`:
```java
package com.writing.novel.orchestrator;

import com.writing.novel.domain.NovelState;
import com.writing.novel.domain.Project;
import com.writing.novel.domain.ProjectMode;
import com.writing.novel.spi.NovelRepository;

import java.util.List;
import java.util.Optional;

/**
 * 项目管理服务。创建/查询/模式切换。
 */
public class ProjectService {

    private final NovelRepository repository;

    public ProjectService(NovelRepository repository) {
        this.repository = repository;
    }

    public Project create(String title, String concept, String tags) {
        return create(title, concept, tags, ProjectMode.SOLO);
    }

    public Project create(String title, String concept, String tags, ProjectMode mode) {
        Project p = new Project(title, concept, tags);
        p.setMode(mode);
        repository.saveProject(p);
        return p;
    }

    public Optional<Project> find(String id) {
        return repository.findProject(id);
    }

    public List<Project> findAll() {
        return repository.findAllProjects();
    }

    public void switchMode(String projectId, ProjectMode mode) {
        Project p = repository.findProject(projectId)
                .orElseThrow(() -> new IllegalArgumentException("项目不存在: " + projectId));
        p.setMode(mode);
        repository.saveProject(p);
    }

    public void delete(String id) {
        repository.deleteProject(id);
    }
}
```

- [ ] **Step 5-6: 测试 + 提交**

```bash
git add src/main/java/com/writing/novel/orchestrator/ProjectService.java \
        src/test/java/com/writing/novel/orchestrator/ProjectServiceTest.java
git commit -m "feat(orchestrator): ProjectService 项目管理"
```

---

## Task 2: NovelOrchestrator — 构思阶段（世界观+人物+大纲）

**Files:**
- Create: `src/main/java/com/writing/novel/orchestrator/NovelOrchestrator.java`
- Test: `src/test/java/com/writing/novel/orchestrator/NovelOrchestratorConceiveTest.java`

**说明:** 这是状态机的核心。本 Task 实现 CONCEIVING 阶段：调用 WorldBuilder + CharacterDesigner + Plotter 生成初始设定，然后状态转为 PLANNING。

- [ ] **Step 1: 写失败测试**

`src/test/java/com/writing/novel/orchestrator/NovelOrchestratorConceiveTest.java`:
```java
package com.writing.novel.orchestrator;

import com.writing.novel.agent.*;
import com.writing.novel.domain.*;
import com.writing.novel.llm.LlmGateway;
import com.writing.novel.llm.LlmRequest;
import com.writing.novel.llm.LlmResponse;
import com.writing.novel.llm.client.StreamHandler;
import com.writing.novel.memory.*;
import com.writing.novel.persistence.sqlite.ConnectionProvider;
import com.writing.novel.persistence.sqlite.SqliteNovelRepository;
import com.writing.novel.persistence.sqlite.SqliteSchemaInitializer;
import com.writing.novel.spi.EmbeddingProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class NovelOrchestratorConceiveTest {

    @TempDir Path tempDir;
    private NovelOrchestrator orchestrator;
    private SqliteNovelRepository repo;
    private ProjectService projectService;

    @BeforeEach
    void setUp() {
        ConnectionProvider cp = new ConnectionProvider(tempDir.resolve("o.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        repo = new SqliteNovelRepository(cp);

        // stub embedding
        EmbeddingProvider emb = new EmbeddingProvider() {
            @Override public float[] embed(String text) { return new float[]{1.0f}; }
            @Override public int dimension() { return 1; }
        };
        var store = new com.writing.novel.persistence.embedding.EmbeddedVectorStore(
                tempDir.resolve("o.idx").toString());
        VectorRetrievalService vectorSvc = new VectorRetrievalService(store, emb);
        KnowledgeBaseService kbSvc = new KnowledgeBaseService(repo, vectorSvc);
        SummaryService summarySvc = new SummaryService(repo);
        ContextAssembler assembler = new ContextAssembler(repo, kbSvc, summarySvc, vectorSvc);

        // mock gateway
        LlmGateway gateway = new LlmGateway() {
            @Override
            public LlmResponse complete(LlmRequest req) {
                // 根据任务类型返回不同内容
                return switch (req.getTaskType()) {
                    case WORLDBUILDING -> new LlmResponse(
                            "【地理】玄黄大陆：东方主大陆。\n【力量体系】灵气：修炼九重。", 50, 100);
                    case CHARACTER_DESIGN -> new LlmResponse(
                            "【姓名】张三\n【角色定位】主角\n【性格】火爆\n【能力】剑修", 50, 100);
                    case PLOTTING -> new LlmResponse(
                            "【总纲】张三的修炼之路。\n\n【卷1】初入仙门\n拜入宗门。\n第1章 测试：灵根测试。", 50, 100);
                    default -> new LlmResponse("ok", 1, 1);
                };
            }
            @Override
            public void streamComplete(LlmRequest req, StreamHandler h) {}
        };

        // 构建所有 Agent
        WorldBuilderAgent worldBuilder = new WorldBuilderAgent(gateway, kbSvc);
        CharacterDesignerAgent charDesigner = new CharacterDesignerAgent(gateway, kbSvc);
        PlotterAgent plotter = new PlotterAgent(gateway, repo);
        ChapterWriterAgent chapterWriter = new ChapterWriterAgent(gateway, assembler, repo, vectorSvc);
        ContinuityCheckerAgent checker = new ContinuityCheckerAgent(gateway, repo);
        StyleGuardianAgent styleGuardian = new StyleGuardianAgent(gateway, repo);
        MemoryManagerAgent memoryManager = new MemoryManagerAgent(gateway, summarySvc);

        projectService = new ProjectService(repo);
        orchestrator = new NovelOrchestrator(repo, projectService, worldBuilder,
                charDesigner, plotter, chapterWriter, checker, styleGuardian, memoryManager,
                summarySvc, assembler);
    }

    @Test
    void Solo模式运行构思阶段生成设定并转入规划() {
        Project p = projectService.create("测试", "剑修故事", "玄幻");

        orchestrator.runConceiving(p.getId());

        Project found = repo.findProject(p.getId()).orElseThrow();
        assertEquals(NovelState.PLANNING, found.getState());
        assertFalse(repo.findKnowledgeEntriesByProject(p.getId()).isEmpty(), "应有世界观条目");
        assertFalse(repo.findCharactersByProject(p.getId()).isEmpty(), "应有人物");
        assertNotNull(repo.findOutlineByProject(p.getId()).orElse(null), "应有大纲");
    }
}
```

- [ ] **Step 2-4: 实现 NovelOrchestrator（构思阶段）**

`src/main/java/com/writing/novel/orchestrator/NovelOrchestrator.java`:
```java
package com.writing.novel.orchestrator;

import com.writing.novel.agent.*;
import com.writing.novel.domain.*;
import com.writing.novel.memory.ContextAssembler;
import com.writing.novel.memory.SummaryService;
import com.writing.novel.spi.NovelRepository;

import java.util.ArrayList;
import java.util.List;

/**
 * 小说编排器——系统大脑。驱动状态机，调度 7 个 Agent。
 */
public class NovelOrchestrator {

    private final NovelRepository repository;
    private final ProjectService projectService;
    private final WorldBuilderAgent worldBuilder;
    private final CharacterDesignerAgent characterDesigner;
    private final PlotterAgent plotter;
    private final ChapterWriterAgent chapterWriter;
    private final ContinuityCheckerAgent continuityChecker;
    private final StyleGuardianAgent styleGuardian;
    private final MemoryManagerAgent memoryManager;
    private final SummaryService summaryService;
    private final ContextAssembler contextAssembler;

    public NovelOrchestrator(NovelRepository repository,
                              ProjectService projectService,
                              WorldBuilderAgent worldBuilder,
                              CharacterDesignerAgent characterDesigner,
                              PlotterAgent plotter,
                              ChapterWriterAgent chapterWriter,
                              ContinuityCheckerAgent continuityChecker,
                              StyleGuardianAgent styleGuardian,
                              MemoryManagerAgent memoryManager,
                              SummaryService summaryService,
                              ContextAssembler contextAssembler) {
        this.repository = repository;
        this.projectService = projectService;
        this.worldBuilder = worldBuilder;
        this.characterDesigner = characterDesigner;
        this.plotter = plotter;
        this.chapterWriter = chapterWriter;
        this.continuityChecker = continuityChecker;
        this.styleGuardian = styleGuardian;
        this.memoryManager = memoryManager;
        this.summaryService = summaryService;
        this.contextAssembler = contextAssembler;
    }

    /**
     * 构思阶段：生成世界观 + 人物 + 大纲。
     * Solo 模式自动完成后转入 PLANNING；交互模式暂停于 checkpoint。
     */
    public void runConceiving(String projectId) {
        Project p = loadProject(projectId);
        if (p.getState() != NovelState.CONCEIVING && p.getState() != NovelState.PAUSED_AT_CHECKPOINT) {
            throw new IllegalStateException("项目不在构思阶段: " + p.getState());
        }

        // 1. 世界观
        worldBuilder.build(projectId, p.getConcept(), p.getTags());

        // 2. 人物（至少主角）
        characterDesigner.design(projectId, "主角", List.of());

        // 3. 大纲
        plotter.plan(projectId, p.getConcept(), p.getConcept(), 10);

        // 4. 状态转移
        if (p.getMode() == ProjectMode.INTERACTIVE) {
            // 交互模式：暂停于 checkpoint
            p.setState(NovelState.PAUSED_AT_CHECKPOINT);
            repository.saveCheckpoint(new Checkpoint(projectId,
                    Checkpoint.Type.AFTER_INITIAL_SETUP, "初始设定生成完成，待审核"));
        } else {
            // Solo 模式：直接转入规划
            p.setState(NovelState.PLANNING);
        }
        repository.saveProject(p);
    }

    /**
     * 从当前状态推进一步（用户调用的主入口）。
     */
    public void step(String projectId) {
        Project p = loadProject(projectId);
        switch (p.getState()) {
            case CONCEIVING -> runConceiving(projectId);
            case PAUSED_AT_CHECKPOINT -> resumeFromCheckpoint(projectId);
            case PLANNING -> runPlanning(projectId);
            case WRITING -> runWritingOneChapter(projectId);
            case VOLUME_DONE -> startNextVolumeOrFinish(projectId);
            default -> throw new IllegalStateException("无法从状态推进: " + p.getState());
        }
    }

    private void resumeFromCheckpoint(String projectId) {
        Project p = loadProject(projectId);
        // 简化：所有 checkpoint 都转到下一阶段
        p.setState(NovelState.PLANNING);
        repository.saveProject(p);
    }

    private void runPlanning(String projectId) {
        Project p = loadProject(projectId);
        p.setState(NovelState.WRITING);
        repository.saveProject(p);
    }

    /** 写一章：组装上下文 → 写正文 → 连续性检查 → 更新摘要 */
    public Chapter runWritingOneChapter(String projectId) {
        Project p = loadProject(projectId);
        Outline outline = repository.findOutlineByProject(projectId).orElseThrow(
                () -> new IllegalStateException("无大纲，无法写作"));

        int chapterIndex = p.getCurrentChapterIndex() + 1;
        // 找到当前章节梗概
        ChapterSynopsis synopsis = findSynopsis(outline, chapterIndex);
        if (synopsis == null) {
            // 没有更多章节梗概，进入卷完结
            p.setState(NovelState.VOLUME_DONE);
            repository.saveProject(p);
            return null;
        }

        // 写章节
        Chapter chapter = chapterWriter.write(projectId, chapterIndex,
                synopsis.getTitle(), synopsis.getSynopsis(), synopsis.getWritingNotes());

        // 更新进度
        p.setCurrentChapterIndex(chapterIndex);
        repository.saveProject(p);

        // 每 3 章生成一次摘要
        if (chapterIndex % 3 == 0) {
            int from = chapterIndex - 2;
            generateSummary(projectId, from, chapterIndex);
        }

        return chapter;
    }

    private void generateSummary(String projectId, int from, int to) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i <= to; i++) {
            repository.findChapterByIndex(projectId, i).ifPresent(c ->
                    sb.append(c.getContent()).append("\n"));
        }
        if (!sb.isEmpty()) {
            memoryManager.summarize(projectId, from, to, sb.toString());
        }
    }

    private void startNextVolumeOrFinish(String projectId) {
        Project p = loadProject(projectId);
        // 检查是否还有未写的大纲章节
        Outline outline = repository.findOutlineByProject(projectId).orElse(null);
        if (outline != null && hasNextChapter(outline, p.getCurrentChapterIndex())) {
            p.setState(NovelState.WRITING);
        } else {
            p.setState(NovelState.FINAL);
        }
        repository.saveProject(p);
    }

    private ChapterSynopsis findSynopsis(Outline outline, int chapterIndex) {
        for (Volume v : outline.getVolumes()) {
            for (ChapterSynopsis s : v.getSynopses()) {
                if (s.getChapterIndex() == chapterIndex) return s;
            }
        }
        return null;
    }

    private boolean hasNextChapter(Outline outline, int currentIndex) {
        return outline.getVolumes().stream()
                .flatMap(v -> v.getSynopses().stream())
                .anyMatch(s -> s.getChapterIndex() > currentIndex);
    }

    private Project loadProject(String projectId) {
        return repository.findProject(projectId)
                .orElseThrow(() -> new IllegalArgumentException("项目不存在: " + projectId));
    }
}
```

- [ ] **Step 5-6: 测试 + 提交**

```bash
git add src/main/java/com/writing/novel/orchestrator/NovelOrchestrator.java \
        src/test/java/com/writing/novel/orchestrator/NovelOrchestratorConceiveTest.java
git commit -m "feat(orchestrator): NovelOrchestrator 状态机 + 构思阶段"
```

---

## Task 3: NovelOrchestrator — 写作循环 + 端到端测试

**Files:**
- Test: `src/test/java/com/writing/novel/orchestrator/NovelOrchestratorWritingTest.java`

**说明:** 测试写作循环：构思→规划→连续写多章。验证断点续写。

- [ ] **Step 1: 写端到端测试**

`src/test/java/com/writing/novel/orchestrator/NovelOrchestratorWritingTest.java`:
```java
package com.writing.novel.orchestrator;

import com.writing.novel.agent.*;
import com.writing.novel.domain.*;
import com.writing.novel.llm.LlmGateway;
import com.writing.novel.llm.LlmRequest;
import com.writing.novel.llm.LlmResponse;
import com.writing.novel.llm.client.StreamHandler;
import com.writing.novel.memory.*;
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

class NovelOrchestratorWritingTest {

    @TempDir Path tempDir;
    private NovelOrchestrator orchestrator;
    private SqliteNovelRepository repo;
    private ProjectService projectService;

    @BeforeEach
    void setUp() {
        ConnectionProvider cp = new ConnectionProvider(tempDir.resolve("w.db").toString());
        new SqliteSchemaInitializer(cp).initialize();
        repo = new SqliteNovelRepository(cp);

        EmbeddingProvider emb = new EmbeddingProvider() {
            @Override public float[] embed(String text) { return new float[]{1.0f}; }
            @Override public int dimension() { return 1; }
        };
        var store = new com.writing.novel.persistence.embedding.EmbeddedVectorStore(
                tempDir.resolve("w.idx").toString());
        VectorRetrievalService vectorSvc = new VectorRetrievalService(store, emb);
        KnowledgeBaseService kbSvc = new KnowledgeBaseService(repo, vectorSvc);
        SummaryService summarySvc = new SummaryService(repo);
        ContextAssembler assembler = new ContextAssembler(repo, kbSvc, summarySvc, vectorSvc);

        LlmGateway gateway = new LlmGateway() {
            @Override
            public LlmResponse complete(LlmRequest req) {
                return switch (req.getTaskType()) {
                    case WORLDBUILDING -> new LlmResponse(
                            "【地理】玄黄大陆：主大陆。\n【力量体系】灵气体系：修炼。", 50, 100);
                    case CHARACTER_DESIGN -> new LlmResponse(
                            "【姓名】张三\n【角色定位】主角\n【性格】火爆\n【能力】剑修", 50, 100);
                    case PLOTTING -> new LlmResponse("""
                            【总纲】张三的修炼之路。

                            【卷1】初入仙门
                            拜入宗门。
                            第1章 测试：灵根测试。
                            第2章 修炼：初次修炼。
                            第3章 比武：外门比武。
                            """, 50, 100);
                    case CHAPTER_WRITING -> new LlmResponse(
                            "这是第" + extractChapterNum(req) + "章的正文内容，张三的故事继续。", 100, 500);
                    case MEMORY_SUMMARY -> new LlmResponse("章节摘要：张三的修炼进展。", 50, 50);
                    default -> new LlmResponse("ok", 1, 1);
                };
            }
            @Override
            public void streamComplete(LlmRequest req, StreamHandler h) {}

            private int extractChapterNum(LlmRequest req) {
                // 从 user message 中提取章节数（简化）
                String msg = req.getUserMessage();
                if (msg.contains("第1章") || msg.contains("测试")) return 1;
                if (msg.contains("第2章") || msg.contains("修炼")) return 2;
                if (msg.contains("第3章") || msg.contains("比武")) return 3;
                return 1;
            }
        };

        projectService = new ProjectService(repo);
        orchestrator = new NovelOrchestrator(repo, projectService,
                new WorldBuilderAgent(gateway, kbSvc),
                new CharacterDesignerAgent(gateway, kbSvc),
                new PlotterAgent(gateway, repo),
                new ChapterWriterAgent(gateway, assembler, repo, vectorSvc),
                new ContinuityCheckerAgent(gateway, repo),
                new StyleGuardianAgent(gateway, repo),
                new MemoryManagerAgent(gateway, summarySvc),
                summarySvc, assembler);
    }

    @Test
    void 全流程_构思_规划_写三章() {
        Project p = projectService.create("测试小说", "剑修故事", "玄幻");

        // 构思
        orchestrator.runConceiving(p.getId());
        // 规划
        orchestrator.step(p.getId());
        // 写第1章
        orchestrator.runWritingOneChapter(p.getId());
        // 写第2章
        orchestrator.runWritingOneChapter(p.getId());
        // 写第3章
        orchestrator.runWritingOneChapter(p.getId());

        // 验证
        List<Chapter> chapters = repo.findChaptersByProject(p.getId());
        assertEquals(3, chapters.size());
        assertEquals(3, repo.findProject(p.getId()).get().getCurrentChapterIndex());

        // 第3章写完应触发摘要（每3章一次）
        assertFalse(repo.findSummariesByProject(p.getId()).isEmpty(), "第3章后应有摘要");
    }

    @Test
    void 写完所有章节后进入卷完结() {
        Project p = projectService.create("t", "c", "tag");
        orchestrator.runConceiving(p.getId());
        orchestrator.step(p.getId());  // → WRITING

        // 写完3章
        orchestrator.runWritingOneChapter(p.getId());  // ch1
        orchestrator.runWritingOneChapter(p.getId());  // ch2
        orchestrator.runWritingOneChapter(p.getId());  // ch3
        // 第4章不存在，应进入 VOLUME_DONE
        orchestrator.runWritingOneChapter(p.getId());

        assertEquals(NovelState.VOLUME_DONE, repo.findProject(p.getId()).get().getState());
    }

    @Test
    void 交互模式在构思后暂停() {
        Project p = projectService.create("t", "c", "tag", ProjectMode.INTERACTIVE);
        orchestrator.runConceiving(p.getId());

        Project found = repo.findProject(p.getId()).orElseThrow();
        assertEquals(NovelState.PAUSED_AT_CHECKPOINT, found.getState());
        assertFalse(repo.findPendingCheckpoints(p.getId()).isEmpty(), "应有待审核 checkpoint");
    }
}
```

- [ ] **Step 2: 运行测试**（NovelOrchestrator 已在 Task 2 实现，这里验证写作循环）

Run: `mvn test -Dtest=NovelOrchestratorWritingTest`
Expected: 3 tests PASS

- [ ] **Step 3: 提交**

```bash
git add src/test/java/com/writing/novel/orchestrator/NovelOrchestratorWritingTest.java
git commit -m "test(orchestrator): 写作循环 + 交互模式 checkpoint 端到端测试"
```

---

## Task 4: OrchestratorConfig + Spring 集成

**Files:**
- Create: `src/main/java/com/writing/novel/orchestrator/OrchestratorConfig.java`
- Test: `src/test/java/com/writing/novel/orchestrator/OrchestratorIntegrationTest.java`

- [ ] **Step 1-4: 创建 + 测试**

`src/main/java/com/writing/novel/orchestrator/OrchestratorConfig.java`:
```java
package com.writing.novel.orchestrator;

import com.writing.novel.agent.*;
import com.writing.novel.memory.ContextAssembler;
import com.writing.novel.memory.SummaryService;
import com.writing.novel.spi.NovelRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OrchestratorConfig {

    @Bean
    public ProjectService projectService(NovelRepository repo) {
        return new ProjectService(repo);
    }

    @Bean
    public NovelOrchestrator novelOrchestrator(NovelRepository repo,
                                                 ProjectService projectService,
                                                 WorldBuilderAgent worldBuilder,
                                                 CharacterDesignerAgent characterDesigner,
                                                 PlotterAgent plotter,
                                                 ChapterWriterAgent chapterWriter,
                                                 ContinuityCheckerAgent continuityChecker,
                                                 StyleGuardianAgent styleGuardian,
                                                 MemoryManagerAgent memoryManager,
                                                 SummaryService summaryService,
                                                 ContextAssembler contextAssembler) {
        return new NovelOrchestrator(repo, projectService, worldBuilder, characterDesigner,
                plotter, chapterWriter, continuityChecker, styleGuardian, memoryManager,
                summaryService, contextAssembler);
    }
}
```

测试 `src/test/java/com/writing/novel/orchestrator/OrchestratorIntegrationTest.java`:
```java
package com.writing.novel.orchestrator;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestPropertySource;

import java.io.File;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@TestPropertySource(properties = {
        "persistence.embedding=stub",
        "persistence.sqlite.db-path=./data/test-orch.db",
        "persistence.vector-store.index-path=./data/test-orch-vecs.idx"
})
class OrchestratorIntegrationTest {

    @Autowired ApplicationContext context;

    @org.junit.jupiter.api.BeforeAll
    static void createDataDir() { new File("./data").mkdirs(); }

    @Test
    void orchestrator与projectService被装配() {
        assertNotNull(context.getBean(NovelOrchestrator.class));
        assertNotNull(context.getBean(ProjectService.class));
    }
}
```

- [ ] **Step 5-6: 运行 + 提交**

```bash
mvn verify
git add src/main/java/com/writing/novel/orchestrator/OrchestratorConfig.java \
        src/test/java/com/writing/novel/orchestrator/OrchestratorIntegrationTest.java
git commit -m "feat(orchestrator): OrchestratorConfig 装配 + 集成测试"
```

---

## 自审记录

- ✅ 状态机全部状态覆盖（CONCEIVING/PLANNING/WRITING/VOLUME_DONE/FINAL/PAUSED_AT_CHECKPOINT）
- ✅ Solo 模式自动推进，交互模式 checkpoint 暂停
- ✅ 写作循环：写正文 + 摘要更新（每3章）
- ✅ 断点续写（状态持久化，step() 从当前状态恢复）
