# Spec: 百万字人机真典脊柱（Human Canon + Long-Range Continuity）

> 文件名约定：`docs/specs/YYYY-MM-DD-<slug>.md`。新需求必须先落 spec，再拆 feature。
> 本 spec 为决策完备（decision-complete）文档：评审通过后可直接进入 `feature_list.json` 登记与逐 feature 实现。
> 上游 review：2026-08-16 对架构/实现的代码级审查（`src/main/java` + 前端 + Phase 10/11 spec 对照）。
> 上游 spec：`docs/specs/2026-08-13-million-word-architecture.md`（Phase 10）、`docs/specs/2026-08-14-quality-gate-architecture.md`（Phase 11）。

## 背景与动机

WritingAgent 已能自动写 3–60 章、单章质量 7–9 分，并具备真典表、门禁、质检委员会的**骨架**。但一次面向「持续高质量百万字 + 中途改世界观/人物/正文再接着写」的代码级复查发现：系统缺的不是再堆 Agent，而是一层**人可写、后续必遵守的真相与规划脊柱**。

验证过的上限约 63 章，不是 400 章。默认 `targetChapters` 已被 p12-05 从 400 改成 24——产品路径已缩成短篇。

### 证据 A：中途交互是空壳（对照 2026-07-10 原设计）

原设计承诺 Solo/交互、4 个 checkpoint、世界观/人物/大纲/章节 PUT、SSE、暂停续写。实现：

| 承诺 | 代码事实 |
|---|---|
| PUT 世界观/人物/大纲 | `ContentController` 只有 GET（`:23-46`） |
| 前端改设定 | `WorldbuildingView` / `OutlineView` 只读 |
| 每卷大纲后可改梗概 | `AFTER_VOLUME_OUTLINE` 仅枚举，无创建点 |
| 矛盾暂停等人确认 | `ON_CONTINUITY_FIX` 几乎不用；`NEEDS_MANUAL_REVIEW` 无处理 UI |
| 暂停 / SSE | 无 `POST /pause`，无 `/stream` |
| 真典给人改 | 无 story_facts API |
| 连续写作中插入修改 | `runWritingLoop`（`NovelOrchestrator.java:1488-1596`）不看 `INTERACTIVE`/`PAUSED`，`write-all` 会冲过暂停 |

Checkpoint 拒绝/修改只处理 `AFTER_CHAPTER`（`CheckpointController.java:56-61`）。构思结束的 `AFTER_INITIAL_SETUP` 暂停了，人却改不了刚生成的世界观/人物/大纲。

### 证据 B：人改的正文不进入记忆层

`editPassage`（`:2268-2329`）只改字符串：不 bump `chapter.version`、不脏摘要、不清旧向量、不收割真典、不 bump `MemoryRevision`。后续章节继续引用旧摘要/旧 chunk。

`rewriteChapter`（`:2171`）会脏摘要，仍不收割真典、不清向量前缀。

### 证据 C：真典没有成为长程记忆

`ContextAssembler.buildBibleSection`（`:197-208`）硬编码 **cap=25**，按表顺序取，不按本章相关性。Phase 11 承诺「动态 cap + 按章召回」未做。

`RelevanceGateway`（`:36-89`）先 `findAll*` 再内存过滤——LLM 输入被 cap，JVM/SQLite 仍全表扫。400 章时 checker 会又慢又瞎。

`applyTokenBudget`（`:994-1028`）裁剪顺序是设定→世界观→大纲→上章原文→摘要 4000→伏笔 500。不保护真典段的「本章必入」；也不先裁 `characterVoice`/`narrativeThreads`/`feedback`。

### 证据 D：规划不演进、质检委员会未接入主循环

`replanRemainingOutline`（`:1823-1878`）不调 Plotter 重画未写卷，只往 `outline.premise` 追加摘要，指望延迟卷生成自己适配。百万字中段偏航无法回收。

`QualityCouncil`（54 行拼接）+ `Arbiter`（38 行 if/else）存在，但 **`NovelOrchestrator` 从不调用 `Arbiter`**。写作环仍是串行检测 + `rewritten` 互斥 + 单章最多重写 1 次。`:1141` 仍有 `catch (Exception ignored)`。

63 章项目出现 **253 条悬置伏笔告警**。完结前对 mustResolve 的处理是 `setMustResolve(false)` 以免卡住——为完结放弃承诺。

### 核心洞察

信息有三条轨，互不同步：

1. **人改的卡面**（Character / KnowledgeEntry / Outline）——几乎没有写 API
2. **真典**（`story_facts` ACTIVE）——写作侧部分消费，检测侧收窄是假的，人不可写
3. **有损摘要 + 向量 chunk**——人改正文后不失效

百万字高质量要求：第 400 章仍能召回第 1 卷铁律；用户在第 80 章改了人设/世界观后，第 81 章必须遵守。共同杠杆是 **Human Canon Patch**：一切变更（人的或系统的）都写成可检索的 ACTIVE 事实，并走同一条记忆失效链。

## 目标

1. **人可在中途改世界观 / 人物 / 大纲 / 真典 / 正文**，暂停作业后写入，后续未写章节必须读到补丁（改卡面 = 写真典 + 失效旧记忆）。
2. **第 N 章的写作上下文按相关性召回真典**，不再硬截 25 条；故事原旨与本章相关铁律永不被预算裁掉。
3. **中段可真重规划**：每 N 卷（或用户改大纲后）Plotter 重画**未写卷**结构，已写章节梗概不动。
4. **质检委员会真正调度写作环**：Arbiter 统一分配重写预算；重写后全量复检；伏笔进 prompt 只走活跃集。
5. **连续写作可暂停、可续写、尊重 INTERACTIVE checkpoint**；`write-all` 不再冲过暂停。

## 非目标

- **不改正文回溯重写**：已发布章节不因设定变更自动连锁重写（延续 Phase 10）。早期矛盾标 `NEEDS_MANUAL_REVIEW` + 纠正真典，由人决定是否手动 `rewriteChapter`。
- **不引入真实 tokenizer / ANN / 分布式**：延续 Phase 10。
- **不改 Agent 间不直接调用**：CanonPatch / Replanner / 新 API 仍经 Orchestrator 或独立 Service，Agent 互不调用。
- **不改既有 `.st` 变量名**：真典召回与补丁在组装层完成。
- **不做硬门禁**：软门禁 + 人机协同延续 Phase 11。
- **不把默认 `targetChapters` 改回 400**：短篇默认 24 保留；长篇由创建时显式选择 100/200/400。系统必须**能**跑 400，不强迫每个项目都跑 400。
- **不做完整 SSE 流式正文**：本 phase 用状态轮询 + pause/resume；SSE 另立 spec。
- **不重写 NovelOrchestrator 为框架**：允许抽出 `CanonPatchService` / `OutlineReplanner`，禁止借机大重构无关模块。
- **不加新检测器**：先把已有 Arbiter/Council/真典接到主路径。禁止再堆 Agent。

## 设计哲学

| 维度 | 现状 | 目标 |
|---|---|---|
| 真相层 | 卡面 / 真典 / 摘要三条轨不同步 | **真典是唯一可变真相**；卡面只留稳定身份；人改 = 写真典 |
| 人机 | 只能读设定、改已写正文 | **暂停 → 补丁 → 失效 → 续写** |
| 长程记忆 | cap=25 顺序截断 | **按章相关性召回**；铁律与 HUMAN_PATCH 必入 |
| 规划 | premise 追加一段话 | **Plotter 重画未写卷** |
| 质检 | 类在、环不调 | **Arbiter 进主循环** |
| 作业 | write-all 无视暂停 | **PAUSED 是硬停点** |

## 目标架构（五大支柱）

### 支柱 ① Human Canon Patch（人机真典补丁）★ 对应目标 1、5

新增 `CanonPatchService`（不要把逻辑继续堆进 2400+ 行的 `NovelOrchestrator`）。

**统一变更入口** `apply(projectId, Patch)`：

```
Patch {
  source: HUMAN | SYSTEM
  kind: CHARACTER | WORLD | OUTLINE | FACT | CHAPTER
  targetId
  fields / content
}
```

处理顺序（所有写路径必须走这里，禁止 Controller 直接 `repository.save*` 后了事）：

1. **持久化卡面**（Character 稳定身份 / KnowledgeEntry / Outline / Chapter.content）
2. **写真典**：可变状态写成新 ACTIVE 事实，旧事实 `SUPERSEDED`（审计链）。`source` 记在 assertion 前缀 `【HUMAN_PATCH】` 或 Fact 的 importance=5，便于召回加权。
3. **失效记忆**：`MemoryRevision.bump(projectId)`；若改章：`chapter.version++`、`invalidateSummariesForChapter`、`vectorStore.deleteByPrefix(chapterId + "#")`；若改章则 `FactHarvester.harvest` 重跑该章。
4. **返回** `{entity, factsWritten[], invalidated: true}`

映射规则（锁定，禁止再双轨乱写）：

| 人改的东西 | 卡面写入 | 真典写入 |
|---|---|---|
| Character.name/appearance/personality/role | Character 稳定身份 | 无（或 NAME_ANCHOR 一条 WORLD_RULE） |
| Character.ability/motivation/growthArc/relationships | **不覆盖**能力/弧（单轨：真典优先）；卡面仅作展示兜底 | CHARACTER_STATE / RELATIONSHIP_STATE |
| KnowledgeEntry.content | KnowledgeEntry | WORLD_RULE（subject=条目名） |
| Outline.premise / 未写卷梗概 | Outline | PLOT_COMMITMENT |
| StoryFact 直接改 | 无 | supercede 旧 + 新 ACTIVE |
| Chapter 段落/整章 | Chapter.content + version++ | FactHarvester 重收割 |

**REST（补齐 2026-07-10 设计，本 phase 落地）**：

```
PUT  /api/projects/{id}/worldbuilding/{entryId}
PUT  /api/projects/{id}/characters/{charId}
PUT  /api/projects/{id}/outline
PUT  /api/projects/{id}/outline/volumes/{volumeIndex}
PUT  /api/projects/{id}/facts/{factId}          # 仅 SUPERSEDE：body={assertion,importance}
POST /api/projects/{id}/facts                   # 人手补一条
GET  /api/projects/{id}/facts?status=ACTIVE

POST /api/projects/{id}/pause                   # 置 PAUSED_AT_CHECKPOINT + 取消 write-all
POST /api/projects/{id}/resume                  # 等同 step()，但从 PAUSED 恢复
```

已有 `rewrite-chapter` / `passage` 改为先走 `CanonPatchService` 的 CHAPTER 分支。

**作业硬停点**：

- `runWritingLoop` 每章**开始前**检查：`cancelChecker` **或** `project.state == PAUSED_AT_CHECKPOINT` → break，已写章节保留。
- INTERACTIVE 模式：`runWritingOneChapter` 末尾置 PAUSED 后，若调用方是 `runWritingLoop`，**本轮即停**（不再写下一章）。
- `POST /pause`：设状态 + `writingTaskExecutor.cancel(activeTask)`。
- `AFTER_INITIAL_SETUP` / `AFTER_VOLUME_OUTLINE` 暂停时，前端必须能改对应产物再 approve。

**前端（最小可用，不重做视觉）**：

- `WorldbuildingView` / 人物卡：字段可编辑 + 保存
- `OutlineView`：premise / 未写章梗概可编辑 + 保存
- 工作区：暂停 / 续写按钮；`write-all` 进行中显示「暂停」
- 不在本 phase 做真典可视化大表（H5 波再做列表）

### 支柱 ② 按章召回的真典 + 预算保护 ★ 对应目标 2

`RelevanceGateway.relevantFacts(projectId, cast, chapterIndex, cap)` 改为**分层必入 + 填充**，禁止「findAll 后 subList(0, 25)」：

```
必入（不受 cap 挤掉，只受 token 硬顶）:
  1. assertion 含 【HUMAN_PATCH】 的 ACTIVE 事实
  2. WORLD_RULE 且 importance >= 4（时代/力量体系铁律）
  3. 本章 cast 的 CHARACTER_STATE / RELATIONSHIP_STATE
  4. PLOT_COMMITMENT 且 lastValidated 距本章 <= 30 或 importance >= 4

填充（剩余额度）:
  importance * 2 + recency(lastValidated) 排序，直到 cap

cap 默认 40；由 TokenBudget 剩余额度再压到 [15, 40]
```

`buildBibleSection` 删除硬编码 25，改为 `relevantFacts(...)`。`applyTokenBudget` 调整：

1. 永不裁 `premiseSection`
2. `bibleSection` 只允许裁「填充」部分，必入块不可裁（实现：组装时把必入与填充分成两段，或在 bible 段内用标记 `<!--optional-->`；更简单：bible 整段不裁，先裁 voice/threads/feedback/constraints/quotes）
3. 裁剪顺序改为：knowledgeQuote → qualityFeedback → characterVoice → narrativeThreads → recentText → volumeOutline → recentSummary(4000) → foreshadow(500)
4. worldbuilding 截断到 2000，但 POWER_SYSTEM/RULE 两条优先保留（已有按类型取样）

`RelevanceGateway` 本 phase **允许仍 findAll 再过滤**（SQLite 单项目规模可接受），但必须：

- 事实查询优先走已有 `findActiveBySubject` / `findActiveByType`，避免每次把全部事实拉进 checker
- 禁止检查器自己 `findCharactersByProject` 全量进 LLM；继续经 Gateway，cap 角色 ≤ 8

### 支柱 ③ 真重规划未写卷 ★ 对应目标 3

抽出 `OutlineReplanner`（或扩 `PlotterAgent`，不把 80 行字符串拼接留在 Orchestrator）：

`replanRemaining(projectId, currentChapter)`：

1. 收集：已写卷摘要（最近 3 卷全文，更早卷各 200 字）+ 真典必入事实 + 未回收 mustResolve 伏笔
2. 调 Plotter **只生成未写卷**的卷标题/摘要/章节梗概（含 cast/beats/climax/tension）
3. **替换** outline 里 `chapterIndex > currentChapter` 的 synopses；已写章梗概只读
4. `MemoryRevision.bump` + 保存 outline
5. INTERACTIVE：创建 `AFTER_VOLUME_OUTLINE` checkpoint 并 PAUSED

触发点（锁定）：

- 每完成 `replanEveryNVolumes` 卷（默认 5，读 WritingPreferences，缺省 5）
- 用户 PUT 未写卷/总纲后显式 `?replan=true`（默认 true）
- 不在每章触发

禁止再「往 premise 追加一段【剧情偏离与重规划】当重规划」。旧追加逻辑删除。

### 支柱 ④ Arbiter 进主循环 + 伏笔活跃集 ★ 对应目标 4

重构 `checkContinuityAfterWriting` 为：

```
1. 跑 ContinuityChecker / ConsistencyGuard / ReaderReview（已有）→ 收集报告
2. QualityCouncil.collect(...)
3. Arbiter.arbitrate(report, rewriteBudget, alreadyRewritten)
4. REWRITE → rewriteWithCorrection(combinedBrief) → 全量复检三器
5. BLOCK 或复检仍 HIGH → RepairArbiter 三分类（REWRITE 用尽 / SUPERSEDE / NEEDS_MANUAL_REVIEW）
```

删除 `rewritten` 互斥提前 return。预算仍默认 1，由 Arbiter 统一花，不由「谁先命中」花。

`catch (Exception ignored)` 在重写复检路径改为 `log.warn` + `EventLog.emit(CONSISTENCY_ISSUE, DETECT_FAILED)`（补齐 p11-q3-03 未清干净的点）。

**伏笔进写作上下文**：`buildForeshadowings` 改为活跃集，最多 12 条：

1. mustResolve 未回收（全入，最多 8）
2. 本章 synopsis 点名 / 近 15 章埋设
3. 其余按 importance，排除已 RETIRED / 已回收
4. 禁止把 253 条悬置告警整表塞进 ContinuityIssue 再灌回下一章 prompt

悬置检测改为：只对 mustResolve 或 importance≥4 且悬置>阈值的发 MEDIUM issue，不再对每条 LOW 伏笔发 issue。

完结前 mustResolve 未回收：保留追加回收章；**禁止** `setMustResolve(false)` 静默降级。改为：追加一章后若仍未回收 → FINAL 门禁 FAIL → `NEEDS_REVISION`（已有门禁语义），由人决定改大纲或手动重写。

### 支柱 ⑤ 人审入口与长篇开关 ★ 对应目标 1 的可发现性

- `GET /chapters/{i}/quality` 已有；补：列表里标 `quality_risk` / `NEEDS_MANUAL_REVIEW`
- 工作区章节列表：NEEDS_MANUAL_REVIEW 橙色标记 + 一键「按意见重写」
- 创建项目：篇幅选择 24 / 100 / 200 / 400（写到 `WritingPreferences.targetChapters`），UI 已有预设，补篇幅
- `quality_risk` 本 phase 继续用 ContinuityIssue 前缀（兼容已有 p11-q1-03）；不强制加列。列表 API 过滤 `[quality_risk]` 即可

## 状态语义（增量）

沿用 `NovelState` / `Chapter.Status` / `Checkpoint.Type`。本 spec **激活**已有但未用的：

| 符号 | 本 phase 行为 |
|---|---|
| `PAUSED_AT_CHECKPOINT` | write-all / loop 的硬停点；pause API 进入；INTERACTIVE 每章/每卷进入 |
| `AFTER_VOLUME_OUTLINE` | 延迟卷生成后、用户改大纲重规划后创建 |
| `AFTER_INITIAL_SETUP` | 已有；批准前必须能改世界/人物/大纲 |
| `NEEDS_MANUAL_REVIEW` | 已有赋值；UI 可见 + 可 rewriteChapter |
| `WritingTask.PAUSED` | pause / 成本熔断进入；resume 从当前章续 |

不新增 NovelState。不改 checkpoint 表结构。

StoryFact 不新增列。人补丁用 assertion 前缀 `【HUMAN_PATCH】` + importance=5 识别（避免迁移）。若后续要一等 `source` 列，另立 spec。

## 验收标准（分波，机器可验证）

### 第一波 · H1 人机脊柱（可中途改、暂停、传播）

- [ ] `mvn test -Dtest=CanonPatchServiceTest`：改人物能力 → 旧 CHARACTER_STATE SUPERSEDED、新 ACTIVE 含 `【HUMAN_PATCH】`、MemoryRevision 增加；改世界观同理。
- [ ] `mvn test -Dtest=CanonWriteApiTest`：PUT character/world/outline/facts 走 CanonPatch，禁止只 save 卡面；GET facts 返回 ACTIVE。
- [ ] `mvn test -Dtest=WritingLoopPauseTest`：INTERACTIVE 下 `runWritingLoop` 写 1 章即停在 PAUSED；`pause()` 取消 write-all；resume 后续写读到补丁事实。
- [ ] `mvn test -Dtest=ChapterEditInvalidationTest`：`editPassage` / `rewriteChapter` 后 version+1、覆盖该章的摘要 dirty、向量前缀被删、FactHarvester 被调用（可用 spy/fake）。
- [ ] 前端：世界观/人物/大纲可保存；工作区有暂停（`cd frontend && npm run build`）。

### 第二波 · H2 真典按章召回

- [ ] `mvn test -Dtest=RelevantFactsRecallTest`：80 条 ACTIVE 事实时，本章 cast + HUMAN_PATCH + 高 importance WORLD_RULE 必入；第 1 卷铁律在第 80 章仍被召回；不再出现「前 25 条顺序截断丢掉铁律」。
- [ ] `mvn test -Dtest=BibleBudgetProtectionTest`：超预算时 premise 与 bible 必入块仍在 `toSystemPrompt()`；先裁 quote/voice/feedback。

### 第三波 · H3 真重规划

- [ ] `mvn test -Dtest=OutlineReplannerTest`：`replanRemaining` 替换未写卷 synopses，已写章梗概不变；不再把【剧情偏离与重规划】追加进 premise。
- [ ] `mvn test -Dtest=VolumeOutlineCheckpointTest`：INTERACTIVE 下延迟卷生成后出现 `AFTER_VOLUME_OUTLINE` PENDING checkpoint 且状态 PAUSED。

### 第四波 · H4 质检主循环 + 伏笔活跃集

- [ ] `mvn test -Dtest=ArbiterWiredTest`：`checkContinuityAfterWriting` 调用 `Arbiter.arbitrate`；HIGH+读者<7 一次重写喂合并 brief；重写后三器复检。
- [ ] `mvn test -Dtest=FailLoudTest`：复检路径无 `catch (Exception ignored)`。
- [ ] `mvn test -Dtest=ForeshadowActiveSetTest`：200 条未回收伏笔时写作上下文最多 12 条，mustResolve 优先；LOW 悬置不刷 ContinuityIssue；完结不得 `setMustResolve(false)`。

### 第五波 · H5 可发现性

- [ ] `mvn test -Dtest=ManualReviewSurfaceTest`：NEEDS_MANUAL_REVIEW / quality_risk 出现在章节质量/列表 DTO。
- [ ] 创建项目可选 24/100/200/400；`WritingPreferences.targetChapters` 被写入（前端 build + `ProjectServiceTest`）。

### 波次完成后的质量门

- [ ] `mvn test` 全绿。
- [ ] H1–H4 完成后 `./scripts/e2e.sh` + `compare-baseline.py` PASS（prompt/上下文/编排有变）。
- [ ] 不要求本 phase 真跑 400 章 record（成本）；长程用单测模拟 80–200 条事实 / 200 条伏笔断言召回与活跃集。

## 影响面

- **涉及模块**：`service/CanonPatchService`（新）、`service/OutlineReplanner`（新）、`web/ContentController`、`web/OrchestratorController`、`orchestrator/NovelOrchestrator`、`service/WritingTaskExecutor`、`memory/ContextAssembler`、`memory/RelevanceGateway`、`service/Arbiter`、`service/QualityCouncil`、`agent/PlotterAgent`、`agent/FactHarvesterAgent`、`frontend/src/{api,components,pages}`
- **涉及 prompt 模板**：不改既有变量名。重规划复用 `volume-outline.st` / `plotting.st`；若必须加「只规划未写卷」说明，只加文案不加新变量。
- **数据库 schema 变更**：无强制新列。StoryFact 用 `【HUMAN_PATCH】` 前缀。若 PUT outline 需要按 volume 局部更新，用现有 `saveOutline`。
- **质量基线影响**：H2/H4 改组装与写作环，需 e2e + compare；若分数波动在阈值内不 bless；若 prompt 指纹变导致 replay miss，record 3 章更新 fixture。
- **配置**：`WritingPreferences.replanEveryNVolumes` 默认 5（缺省兼容）。`targetChapters` 已存在。

## Feature 拆分

> 拆分规则见 AGENTS.md。每个 feature 1-3 个文件、可独立验证。Phase 13。五波：H1 脊柱先行（无跨波依赖可先做）；H2 召回（独立）；H3 重规划（依赖 H1 失效）；H4 质检主循环（独立，建议 H1 后）；H5 可发现性（依赖 H1 API）。

### 第一波 · H1 人机脊柱

| id | name | 依赖 | acceptance | verify_cmd | 文件 |
|----|------|------|------------|------------|------|
| p13-h1-01 | CanonPatchService 统一补丁 | [] | 改人物能力/世界观 → SUPERSEDED+新 ACTIVE（含【HUMAN_PATCH】）+ MemoryRevision.bump；单测覆盖两条路径 | `mvn test -Dtest=CanonPatchServiceTest` | `service/CanonPatchService.java` |
| p13-h1-02 | 设定/真典写 API | [p13-h1-01] | PUT character/world/outline + GET/POST/PUT facts 均走 CanonPatch；无补丁则 404 | `mvn test -Dtest=CanonWriteApiTest` | `web/ContentController.java` |
| p13-h1-03 | write-all / loop 尊重暂停 | [] | INTERACTIVE 下 loop 一章即 PAUSED；POST /pause 取消任务；resume=step | `mvn test -Dtest=WritingLoopPauseTest` | `orchestrator/NovelOrchestrator.java`、`web/OrchestratorController.java` |
| p13-h1-04 | 章节编辑走失效链 | [p13-h1-01] | editPassage/rewriteChapter：version+1、摘要 dirty、向量前缀删除、harvest | `mvn test -Dtest=ChapterEditInvalidationTest` | `orchestrator/NovelOrchestrator.java` |
| p13-h1-05 | 前端可编辑设定 + 暂停 | [p13-h1-02,p13-h1-03] | 世界观/人物/大纲可保存；工作区暂停/续写；build 通过 | `cd frontend && npm run build` | `frontend/src/components/WorldbuildingView.tsx`、`frontend/src/components/OutlineView.tsx`、`frontend/src/api/client.ts` |

### 第二波 · H2 真典按章召回

| id | name | 依赖 | acceptance | verify_cmd | 文件 |
|----|------|------|------------|------------|------|
| p13-h2-01 | relevantFacts 分层必入 | [] | 80 条事实时 HUMAN_PATCH + 铁律 + 本章 cast 必入；禁止 subList(0,25) | `mvn test -Dtest=RelevantFactsRecallTest` | `memory/RelevanceGateway.java` |
| p13-h2-02 | bibleSection 去掉 cap=25 | [p13-h2-01] | buildBibleSection 调 relevantFacts；硬编码 25 删除 | `mvn test -Dtest=RelevantFactsRecallTest` | `memory/ContextAssembler.java` |
| p13-h2-03 | token 预算保护 premise/bible | [p13-h2-02] | 超预算时 premise+bible 必入仍在；先裁 quote/voice/feedback | `mvn test -Dtest=BibleBudgetProtectionTest` | `memory/ContextAssembler.java` |

### 第三波 · H3 真重规划

| id | name | 依赖 | acceptance | verify_cmd | 文件 |
|----|------|------|------------|------------|------|
| p13-h3-01 | OutlineReplanner 替换未写卷 | [] | 已写梗概不变；未写卷被替换；premise 不再追加偏离段 | `mvn test -Dtest=OutlineReplannerTest` | `service/OutlineReplanner.java`、`orchestrator/NovelOrchestrator.java` |
| p13-h3-02 | 卷大纲 checkpoint | [p13-h3-01] | INTERACTIVE 延迟卷/重规划后 AFTER_VOLUME_OUTLINE + PAUSED | `mvn test -Dtest=VolumeOutlineCheckpointTest` | `orchestrator/NovelOrchestrator.java` |

### 第四波 · H4 质检主循环 + 伏笔活跃集

| id | name | 依赖 | acceptance | verify_cmd | 文件 |
|----|------|------|------------|------------|------|
| p13-h4-01 | Arbiter 接入写作环 | [] | checkContinuityAfterWriting 调 Arbiter；合并 brief 一次重写；三器复检 | `mvn test -Dtest=ArbiterWiredTest` | `orchestrator/NovelOrchestrator.java` |
| p13-h4-02 | 复检路径 fail-loud | [p13-h4-01] | 无 catch (Exception ignored)；失败 emit DETECT_FAILED | `mvn test -Dtest=FailLoudTest` | `orchestrator/NovelOrchestrator.java` |
| p13-h4-03 | 伏笔活跃集 + 禁止静默降级 | [] | 上下文 ≤12 条 mustResolve 优先；完结不得 setMustResolve(false) | `mvn test -Dtest=ForeshadowActiveSetTest` | `memory/ContextAssembler.java`、`orchestrator/NovelOrchestrator.java` |

### 第五波 · H5 可发现性

| id | name | 依赖 | acceptance | verify_cmd | 文件 |
|----|------|------|------------|------------|------|
| p13-h5-01 | 人审标记进 DTO | [] | 质量/章节列表暴露 NEEDS_MANUAL_REVIEW 与 quality_risk | `mvn test -Dtest=ManualReviewSurfaceTest` | `web/ContentController.java`、`orchestrator/NovelOrchestrator.java` |
| p13-h5-02 | 创建项目选篇幅 | [] | 24/100/200/400 写入 targetChapters；前端 build 通过 | `mvn test -Dtest=ProjectServiceTest` | `frontend/src/pages/ProjectListPage.tsx`、`orchestrator/ProjectService.java` |

共 **15 个 feature**。实施顺序：H1 全做完才能「中途改了后面认」；H2/H4 可与 H1 后半并行；H3 依赖 H1 失效；H5 最后。

## 风险与备选方案

**风险与缓解：**

- **人改与自动收割打架**：用户刚补的 CHARACTER_STATE 可能被下一章 FactHarvester supercede。缓解：Harvester 对 `【HUMAN_PATCH】` 且 `lastValidated` 在 5 章内的事实，冲突时**保留补丁、上报 issue**，不自动 supercede。H1-01 测试覆盖。
- **PUT 与正在 write-all 竞态**：缓解：H1-03 要求先 pause；API 在存在 RUNNING 任务时对设定 PUT 返回 409，提示先暂停。
- **重规划 LLM 失败**：缓解：失败保留旧未写卷 + 记 quality_risk，不写半截 outline。
- **bible 必入撑爆窗口**：缓解：必入上限 24 条（补丁+铁律+本章状态），超出按 importance 再截必入；填充另计。H2 测试断言铁律仍在。
- **前端编辑简陋**：本 phase 接受 Ant Design 表单级编辑，不重做视觉。
- **NovelOrchestrator 继续膨胀**：H1/H3 强制抽 Service；审查时若仍把 200 行补丁写进 Orchestrator 视为 feature 失败。

**被否决的方案：**

- **改正文回溯重写**：否决（Phase 10 已定）。成本爆炸。
- **只做前端编辑、不写真典**：否决。改了卡面后面不认，是当前最大洞。
- **先再加 3 个检测器**：否决。委员会都没进主循环。
- **默认改回 400 章**：否决。短篇验证路径不能被毁；长篇显式选择。
- **RelevanceGateway 本 phase 上 HNSW/SQL 分页大改**：否决。先改召回策略，物理分页另立 spec。
- **把 quality_risk 升为一等列**：本 phase 不做迁移；前缀已能过滤。

## 评审检查清单（spec 自检）

- [x] 所有 feature 均有 dependencies / acceptance / verify_cmd，acceptance 可机器验证（H1-05 前端为 build）。
- [x] 每个 feature 1-3 个文件。
- [x] 证据 A–D 每个缺口都有 feature 覆盖。
- [x] 分波边界明确：H1 脊柱 → H2 召回 / H4 质检 → H3 重规划 → H5 表面。
- [x] 状态语义只激活已有枚举，无新 NovelState。
- [x] 无 TBD/占位；验收命令对齐仓库约定。
- [x] 锁定决策：不回溯改正文、不改默认 24 章、不加检测器、人补丁用【HUMAN_PATCH】前缀、write-all 必须停在 PAUSED。
- [x] 与 Phase 10/11 不重复造轮子：复用 StoryFact / MemoryRevision / Arbiter / QualityCouncil / FactHarvester / Checkpoint，只补「未接线 / 假实现」。
