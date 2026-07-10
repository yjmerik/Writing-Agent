# Plan 4: 七个 Agent 实现

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 实现全部 7 个 Agent（WorldBuilder / CharacterDesigner / Plotter / ChapterWriter / ContinuityChecker / StyleGuardian / MemoryManager），每个是无状态技能单元，吃 LlmGateway + Memory 服务，产出结构化领域对象。

**Architecture:** 统一的 Agent 接口模式——每个 Agent 有一个核心方法（如 `Worldbuilding build(projectId, concept)`），内部：组装 prompt → 调 LlmGateway → 解析响应 → 写入记忆层 → 返回领域对象。所有 Agent 无状态，由 Orchestrator（Plan 5）调度。

**Tech Stack:** Spring Boot 3, Java 17, LlmGateway (Plan 2), Memory 服务 (Plan 3)。

**Spec 参考:** spec 第 4 节（Agent 角色定义）。

---

## 文件结构

```
src/main/java/com/writing/novel/agent/
├── WorldBuilderAgent.java         # 世界观生成
├── CharacterDesignerAgent.java    # 人物设计
├── PlotterAgent.java              # 大纲规划
├── ChapterWriterAgent.java        # 正文写作（核心，用 ContextAssembler）
├── ContinuityCheckerAgent.java    # 连续性校验
├── StyleGuardianAgent.java        # 风格守护
├── MemoryManagerAgent.java        # 摘要生成
├── AgentConfig.java               # Bean 装配
└── util/
    └── ResponseParser.java        # LLM 响应解析工具
```

---

## Task 1: ResponseParser 工具 + WorldBuilderAgent

**Files:**
- Create: `src/main/java/com/writing/novel/agent/util/ResponseParser.java`
- Create: `src/main/java/com/writing/novel/agent/WorldBuilderAgent.java`
- Test: `src/test/java/com/writing/novel/agent/WorldBuilderAgentTest.java`

- [ ] **Step 1: 写失败测试**

`src/test/java/com/writing/novel/agent/WorldBuilderAgentTest.java`:
```java
package com.writing.novel.agent;

import com.writing.novel.domain.KnowledgeEntry;
import com.writing.novel.domain.Worldbuilding;
import com.writing.novel.llm.LlmGateway;
import com.writing.novel.llm.LlmRequest;
import com.writing.novel.llm.LlmResponse;
import com.writing.novel.llm.LlmTaskType;
import com.writing.novel.memory.KnowledgeBaseService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WorldBuilderAgentTest {

    @Test
    void 从构思生成世界观条目() {
        // Mock LlmGateway：返回结构化世界观
        LlmGateway mockGateway = req -> new LlmResponse("""
            【地理】玄黄大陆：故事主大陆，分东西两域。
            【力量体系】灵气体系：修炼者吸收天地灵气，境界分九重。
            【势力】青云宗：玄黄大陆东部第一大宗。
            """, 100, 200);

        // Mock KnowledgeBaseService：记录写入
        KnowledgeBaseService mockKb = new KnowledgeBaseService(null, null) {
            @Override public void saveEntry(KnowledgeEntry e) { /* 记录测试 */ }
            @Override public List<KnowledgeEntry> findAllEntries(String pid) { return List.of(); }
        };

        WorldBuilderAgent agent = new WorldBuilderAgent(mockGateway, mockKb);
        Worldbuilding wb = agent.build("proj-1", "一个剑修复仇的玄幻故事", "玄幻,热血");

        assertNotNull(wb);
        assertFalse(wb.getEntries().isEmpty(), "应生成世界观条目");
        // 验证至少包含地理和力量体系
        assertTrue(wb.getEntries().stream().anyMatch(e -> e.getName().contains("玄黄大陆")));
        assertTrue(wb.getEntries().stream().anyMatch(e -> e.getName().contains("灵气")));
    }
}
```

- [ ] **Step 2: 运行确认失败**

- [ ] **Step 3: 创建 ResponseParser**

`src/main/java/com/writing/novel/agent/util/ResponseParser.java`:
```java
package com.writing.novel.agent.util;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LLM 响应解析工具。从自由文本中提取结构化条目。
 * 支持格式：【类型】名称：内容
 */
public class ResponseParser {

    private static final Pattern ENTRY_PATTERN =
            Pattern.compile("【([^】]+)】([^：:]+)[：:]\\s*(.+)");

    /** 解析【类型】名称：内容 格式的条目 */
    public static List<ParsedEntry> parseEntries(String text) {
        List<ParsedEntry> entries = new ArrayList<>();
        if (text == null) return entries;
        for (String line : text.split("\n")) {
            line = line.trim();
            if (line.isEmpty()) continue;
            Matcher m = ENTRY_PATTERN.matcher(line);
            if (m.find()) {
                entries.add(new ParsedEntry(
                        m.group(1).trim(),   // type
                        m.group(2).trim(),   // name
                        m.group(3).trim())); // content
            }
        }
        return entries;
    }

    public record ParsedEntry(String type, String name, String content) {}
}
```

- [ ] **Step 4: 创建 WorldBuilderAgent**

`src/main/java/com/writing/novel/agent/WorldBuilderAgent.java`:
```java
package com.writing.novel.agent;

import com.writing.novel.agent.util.ResponseParser;
import com.writing.novel.agent.util.ResponseParser.ParsedEntry;
import com.writing.novel.domain.KnowledgeEntry;
import com.writing.novel.domain.Worldbuilding;
import com.writing.novel.llm.LlmGateway;
import com.writing.novel.llm.LlmRequest;
import com.writing.novel.llm.LlmTaskType;
import com.writing.novel.memory.KnowledgeBaseService;

/**
 * 世界观构建 Agent。
 * 输入构思简介+类型标签 → 调 LLM → 解析为世界观条目 → 写入知识库。
 */
public class WorldBuilderAgent {

    private final LlmGateway gateway;
    private final KnowledgeBaseService knowledgeBase;

    public WorldBuilderAgent(LlmGateway gateway, KnowledgeBaseService knowledgeBase) {
        this.gateway = gateway;
        this.knowledgeBase = knowledgeBase;
    }

    public Worldbuilding build(String projectId, String concept, String tags) {
        String systemPrompt = """
                你是网络小说世界观设定专家。根据用户的构思，生成完整的世界观设定。
                必须覆盖：地理环境、力量体系（修炼/魔法等）、主要势力、历史背景、核心规则。
                严格按以下格式输出，每行一条：
                【类型】名称：内容描述
                类型包括：地理、力量体系、势力、历史、规则。
                """;

        String userMessage = "构思：" + concept + "\n类型标签：" + tags
                + "\n请生成 5-8 条世界观设定。";

        var req = LlmRequest.builder()
                .taskType(LlmTaskType.WORLDBUILDING)
                .systemPrompt(systemPrompt)
                .userMessage(userMessage)
                .temperature(0.9)
                .maxTokens(2000)
                .build();

        var resp = gateway.complete(req);

        // 解析响应
        Worldbuilding wb = new Worldbuilding(projectId);
        for (ParsedEntry pe : ResponseParser.parseEntries(resp.getContent())) {
            KnowledgeEntry entry = new KnowledgeEntry(projectId,
                    normalizeType(pe.type()), pe.name(), pe.content());
            wb.addEntry(entry);
            knowledgeBase.saveEntry(entry);  // 写入知识库 + 索引
        }
        return wb;
    }

    private String normalizeType(String chineseType) {
        return switch (chineseType) {
            case "地理" -> "GEOGRAPHY";
            case "力量体系" -> "POWER_SYSTEM";
            case "势力" -> "FACTION";
            case "历史" -> "HISTORY";
            case "规则" -> "RULE";
            default -> "CUSTOM";
        };
    }
}
```

- [ ] **Step 5: 运行测试通过**

- [ ] **Step 6: 提交**

```bash
git add src/main/java/com/writing/novel/agent/util/ResponseParser.java \
        src/main/java/com/writing/novel/agent/WorldBuilderAgent.java \
        src/test/java/com/writing/novel/agent/WorldBuilderAgentTest.java
git commit -m "feat(agent): ResponseParser 工具 + WorldBuilderAgent"
```

---

## Task 2: CharacterDesignerAgent

**Files:**
- Create: `src/main/java/com/writing/novel/agent/CharacterDesignerAgent.java`
- Test: `src/test/java/com/writing/novel/agent/CharacterDesignerAgentTest.java`

- [ ] **Step 1: 写失败测试**

`src/test/java/com/writing/novel/agent/CharacterDesignerAgentTest.java`:
```java
package com.writing.novel.agent;

import com.writing.novel.domain.Character;
import com.writing.novel.domain.KnowledgeEntry;
import com.writing.novel.llm.LlmGateway;
import com.writing.novel.llm.LlmResponse;
import com.writing.novel.memory.KnowledgeBaseService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CharacterDesignerAgentTest {

    @Test
    void 从需求生成主角人物卡() {
        LlmGateway mockGateway = req -> new LlmResponse("""
            【姓名】张三
            【角色定位】主角
            【性格】火爆冲动，重情重义，宁折不弯。
            【能力】先天剑骨，修炼速度常人十倍，擅长剑意斩击。
            【动机】为被灭门的师门复仇，追寻真相。
            【外貌】黑发黑瞳，身形修长，常穿青衫，眉宇间有剑纹。
            【人物关系】与青云宗宗主有师徒之缘；与反派"血煞门"门主有血海深仇。
            【成长弧】从冲动少年成长为沉稳剑道宗师。
            """, 100, 300);

        KnowledgeBaseService mockKb = new KnowledgeBaseService(null, null) {
            @Override public void saveCharacter(Character c) {}
            @Override public List<Character> findAllCharacters(String pid) { return List.of(); }
        };

        CharacterDesignerAgent agent = new CharacterDesignerAgent(mockGateway, mockKb);
        Character c = agent.design("proj-1", "主角，复仇者", List.of());

        assertNotNull(c);
        assertEquals("张三", c.getName());
        assertEquals("主角", c.getRole());
        assertEquals("火爆冲动，重情重义，宁折不弯。", c.getPersonality());
        assertTrue(c.getAbility().contains("剑骨"));
        assertTrue(c.getMotivation().contains("复仇"));
    }
}
```

- [ ] **Step 2-5: 实现 CharacterDesignerAgent**

`src/main/java/com/writing/novel/agent/CharacterDesignerAgent.java`:
```java
package com.writing.novel.agent;

import com.writing.novel.domain.Character;
import com.writing.novel.llm.LlmGateway;
import com.writing.novel.llm.LlmRequest;
import com.writing.novel.llm.LlmTaskType;
import com.writing.novel.memory.KnowledgeBaseService;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 人物设计 Agent。根据剧情需求 + 世界观生成人物卡。
 */
public class CharacterDesignerAgent {

    private final LlmGateway gateway;
    private final KnowledgeBaseService knowledgeBase;

    public CharacterDesignerAgent(LlmGateway gateway, KnowledgeBaseService knowledgeBase) {
        this.gateway = gateway;
        this.knowledgeBase = knowledgeBase;
    }

    public Character design(String projectId, String requirement, List<Character> existing) {
        String existingDesc = existing.isEmpty() ? "（暂无已有人物）" :
                existing.stream().map(c -> c.getName() + "（" + c.getRole() + "）")
                        .reduce((a, b) -> a + "、" + b).orElse("");

        String systemPrompt = """
                你是网络小说人物设计专家。根据剧情需求设计一个立体的人物。
                严格按以下格式输出：
                【姓名】...
                【角色定位】主角/配角/反派
                【性格】...
                【能力】...
                【动机】...
                【外貌】...
                【人物关系】...
                【成长弧】...
                """;

        String userMessage = "剧情需求：" + requirement
                + "\n已有人物：" + existingDesc
                + "\n请设计一个新人物，与已有人物不重复。";

        var req = LlmRequest.builder()
                .taskType(LlmTaskType.CHARACTER_DESIGN)
                .systemPrompt(systemPrompt)
                .userMessage(userMessage)
                .temperature(0.85)
                .maxTokens(1500)
                .build();

        var resp = gateway.complete(req);
        Character c = parseCharacter(resp.getContent(), projectId);
        knowledgeBase.saveCharacter(c);
        return c;
    }

    private Character parseCharacter(String text, String projectId) {
        String name = extractField(text, "姓名", "未命名");
        String role = extractField(text, "角色定位", "配角");
        Character c = new Character(projectId, name, role);
        c.setPersonality(extractFieldOrNull(text, "性格"));
        c.setAbility(extractFieldOrNull(text, "能力"));
        c.setMotivation(extractFieldOrNull(text, "动机"));
        c.setAppearance(extractFieldOrNull(text, "外貌"));
        c.setRelationships(extractFieldOrNull(text, "人物关系"));
        c.setGrowthArc(extractFieldOrNull(text, "成长弧"));
        return c;
    }

    private String extractField(String text, String field, String defaultValue) {
        String v = extractFieldOrNull(text, field);
        return v != null ? v : defaultValue;
    }

    private String extractFieldOrNull(String text, String field) {
        Pattern p = Pattern.compile("【" + Pattern.quote(field) + "】(.+?)(?=【|$)", Pattern.DOTALL);
        Matcher m = p.matcher(text);
        return m.find() ? m.group(1).trim() : null;
    }
}
```

- [ ] **Step 6: 提交**

```bash
git add src/main/java/com/writing/novel/agent/CharacterDesignerAgent.java \
        src/test/java/com/writing/novel/agent/CharacterDesignerAgentTest.java
git commit -m "feat(agent): CharacterDesignerAgent 人物设计"
```

---

## Task 3: PlotterAgent

**Files:**
- Create: `src/main/java/com/writing/novel/agent/PlotterAgent.java`
- Test: `src/test/java/com/writing/novel/agent/PlotterAgentTest.java`

- [ ] **Step 1: 写失败测试**

`src/test/java/com/writing/novel/agent/PlotterAgentTest.java`:
```java
package com.writing.novel.agent;

import com.writing.novel.domain.Outline;
import com.writing.novel.domain.Volume;
import com.writing.novel.llm.LlmGateway;
import com.writing.novel.llm.LlmResponse;
import com.writing.novel.spi.NovelRepository;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PlotterAgentTest {

    @Test
    void 生成全书大纲与卷() {
        LlmGateway mockGateway = req -> new LlmResponse("""
            【总纲】张三从凡人少年成长为剑道宗师，期间经历灭门、拜师、复仇、悟道，最终证道飞升。

            【卷1】初入仙门
            张三家破人亡，机缘巧合拜入青云宗，开始修炼之路。
            第1章 灵根测试：张三参加灵根测试，意外觉醒上古剑骨。
            第2章 拜入宗门：张三正式成为青云宗外门弟子。
            第3章 初次修炼：张三开始修炼基础剑法，展现惊人天赋。

            【卷2】宗门风云
            张三在宗门中崭露头角，卷入内门争斗。
            第4章 外门比武：张三参加外门比武大会。
            第5章 结怨：张三与内门弟子结下梁子。
            """, 100, 500);

        NovelRepository mockRepo = new NovelRepositoryStub();

        PlotterAgent agent = new PlotterAgent(mockGateway, mockRepo);
        Outline outline = agent.plan("proj-1", "剑修复仇玄幻故事",
                "主角从凡人修炼至大乘", 3);

        assertNotNull(outline);
        assertEquals("张三从凡人少年成长为剑道宗师", outline.getPremise().substring(0, 14));
        assertEquals(2, outline.getVolumes().size());
        assertEquals("初入仙门", outline.getVolumes().get(0).getTitle());
        assertEquals(3, outline.getVolumes().get(0).getSynopses().size());
        assertEquals(2, outline.getVolumes().get(1).getSynopses().size());
    }
}
```

需要测试用的 NovelRepository stub。在测试文件中加一个最简 stub（只实现用到的方法，其余抛 UnsupportedOperationException）。由于 NovelRepository 接口很大，用匿名类会很冗长。更简洁的方式：创建一个测试辅助类。

在测试文件底部加：
```java
    /** 最简 stub，仅实现 saveOutline */
    static class NovelRepositoryStub implements com.writing.novel.spi.NovelRepository {
        public void saveOutline(com.writing.novel.domain.Outline o) { /* 测试不验证写入 */ }
        // 其余方法默认抛异常（用默认方法或留空）
        public String saveProject(com.writing.novel.domain.Project p) { return null; }
        public java.util.Optional<com.writing.novel.domain.Project> findProject(String id) { return java.util.Optional.empty(); }
        public java.util.List<com.writing.novel.domain.Project> findAllProjects() { return java.util.List.of(); }
        public void deleteProject(String id) {}
        public void saveKnowledgeEntry(com.writing.novel.domain.KnowledgeEntry e) {}
        public java.util.List<com.writing.novel.domain.KnowledgeEntry> findKnowledgeEntriesByProject(String p) { return java.util.List.of(); }
        public java.util.Optional<com.writing.novel.domain.KnowledgeEntry> findKnowledgeEntry(String id) { return java.util.Optional.empty(); }
        public java.util.List<com.writing.novel.domain.KnowledgeEntry> findKnowledgeEntriesByType(String p, String t) { return java.util.List.of(); }
        public String saveCharacter(com.writing.novel.domain.Character c) { return null; }
        public java.util.Optional<com.writing.novel.domain.Character> findCharacter(String id) { return java.util.Optional.empty(); }
        public java.util.List<com.writing.novel.domain.Character> findCharactersByProject(String p) { return java.util.List.of(); }
        public java.util.Optional<com.writing.novel.domain.Outline> findOutlineByProject(String p) { return java.util.Optional.empty(); }
        public String saveChapter(com.writing.novel.domain.Chapter c) { return null; }
        public java.util.Optional<com.writing.novel.domain.Chapter> findChapter(String id) { return java.util.Optional.empty(); }
        public java.util.Optional<com.writing.novel.domain.Chapter> findChapterByIndex(String p, int i) { return java.util.Optional.empty(); }
        public java.util.List<com.writing.novel.domain.Chapter> findChaptersByProject(String p) { return java.util.List.of(); }
        public java.util.List<com.writing.novel.domain.Chapter> findRecentChapters(String p, int c) { return java.util.List.of(); }
        public void saveSummary(com.writing.novel.domain.Summary s) {}
        public java.util.List<com.writing.novel.domain.Summary> findSummariesByProject(String p) { return java.util.List.of(); }
        public void saveForeshadowing(com.writing.novel.domain.Foreshadowing f) {}
        public java.util.List<com.writing.novel.domain.Foreshadowing> findForeshadowingsByProject(String p) { return java.util.List.of(); }
        public java.util.List<com.writing.novel.domain.Foreshadowing> findUnresolvedForeshadowings(String p) { return java.util.List.of(); }
        public void saveStyleProfile(com.writing.novel.domain.StyleProfile p) {}
        public java.util.Optional<com.writing.novel.domain.StyleProfile> findStyleProfileByProject(String p) { return java.util.Optional.empty(); }
        public void saveContinuityIssue(com.writing.novel.domain.ContinuityIssue i) {}
        public java.util.List<com.writing.novel.domain.ContinuityIssue> findContinuityIssuesByProject(String p) { return java.util.List.of(); }
        public java.util.List<com.writing.novel.domain.ContinuityIssue> findUnresolvedIssues(String p) { return java.util.List.of(); }
        public String saveCheckpoint(com.writing.novel.domain.Checkpoint c) { return null; }
        public java.util.Optional<com.writing.novel.domain.Checkpoint> findCheckpoint(String id) { return java.util.Optional.empty(); }
        public java.util.List<com.writing.novel.domain.Checkpoint> findPendingCheckpoints(String p) { return java.util.List.of(); }
    }
```

> **注意：** 这个 stub 很长但必要。后续 Agent 测试都需要它。考虑放在 `src/test/java/com/writing/novel/agent/TestNovelRepositoryStub.java` 作为共享测试辅助。

- [ ] **Step 2-5: 实现 PlotterAgent**

`src/main/java/com/writing/novel/agent/PlotterAgent.java`:
```java
package com.writing.novel.agent;

import com.writing.novel.domain.*;
import com.writing.novel.llm.LlmGateway;
import com.writing.novel.llm.LlmRequest;
import com.writing.novel.llm.LlmTaskType;
import com.writing.novel.spi.NovelRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 大纲规划 Agent。生成全书总纲 + 分卷 + 章节梗概。
 */
public class PlotterAgent {

    private final LlmGateway gateway;
    private final NovelRepository repository;

    public PlotterAgent(LlmGateway gateway, NovelRepository repository) {
        this.gateway = gateway;
        this.repository = repository;
    }

    public Outline plan(String projectId, String concept, String theme, int totalChapters) {
        String systemPrompt = """
                你是网络小说大纲规划专家。根据构思生成分层大纲：总纲 + 多卷 + 每卷的章节梗概。
                严格按以下格式输出：

                【总纲】全书主线一句话概括。

                【卷N】卷名
                本卷梗概描述。
                第X章 章节名：本章内容梗概。
                第Y章 章节名：本章内容梗概。

                【卷M】卷名
                ...
                """;

        String userMessage = "构思：" + concept + "\n主题：" + theme
                + "\n预计总章数：" + totalChapters
                + "\n请规划 2-3 卷，每卷 3-5 章。";

        var req = LlmRequest.builder()
                .taskType(LlmTaskType.PLOTTING)
                .systemPrompt(systemPrompt)
                .userMessage(userMessage)
                .temperature(0.8)
                .maxTokens(3000)
                .build();

        var resp = gateway.complete(req);
        Outline outline = parseOutline(resp.getContent(), projectId);
        repository.saveOutline(outline);
        return outline;
    }

    private Outline parseOutline(String text, String projectId) {
        // 提取总纲
        String premise = "";
        Matcher premM = Pattern.compile("【总纲】(.+?)(?=【卷|$)", Pattern.DOTALL).matcher(text);
        if (premM.find()) premise = premM.group(1).trim();

        Outline outline = new Outline(projectId, premise);

        // 提取卷
        Matcher volM = Pattern.compile("【卷(\\d+)】(.+?)\\n(.+?)(?=【卷|【伏笔|$)", Pattern.DOTALL).matcher(text);
        int chapterCounter = 0;
        while (volM.find()) {
            int volIndex = Integer.parseInt(volM.group(1));
            String volTitle = volM.group(2).trim();
            String volBody = volM.group(3).trim();
            // 分离卷梗概（第一行）和章节
            String volSummary = volBody.split("\\n")[0].trim();
            Volume volume = new Volume(projectId, volIndex, volTitle, volSummary);

            // 提取章节
            Matcher chM = Pattern.compile("第(\\d+)章\\s*(.+?)：(.+)").matcher(volBody);
            while (chM.find()) {
                chapterCounter++;
                int idx = Integer.parseInt(chM.group(1));
                String title = chM.group(2).trim();
                String synopsis = chM.group(3).trim();
                ChapterSynopsis cs = new ChapterSynopsis(projectId, idx, title, synopsis);
                volume.addSynopsis(cs);
            }
            outline.addVolume(volume);
        }
        return outline;
    }
}
```

- [ ] **Step 6: 提交**

```bash
git add src/main/java/com/writing/novel/agent/PlotterAgent.java \
        src/test/java/com/writing/novel/agent/PlotterAgentTest.java \
        src/test/java/com/writing/novel/agent/TestNovelRepositoryStub.java
git commit -m "feat(agent): PlotterAgent 大纲规划 + 共享测试 stub"
```

---

## Task 4: ChapterWriterAgent（核心！用 ContextAssembler）

**Files:**
- Create: `src/main/java/com/writing/novel/agent/ChapterWriterAgent.java`
- Test: `src/test/java/com/writing/novel/agent/ChapterWriterAgentTest.java`

- [ ] **Step 1: 写失败测试**

`src/test/java/com/writing/novel/agent/ChapterWriterAgentTest.java`:
```java
package com.writing.novel.agent;

import com.writing.novel.domain.Chapter;
import com.writing.novel.llm.LlmGateway;
import com.writing.novel.llm.LlmResponse;
import com.writing.novel.memory.ContextAssembler;
import com.writing.novel.memory.WritingContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ChapterWriterAgentTest {

    @Test
    void 组装上下文并写出章节() {
        // Mock ContextAssembler：返回预设上下文
        WritingContext mockCtx = new WritingContext();
        mockCtx.setStyleSection("热血风格");
        mockCtx.setTaskSection("第一章 张三登场");
        ContextAssembler mockAssembler = (projectId, chapterIndex, title, synopsis, notes) -> mockCtx;

        // Mock Gateway：返回正文
        LlmGateway mockGateway = req -> new LlmResponse(
                "张三踏入青云宗山门，剑意凛然，这是正文内容……", 200, 800);

        ChapterWriterAgent agent = new ChapterWriterAgent(mockGateway, mockAssembler, null);
        Chapter ch = agent.write("proj-1", 1, "第一章 张三登场",
                "张三拜入山门", "3000字");

        assertNotNull(ch);
        assertEquals(1, ch.getChapterIndex());
        assertEquals("第一章 张三登场", ch.getTitle());
        assertTrue(ch.getContent().contains("张三"));
        assertTrue(ch.getWordCount() > 0);
    }

    @Test
    void 写完后保存章节到仓库() {
        WritingContext mockCtx = new WritingContext();
        ContextAssembler mockAssembler = (p, i, t, s, n) -> mockCtx;
        LlmGateway mockGateway = req -> new LlmResponse("正文内容", 10, 20);

        boolean[] saved = {false};
        com.writing.novel.spi.NovelRepository mockRepo = new TestNovelRepositoryStub() {
            @Override
            public String saveChapter(Chapter c) { saved[0] = true; return c.getId(); }
        };

        ChapterWriterAgent agent = new ChapterWriterAgent(mockGateway, mockAssembler, mockRepo);
        agent.write("p1", 1, "ch1", "synopsis", null);

        assertTrue(saved[0], "写完后应保存章节到仓库");
    }
}
```

注意：`ContextAssembler` 是类不是接口，不能直接用 lambda。需要改为：测试中用匿名子类或 mock。修正测试——实际上 ContextAssembler 有 `assemble` 方法，lambda 不行。改用匿名类：

```java
        ContextAssembler mockAssembler = new ContextAssembler(null, null, null, null) {
            @Override
            public WritingContext assemble(String projectId, int chapterIndex,
                                           String chapterTitle, String chapterSynopsis,
                                           String writingNotes) {
                return mockCtx;
            }
        };
```

- [ ] **Step 2-5: 实现 ChapterWriterAgent**

`src/main/java/com/writing/novel/agent/ChapterWriterAgent.java`:
```java
package com.writing.novel.agent;

import com.writing.novel.domain.Chapter;
import com.writing.novel.llm.LlmGateway;
import com.writing.novel.llm.LlmRequest;
import com.writing.novel.llm.LlmTaskType;
import com.writing.novel.memory.ContextAssembler;
import com.writing.novel.memory.WritingContext;
import com.writing.novel.memory.VectorRetrievalService;
import com.writing.novel.memory.IndexedText;
import com.writing.novel.spi.NovelRepository;

/**
 * 章节写作 Agent（核心）。
 * 用 ContextAssembler 组装完整上下文 → 调 LLM 写正文 → 保存 + 索引。
 */
public class ChapterWriterAgent {

    private final LlmGateway gateway;
    private final ContextAssembler contextAssembler;
    private final NovelRepository repository;
    private final VectorRetrievalService vectorRetrieval;

    public ChapterWriterAgent(LlmGateway gateway, ContextAssembler contextAssembler,
                               NovelRepository repository, VectorRetrievalService vectorRetrieval) {
        this.gateway = gateway;
        this.contextAssembler = contextAssembler;
        this.repository = repository;
        this.vectorRetrieval = vectorRetrieval;
    }

    /** 兼容测试的三参构造（vectorRetrieval 可为 null，仅测试用） */
    public ChapterWriterAgent(LlmGateway gateway, ContextAssembler contextAssembler,
                               NovelRepository repository) {
        this(gateway, contextAssembler, repository, null);
    }

    public Chapter write(String projectId, int chapterIndex, String chapterTitle,
                         String chapterSynopsis, String writingNotes) {
        // 1. 组装上下文
        WritingContext ctx = contextAssembler.assemble(
                projectId, chapterIndex, chapterTitle, chapterSynopsis, writingNotes);

        // 2. 构建 LLM 请求
        String systemPrompt = ctx.toSystemPrompt() + "\n\n你是网络小说写作高手。" +
                "请根据提供的上下文写出本章正文，保持文风一致，注意与前文衔接。";

        var req = LlmRequest.builder()
                .taskType(com.writing.novel.llm.LlmTaskType.CHAPTER_WRITING)
                .systemPrompt(systemPrompt)
                .userMessage(ctx.toUserPrompt())
                .temperature(0.8)
                .maxTokens(3000)
                .build();

        // 3. 调 LLM 写正文
        var resp = gateway.complete(req);

        // 4. 构建 Chapter 并保存
        Chapter chapter = new Chapter(projectId, chapterIndex, chapterTitle);
        chapter.setContent(resp.getContent());
        repository.saveChapter(chapter);

        // 5. 索引章节正文到向量库（供后续语义检索）
        if (vectorRetrieval != null) {
            vectorRetrieval.index(new IndexedText(
                    chapter.getId(), projectId,
                    "第" + chapterIndex + "章 " + chapterTitle + "：" + resp.getContent(),
                    "CHAPTER"));
        }

        return chapter;
    }
}
```

- [ ] **Step 6: 提交**

```bash
git add src/main/java/com/writing/novel/agent/ChapterWriterAgent.java \
        src/test/java/com/writing/novel/agent/ChapterWriterAgentTest.java
git commit -m "feat(agent): ChapterWriterAgent 章节写作（核心，用 ContextAssembler）"
```

---

## Task 5: ContinuityCheckerAgent + StyleGuardianAgent

**Files:**
- Create: `src/main/java/com/writing/novel/agent/ContinuityCheckerAgent.java`
- Create: `src/main/java/com/writing/novel/agent/StyleGuardianAgent.java`
- Test: `src/test/java/com/writing/novel/agent/ContinuityCheckerAgentTest.java`
- Test: `src/test/java/com/writing/novel/agent/StyleGuardianAgentTest.java`

- [ ] **Step 1: ContinuityChecker 测试**

`src/test/java/com/writing/novel/agent/ContinuityCheckerAgentTest.java`:
```java
package com.writing.novel.agent;

import com.writing.novel.domain.ContinuityIssue;
import com.writing.novel.domain.StyleProfile;
import com.writing.novel.llm.LlmGateway;
import com.writing.novel.llm.LlmResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ContinuityCheckerAgentTest {

    @Test
    void 检测到矛盾则生成issue() {
        LlmGateway mockGateway = req -> new LlmResponse("""
            发现以下连续性问题：
            【人物|高】张三的佩剑名前后不一致：第3章叫"断水"，第12章叫"霜寒"。
            【世界观|中】第8章提到的"九重境界"与设定中的"灵气体系"描述矛盾。
            """, 50, 100);

        ContinuityCheckerAgent agent = new ContinuityCheckerAgent(mockGateway, null);
        List<ContinuityIssue> issues = agent.check("proj-1", 12,
                "第12章正文……", "人物设定：张三用断水剑");

        assertEquals(2, issues.size());
        assertEquals(ContinuityIssue.Severity.HIGH, issues.get(0).getSeverity());
        assertEquals(ContinuityIssue.Type.CHARACTER, issues.get(0).getType());
        assertTrue(issues.get(0).getDescription().contains("佩剑"));
    }

    @Test
    void 无矛盾则返回空列表() {
        LlmGateway mockGateway = req -> new LlmResponse("未发现连续性问题。", 10, 10);
        ContinuityCheckerAgent agent = new ContinuityCheckerAgent(mockGateway, null);
        List<ContinuityIssue> issues = agent.check("p1", 5, "正文", "设定");
        assertTrue(issues.isEmpty());
    }
}
```

- [ ] **Step 2: 实现 ContinuityCheckerAgent**

`src/main/java/com/writing/novel/agent/ContinuityCheckerAgent.java`:
```java
package com.writing.novel.agent;

import com.writing.novel.domain.ContinuityIssue;
import com.writing.novel.llm.LlmGateway;
import com.writing.novel.llm.LlmRequest;
import com.writing.novel.llm.LlmTaskType;
import com.writing.novel.spi.NovelRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 连续性校验 Agent。检查新章节是否与已有设定矛盾。
 */
public class ContinuityCheckerAgent {

    private final LlmGateway gateway;
    private final NovelRepository repository;

    public ContinuityCheckerAgent(LlmGateway gateway, NovelRepository repository) {
        this.gateway = gateway;
        this.repository = repository;
    }

    public List<ContinuityIssue> check(String projectId, int chapterIndex,
                                        String chapterContent, String relevantSettings) {
        String systemPrompt = """
                你是小说连续性审查员。检查新章节是否与已有设定矛盾。
                如果发现问题，严格按以下格式每行一条输出：
                【类型|严重度】问题描述
                类型：人物/世界观/时间线/伏笔
                严重度：高/中/低
                如果没有问题，回复"未发现连续性问题。"
                """;

        String userMessage = "新章节内容：\n" + chapterContent
                + "\n\n相关设定：\n" + relevantSettings;

        var req = LlmRequest.builder()
                .taskType(LlmTaskType.CONTINUITY_CHECK)
                .systemPrompt(systemPrompt)
                .userMessage(userMessage)
                .temperature(0.2)
                .maxTokens(1000)
                .build();

        var resp = gateway.complete(req);
        List<ContinuityIssue> issues = parseIssues(resp.getContent(), projectId, chapterIndex);

        // 保存 issue 到仓库
        if (repository != null) {
            for (ContinuityIssue issue : issues) {
                repository.saveContinuityIssue(issue);
            }
        }
        return issues;
    }

    private List<ContinuityIssue> parseIssues(String text, String projectId, int chapterIndex) {
        List<ContinuityIssue> issues = new ArrayList<>();
        if (text == null || text.contains("未发现")) return issues;

        Pattern p = Pattern.compile("【([^|]+)\\|([^】]+)】(.+)");
        Matcher m = p.matcher(text);
        while (m.find()) {
            ContinuityIssue.Type type = parseType(m.group(1).trim());
            ContinuityIssue.Severity severity = parseSeverity(m.group(2).trim());
            String desc = m.group(3).trim();
            issues.add(new ContinuityIssue(projectId, chapterIndex, type, severity, desc));
        }
        return issues;
    }

    private ContinuityIssue.Type parseType(String s) {
        return switch (s) {
            case "人物" -> ContinuityIssue.Type.CHARACTER;
            case "世界观" -> ContinuityIssue.Type.WORLDBUILDING;
            case "时间线" -> ContinuityIssue.Type.TIMELINE;
            case "伏笔" -> ContinuityIssue.Type.FORESHADOWING;
            default -> ContinuityIssue.Type.WORLDBUILDING;
        };
    }

    private ContinuityIssue.Severity parseSeverity(String s) {
        return switch (s) {
            case "高" -> ContinuityIssue.Severity.HIGH;
            case "中" -> ContinuityIssue.Severity.MEDIUM;
            default -> ContinuityIssue.Severity.LOW;
        };
    }
}
```

- [ ] **Step 3: StyleGuardian 测试**

`src/test/java/com/writing/novel/agent/StyleGuardianAgentTest.java`:
```java
package com.writing.novel.agent;

import com.writing.novel.domain.StyleProfile;
import com.writing.novel.llm.LlmGateway;
import com.writing.novel.llm.LlmResponse;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StyleGuardianAgentTest {

    @Test
    void 从样章生成风格基线() {
        LlmGateway mockGateway = req -> new LlmResponse("""
            【基调】热血、紧凑、节奏明快
            【叙事】第三人称，多动作描写，少心理独白
            【注意事项】多用短句推进节奏；避免大段说明性文字；战斗场景要有画面感
            """, 50, 100);

        StyleGuardianAgent agent = new StyleGuardianAgent(mockGateway, null);
        StyleProfile profile = agent.establishProfile("proj-1", "样章正文……");

        assertNotNull(profile);
        assertEquals("热血、紧凑、节奏明快", profile.getTone());
        assertTrue(profile.getDosAndDonts().contains("短句"));
    }

    @Test
    void 检测风格一致性() {
        LlmGateway mockGateway = req -> new LlmResponse("风格一致性评分：85/100。整体符合热血风格，但第3段节奏偏慢。", 10, 30);
        StyleGuardianAgent agent = new StyleGuardianAgent(mockGateway, null);
        String report = agent.checkConsistency("proj-1", "新章节正文", "热血风格");
        assertTrue(report.contains("85"));
    }
}
```

- [ ] **Step 4: 实现 StyleGuardianAgent**

`src/main/java/com/writing/novel/agent/StyleGuardianAgent.java`:
```java
package com.writing.novel.agent;

import com.writing.novel.domain.StyleProfile;
import com.writing.novel.llm.LlmGateway;
import com.writing.novel.llm.LlmRequest;
import com.writing.novel.llm.LlmTaskType;
import com.writing.novel.spi.NovelRepository;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 风格守护 Agent。建立风格基线 + 检测风格漂移。
 */
public class StyleGuardianAgent {

    private final LlmGateway gateway;
    private final NovelRepository repository;

    public StyleGuardianAgent(LlmGateway gateway, NovelRepository repository) {
        this.gateway = gateway;
        this.repository = repository;
    }

    /** 从样章分析并建立风格基线 */
    public StyleProfile establishProfile(String projectId, String sampleText) {
        String systemPrompt = """
                你是文学风格分析专家。分析给定的文本样章，总结其写作风格。
                严格按以下格式输出：
                【基调】...
                【叙事】...
                【注意事项】...
                """;

        var req = LlmRequest.builder()
                .taskType(LlmTaskType.STYLE_GUARDIAN)
                .systemPrompt(systemPrompt)
                .userMessage("请分析以下样章的风格：\n" + sampleText)
                .temperature(0.5)
                .maxTokens(800)
                .build();

        var resp = gateway.complete(req);
        StyleProfile profile = new StyleProfile(projectId);
        profile.setTone(extractField(resp.getContent(), "基调"));
        profile.setVoice(extractField(resp.getContent(), "叙事"));
        profile.setDosAndDonts(extractField(resp.getContent(), "注意事项"));

        if (repository != null) repository.saveStyleProfile(profile);
        return profile;
    }

    /** 检查新章节的风格一致性，返回评估报告 */
    public String checkConsistency(String projectId, String chapterText, String styleBaseline) {
        String systemPrompt = """
                你是风格一致性审查员。评估新章节是否与全书风格基线一致。
                输出格式：风格一致性评分：XX/100。简短说明。
                """;

        var req = LlmRequest.builder()
                .taskType(LlmTaskType.STYLE_GUARDIAN)
                .systemPrompt(systemPrompt)
                .userMessage("风格基线：" + styleBaseline
                        + "\n\n新章节：\n" + chapterText
                        + "\n\n请评估风格一致性。")
                .temperature(0.3)
                .maxTokens(500)
                .build();

        return gateway.complete(req).getContent();
    }

    private String extractField(String text, String field) {
        Pattern p = Pattern.compile("【" + Pattern.quote(field) + "】(.+?)(?=【|$)", Pattern.DOTALL);
        Matcher m = p.matcher(text);
        return m.find() ? m.group(1).trim() : "";
    }
}
```

- [ ] **Step 5: 运行测试**

- [ ] **Step 6: 提交**

```bash
git add src/main/java/com/writing/novel/agent/ContinuityCheckerAgent.java \
        src/main/java/com/writing/novel/agent/StyleGuardianAgent.java \
        src/test/java/com/writing/novel/agent/ContinuityCheckerAgentTest.java \
        src/test/java/com/writing/novel/agent/StyleGuardianAgentTest.java
git commit -m "feat(agent): ContinuityCheckerAgent + StyleGuardianAgent"
```

---

## Task 6: MemoryManagerAgent

**Files:**
- Create: `src/main/java/com/writing/novel/agent/MemoryManagerAgent.java`
- Test: `src/test/java/com/writing/novel/agent/MemoryManagerAgentTest.java`

- [ ] **Step 1: 写失败测试**

`src/test/java/com/writing/novel/agent/MemoryManagerAgentTest.java`:
```java
package com.writing.novel.agent;

import com.writing.novel.domain.Summary;
import com.writing.novel.llm.LlmGateway;
import com.writing.novel.llm.LvmResponse;
import com.writing.novel.memory.SummaryService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MemoryManagerAgentTest {

    @Test
    void 压缩章节为摘要() {
        LlmGateway mockGateway = req -> new LlmResponse(
                "第1-3章：张三拜入青云宗，觉醒剑骨，初露锋芒，与师兄结怨。", 200, 100);

        SummaryService mockSummarySvc = new SummaryService(null);
        boolean[] saved = {false};
        SummaryService summarySvc = new SummaryService(null) {
            @Override public void save(Summary s) { saved[0] = true; }
        };

        MemoryManagerAgent agent = new MemoryManagerAgent(mockGateway, summarySvc);
        Summary summary = agent.summarize("proj-1", 1, 3, "第1章正文……\n第2章正文……\n第3章正文……");

        assertNotNull(summary);
        assertEquals(1, summary.getFromChapter());
        assertEquals(3, summary.getToChapter());
        assertTrue(summary.getContent().contains("张三"));
        assertTrue(saved[0]);
    }
}
```

- [ ] **Step 2-5: 实现 MemoryManagerAgent**

`src/main/java/com/writing/novel/agent/MemoryManagerAgent.java`:
```java
package com.writing.novel.agent;

import com.writing.novel.domain.Summary;
import com.writing.novel.llm.LlmGateway;
import com.writing.novel.llm.LlmRequest;
import com.writing.novel.llm.LlmTaskType;
import com.writing.novel.memory.SummaryService;

/**
 * 记忆管理 Agent。把章节正文压缩为滚动摘要，控制上下文 token 用量。
 */
public class MemoryManagerAgent {

    private final LlmGateway gateway;
    private final SummaryService summaryService;

    public MemoryManagerAgent(LlmGateway gateway, SummaryService summaryService) {
        this.gateway = gateway;
        this.summaryService = summaryService;
    }

    /** 把 fromChapter 到 toChapter 的正文压缩为一条摘要 */
    public Summary summarize(String projectId, int fromChapter, int toChapter, String chaptersText) {
        String systemPrompt = """
                你是小说摘要专家。把给定的章节正文压缩为简洁的情节摘要。
                要求：保留关键事件、人物状态变化、伏笔进展；省略细节描写。
                输出 2-4 句话的摘要，不要加标题或格式标记。
                """;

        var req = LlmRequest.builder()
                .taskType(LlmTaskType.MEMORY_SUMMARY)
                .systemPrompt(systemPrompt)
                .userMessage("请压缩第" + fromChapter + "-" + toChapter + "章：\n" + chaptersText)
                .temperature(0.3)
                .maxTokens(500)
                .build();

        var resp = gateway.complete(req);
        Summary summary = new Summary(projectId, fromChapter, toChapter, resp.getContent().trim());
        summaryService.save(summary);
        return summary;
    }
}
```

- [ ] **Step 6: 提交**

```bash
git add src/main/java/com/writing/novel/agent/MemoryManagerAgent.java \
        src/test/java/com/writing/novel/agent/MemoryManagerAgentTest.java
git commit -m "feat(agent): MemoryManagerAgent 摘要生成"
```

---

## Task 7: AgentConfig 装配 + 集成测试

**Files:**
- Create: `src/main/java/com/writing/novel/agent/AgentConfig.java`
- Test: `src/test/java/com/writing/novel/agent/AgentIntegrationTest.java`

- [ ] **Step 1: 写失败测试**

`src/test/java/com/writing/novel/agent/AgentIntegrationTest.java`:
```java
package com.writing.novel.agent;

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
        "persistence.sqlite.db-path=./data/test-agent.db",
        "persistence.vector-store.index-path=./data/test-agent-vecs.idx"
})
class AgentIntegrationTest {

    @Autowired ApplicationContext context;

    @org.junit.jupiter.api.BeforeAll
    static void createDataDir() { new File("./data").mkdirs(); }

    @Test
    void 七个agent均被装配() {
        assertNotNull(context.getBean(WorldBuilderAgent.class));
        assertNotNull(context.getBean(CharacterDesignerAgent.class));
        assertNotNull(context.getBean(PlotterAgent.class));
        assertNotNull(context.getBean(ChapterWriterAgent.class));
        assertNotNull(context.getBean(ContinuityCheckerAgent.class));
        assertNotNull(context.getBean(StyleGuardianAgent.class));
        assertNotNull(context.getBean(MemoryManagerAgent.class));
    }
}
```

- [ ] **Step 2: 创建 AgentConfig**

`src/main/java/com/writing/novel/agent/AgentConfig.java`:
```java
package com.writing.novel.agent;

import com.writing.novel.llm.LlmGateway;
import com.writing.novel.memory.ContextAssembler;
import com.writing.novel.memory.KnowledgeBaseService;
import com.writing.novel.memory.SummaryService;
import com.writing.novel.memory.VectorRetrievalService;
import com.writing.novel.spi.NovelRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AgentConfig {

    @Bean
    public WorldBuilderAgent worldBuilderAgent(LlmGateway gateway, KnowledgeBaseService kb) {
        return new WorldBuilderAgent(gateway, kb);
    }

    @Bean
    public CharacterDesignerAgent characterDesignerAgent(LlmGateway gateway, KnowledgeBaseService kb) {
        return new CharacterDesignerAgent(gateway, kb);
    }

    @Bean
    public PlotterAgent plotterAgent(LlmGateway gateway, NovelRepository repo) {
        return new PlotterAgent(gateway, repo);
    }

    @Bean
    public ChapterWriterAgent chapterWriterAgent(LlmGateway gateway, ContextAssembler assembler,
                                                   NovelRepository repo, VectorRetrievalService vector) {
        return new ChapterWriterAgent(gateway, assembler, repo, vector);
    }

    @Bean
    public ContinuityCheckerAgent continuityCheckerAgent(LlmGateway gateway, NovelRepository repo) {
        return new ContinuityCheckerAgent(gateway, repo);
    }

    @Bean
    public StyleGuardianAgent styleGuardianAgent(LlmGateway gateway, NovelRepository repo) {
        return new StyleGuardianAgent(gateway, repo);
    }

    @Bean
    public MemoryManagerAgent memoryManagerAgent(LlmGateway gateway, SummaryService summaryService) {
        return new MemoryManagerAgent(gateway, summaryService);
    }
}
```

- [ ] **Step 3-6: 运行 + 提交**

```bash
mvn test
mvn verify
git add src/main/java/com/writing/novel/agent/AgentConfig.java \
        src/test/java/com/writing/novel/agent/AgentIntegrationTest.java
git commit -m "feat(agent): AgentConfig 装配 + 七 Agent 集成测试"
```

---

## 自审记录

- ✅ 7 个 Agent 全覆盖 — Task 1-6
- ✅ 每个 Agent 无状态、吃 LlmGateway + Memory 服务
- ✅ ChapterWriterAgent 使用 ContextAssembler（Plan 3 核心依赖）
- ✅ ResponseParser 提取结构化数据
- ✅ AgentConfig 装配 + 集成测试

注意修正：Task 6 测试中 `LvmResponse` 是笔误，应为 `LlmResponse`。
