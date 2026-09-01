# Spec: 过程化质量门禁架构（Quality Gate Architecture）

> 文件名约定：`docs/specs/YYYY-MM-DD-<slug>.md`。新需求必须先落 spec，再拆 feature。
> 本 spec 为决策完备（decision-complete）文档：评审通过后可直接进入 `feature_list.json` 登记与逐 feature 实现。
> 上游 review：本会话对「质检 Agent 体系 / 编排流程 / 长程一致性机制 / 已知问题与可观测性」四子系统的代码级审查（基于 `src/main/java` + `src/test` 实读 + file:line 证据），结论见「背景与动机」。
> 上游 spec：`docs/specs/2026-08-13-million-word-architecture.md`（Phase 10）。本 spec 为其质量闭环维度的延续与纠偏。

## 背景与动机

WritingAgent 已在百万字架构层面完成 Phase 10 的 23 个 feature（故事真典 / 检索门面 / 分层记忆 / 可靠作业 / 结构演进）。但一次面向**「创作质量的过程化控制」**的代码级复查发现两个层面的问题：

1. **过程化质量干预缺位**：系统的质量哲学是「事后检测 + 单点重写」，大纲/世界观/人物的生成「生成即采纳、零前置门禁」，章节质检器串行、互斥、互不可见——这正是「最后才发现问题、反复修改」的结构性根因。
2. **Phase 10 质量闭环声称已完成但代码验证未落地**：W2-05 / W2-07 / W3-08 在 `feature_list.json` 标 `completed`，但代码级 grep + 构造器签名验证显示其**承诺的检测侧改造并未真正接入代码**。在未落地的地基上叠加新门禁是危险的——必须先补齐。

### 证据 A：质检是「事后补救」，过程化干预几乎为零

| 生成步骤 | 现状 | 位置 |
|---|---|---|
| WorldBuilder / CharacterDesigner / Plotter 生成 | 解析即入库，**零语义门禁**（仅空输出→fallback 自愈） | `NovelOrchestrator.java:269/297/381` |
| 章节梗概 ChapterSynopsis | 写正文前**无可行性/一致性预检**，占位梗概也被直接写 | `createPlaceholderVolume:1897` |
| Cliche/Diversity/Climax 「过程中约束」 | 全是 prompt 软引导，**事后无「是否遵守」的硬校验回环** | `ContextAssembler.java:602/640/648` |

### 证据 B：章节质检闭环「互斥、不复检、不协同」

- 单章最多 1 次重写，三检测器互斥（`rewrittenByContinuity` 标志）：一章同时有连续性矛盾 + 读者评分低时**只修一个**（注释明说「防成本翻倍」，`NovelOrchestrator.java:569/608/1018`）。
- `continuity` 重写后**只标 resolved 不重跑检查**（`:957-962`）——重写可能引入新矛盾而无人发现。
- 质量熔断阈值从 3 **权宜放宽到 10**（`:1414-1422`）——最多容忍连续 9 章 HIGH 才暂停。

### 证据 C：Phase 10 质量闭环「声称完成 vs 代码未落地」（spec 纠偏，本 spec 核心论据）

代码级验证（2026-08-14）：

| spec 声称 | feature_list 状态 | 代码验证结果 |
|---|---|---|
| W2-05 检查器改走 RelevanceGateway 收窄 | completed | **未落地**：`RelevanceGateway` 仅被 `ContextAssembler`/`MemoryConfig`/自身引用；`ConsistencyGuard`/`ContinuityCheckerAgent`/`EntityExtractorAgent`/`CharacterProfileUpgrader`/`QualityEvaluationAgent` **0 引用**，构造器只注入 `LlmGateway`/`NovelRepository`/`PromptTemplateService` |
| W2-07 检查器读真典 ACTIVE 事实 | completed | **未落地**：上述 5 检查器对 `StoryFact`/`storyFactRepository` 的引用数**全部为 0**；仅 `ContextAssembler.buildBibleSection` 与 `replanRemainingOutline` 消费真典 |
| W3-08 闭环修复三分类仲裁 | completed | **只实现 1/3**：`rewriteWithCorrection` 只做「重写当前章」；`Chapter.Status.NEEDS_MANUAL_REVIEW` 枚举**全代码库仅出现在 `Chapter.java:13/15` 定义处，无任何赋值点** |
| W3-03 ArchiveCompactor 归档 5 类 | completed | **只做 2/5**：`deleteExpiredEdges` + `archiveOldLowSeverityIssues`；`foreshadowings`/`narrative_threads`/`knowledge_entries` 无归档 |
| line 61 人物单轨化（Character 留稳定身份，真典接可变状态） | （spec 承诺） | **未做**：`CharacterProfileUpgrader:149-153` 仍覆盖式写 `Character.ability/growthArc`，与真典 `CHARACTER_STATE` 双轨并行无裁决 |

### 证据 D：质量闭环是「黑盒」，回归门禁失效

- `EventLog` 仅 4 类机械事件（`LLM_CALL`/`LLM_ERROR`/`CHAPTER_WRITTEN`/`CHAPTER_FAILED`）。质检评分、重写决策、熔断、FINAL 结论**全部只落 `app.log`**，`diagnose.sh` 对质量问题完全不可见。
- `golden.json` 的 `minEvaluationScore: null`——`compare-baseline.py:40` 直接跳过，**质量评分不被任何自动门禁守住**。
- 质检反馈重写三条路径**零专门测试**；集成测试把 `readerReviewAgent` 置 null，从不触发重写。
- `ConsistencyGuard.java:76/141` 两处 `catch(Exception ignored)`、`rewriteWithCorrection:1118-1132` 多处静默 return/丢弃——失败零信号。

### 核心洞察

系统当前是**「成本优先、事后补救」**哲学（单次重写、检测器互斥、阈值放宽、静默吞异常）。目标是转向**「质量优先、过程拦截、可观测」**。这是一次范式转变，需要：① 补齐声称已落地但未落地的真典/收窄/三分类地基；② 在构思→规划→写作三阶段插入分层前置门禁；③ 把串行互斥的检测器重构为统一仲裁 + 合并重写 + 全量复检；④ 让质量决策结构化可观测、被回归门禁守住。

## 目标

1. **大纲/梗概生成过程中实时质检**：WorldBuilder / CharacterDesigner / Plotter 生成后、章节梗概写正文前，经分层门禁（ConceiveGate / PlanGate）评估，不过关打回重生成（有限次），仍不过则放行并标记 `quality_risk`，INTERACTIVE 模式可暂停供人工优化。
2. **章节质检统一仲裁 + 合并重写 + 全量复检**：四个检测器先各自出报告，Arbiter 汇总判级、三分类仲裁、生成合并纠偏指令，一次重写喂入全部问题，重写后全部检测器复检。
3. **真典作为一致性检测的唯一真相层**：检查器经 RelevanceGateway 收窄 + 读真典 ACTIVE 事实；人物单轨化（可变状态由真典承载）；三分类仲裁落地、`NEEDS_MANUAL_REVIEW` 激活。
4. **质量闭环可观测 + 回归门禁生效**：质量评分/重写决策/熔断/门禁结论结构化入 `events.jsonl`；`diagnose.sh` 能诊断质量分类；`compare-baseline` 重新启用 `minEvaluationScore` 门禁。
5. **稳定性硬化**：消除静默吞异常（fail-loud）；质量熔断与重写预算可配置；质检闭环有专门测试网。

## 非目标

- **不改已发布正文回溯重写**：延续 Phase 10 边界，早期根源矛盾靠「纠正真典 + 标 `NEEDS_MANUAL_REVIEW`」让人工处理，不连锁重写正文。
- **不改 Agent 间不直接调用的约束**：新增的 Arbiter / ConceiveGate / PlanGate 仍经 NovelOrchestrator 调度。
- **不改既有 `.st` 模板变量名**：影响产出质量的 prompt 文案调整走另立小 spec；门禁/仲裁在编排与组装层完成。
- **不引入真实 tokenizer / 强制 ANN 迁移**：延续 Phase 10 边界。
- **不做质量门禁的「硬阻塞」**：按用户决策采用「软门禁 + 人机协同」——次数上限后放行并标记，不永久卡死推进（硬阻塞会令长篇作业无法收尾）。
- **不重做 Phase 10 已正确落地的部分**：真典播种/收割/消费（写作侧）、伏笔三件套、分层记忆、可靠作业保留；本 spec 只补「检测侧未落地」+「过程化门禁」。

## 设计哲学转变

| 维度 | 现状（成本优先） | 目标（质量过程化） |
|---|---|---|
| 检查时机 | 事后（写完后） | **分层前置 + 事后协同** |
| 真相层 | 检测器各自全量加载、与真典脱节 | **真典为唯一真相层，检测器收窄读真典** |
| 多检测器 | 串行、互斥、互不可见 | **质检委员会：各自出报告 → 统一仲裁 → 合并重写 → 全量复检** |
| 重写调度 | 检测器互斥，第一次命中触发 | **仲裁层按预算统一调度**（同成本，质量收益翻倍） |
| 门禁失败 | 无门禁 / 静默吞 | **软门禁：次数上限 → 标记 quality_risk → 人机暂停** |
| 可观测性 | 质量决策只在 app.log | **结构化质量事件 + 质量回归门禁** |
| 失败处理 | `catch(Exception ignored)` | **fail-loud：必发事件 + 计数 + 熔断** |

## 目标架构边界（四大支柱）

### 支柱 ① 过程化前置门禁（ConceiveGate + PlanGate）★ 对应目标 1

新增门禁抽象 `QualityGate`：`gate.check(产出, 上下文) → GateResult{pass, issues[], severity, retryable}`。两类门禁：

- **ConceiveGate（构思期）**：在 `doConceiving` 的 WorldBuilder / CharacterDesigner / Plotter **各自生成后**插入。低成本档=纯规则（去重/计数/结构完整性/题材词表），语义档=LLM 评分。
  - WorldBuilderGate：力量体系内部自洽、势力不重复、与 EraAnchor 不冲突。
  - CharacterGate：人物不重复、动机自洽、能力体系在 WorldBuilder 约束内、主角-对手张力存在。
  - OutlineGate：分卷节奏均衡、伏笔密度合理、主线钩子存在、章节量达标（升级现有 `<50%` 重试为门禁）。
- **PlanGate（规划期）**：在 `tryExtendLayeredOutline`/`parseAndAppendVolume` 解析出 ChapterSynopsis 后、写正文前，做轻量预检：
  - 本章梗概是否与真典 ACTIVE 事实冲突（能力/关系/时间线）。
  - 本章是否推进至少一条挂得最久的叙事线索/伏笔（避免空转章）。
  - cast 是否与 ArcScheduler 建议一致。
  - 预检不过 → 反馈 Plotter **重生成该卷梗概**（省一个数量级 token，相比写完正文再重写）。

**软门禁策略（用户决策）**：门禁不过 → 带反馈重生成，最多 `gateMaxRetries` 次（默认 2，可配置）；仍不过 → 放行并标记 `quality_risk`（落 `Chapter`/`Outline` 元数据 + 发 `CONCEIVE_GATE`/`PLAN_GATE` 事件）；INTERACTIVE 模式 → `PAUSED_AT_CHECKPOINT` 供人工优化后 `resumeFromCheckpoint`。**不硬阻塞推进**。

### 支柱 ② 章节质检委员会 + 统一仲裁（QualityCouncil + Arbiter）★ 对应目标 2

重构 `checkContinuityAfterWriting` + reader-review 为三阶段：

```
阶段1 评审（不重写）:
  ContinuityChecker / ConsistencyGuard / ReaderReview / StyleGuardian 各出报告
  → CouncilReport{ issues[], scores{}, suggestions[] }
阶段2 仲裁（Arbiter）:
  汇总去重/判级 → 三分类根因 → 合并 correctionBrief
  动作决策: PASS | REWRITE(correctionBrief) | BLOCK(熔断)
阶段3（仅 REWRITE）合并重写 + 全量复检:
  ChapterWriter.rewriteForCorrection(correctionBrief)   ← 喂入所有检测器问题
  重写后全部检测器复跑（不再只复检 ConsistencyGuard）
```

- **三分类根因仲裁**（落地 W3-08 承诺）：正文笔误/越界→重写当前章；真典需演进→supercede；早期根源矛盾→标 `Chapter.NEEDS_MANUAL_REVIEW` + 纠正真典。
- **解除互斥，改预算调度**：保留「单章重写预算有限」（默认 1 次，可配置 0/1/2），但预算由 Arbiter 统一分配——一次重写联合修正多维问题。同成本，质量收益翻倍。
- **全量复检**：重写后 ContinuityChecker/ConsistencyGuard/ReaderReview 全部复跑（修复 `continuity` 重写不复检）。

### 支柱 ③ 真典作为一致性检测的唯一真相层 ★ 对应目标 3

落地 Phase 10 声称但未落地的检测侧改造：

- **检查器收窄 + 读真典**：`ConsistencyGuard`/`ContinuityChecker`/`EntityExtractor`/`CharacterProfileUpgrader`/`QualityEvaluation` 经 `RelevanceGateway` 取本章相关切片（向量召回 + 结构查询 by cast/synopsis/chapterIndex）+ 注入真典相关 ACTIVE 事实作为校验依据。**禁止任何检查器再直接 `findAll*` 全量加载**（落地 W2-05/W2-07）。
- **人物单轨化**：`CharacterProfileUpgrader` 改为写真典 `CHARACTER_STATE` 而非覆盖 `Character.ability/growthArc`；`Character` 只留稳定身份（姓名/外貌/核心性格/别名），可变状态（修为/目标/位置/关系）由真典承载；`ContextAssembler.toSystemPrompt` 可变字段改从真典读。**消除双轨冲突源**。
- **真典注入 cap 动态化**：当前硬编码 `cap=25`，百万字下丢事实。改为按章节相关性 + importance 召回（`relevantFacts` 已有雏形），本章相关事实必入。
- **归档衰减补齐**：`ArchiveCompactor` 增加 `foreshadowings`/`narrative_threads`/`knowledge_entries` 归档（落地 W3-03 的 3/5 缺口），压小活跃集。

### 支柱 ④ 可观测质量遥测 + 稳定性硬化 + 回归网 ★ 对应目标 4、5

- **质量事件**（扩展 `EventLog`）：

  | 事件 | 触发点 | 关键字段 |
  |---|---|---|
  | `CONCEIVE_GATE` | 支柱①构思门禁 | stage, pass, issues, retries, quality_risk |
  | `PLAN_GATE` | 支柱①梗概预检 | chapterIndex, pass, conflicts |
  | `QUALITY_REVIEW` | 委员会评审 | chapterIndex, 各维度分, overall |
  | `REWRITE_TRIGGERED` | 仲裁决定重写 | chapterIndex, reason, correctionBrief摘要, 复检结果 |
  | `CONSISTENCY_ISSUE` | 检测发现 | chapterIndex, type, severity, rootClassification |
  | `QUALITY_CIRCUIT_BREAKER` | 质量熔断 | consecutiveCount, threshold, kind(highIssue\|rewriteFail) |
  | `FINAL_GATE` | 成书门禁 | pass, hardFailReasons, softWarnings, cost |

- **diagnose.sh 质量诊断分类**：重写成功率、最常见 issue 根因、熔断次数、FINAL 失败原因、quality_risk 标记章节。
- **fail-loud**：消除 `ConsistencyGuard`/`rewriteWithCorrection` 静默吞异常，失败必发 `CONSISTENCY_ISSUE(severity=DETECT_FAILED)` 事件 + 计数。
- **回归门禁**：`golden.json` 设 `minEvaluationScore`（如 7.0），`compare-baseline.py` 重新启用；补质检闭环专门测试网。
- **FinalAuditGate 扩展**（吸收 p10-w3-09）：真典 `SUPERSEDED>20` 告警 + 成本回执 + 卷边界全局一致性扫描。

## 状态语义（增量）

- 沿用 Phase 10 的 `Chapter.Status`（`DRAFTING`/`POST_PROCESSING`/`DONE`/`NEEDS_MANUAL_REVIEW`）。本 spec **激活** `NEEDS_MANUAL_REVIEW` 的赋值路径（三分类早期根源矛盾）。
- 新增可空标记位（兼容旧库）：
  - `chapters.quality_risk`（门禁/仲裁软放行标记，默认 null）。
  - `outline` / 卷大纲解析产物携带 `gate_result` 摘要（可选，仅记录用）。
- 沿用 `NovelState` 的 `PAUSED_AT_CHECKPOINT`（INTERACTIVE 模式门禁暂停入口），新增 checkpoint 类型 `AT_QUALITY_GATE`。

## 验收标准（分波，机器可验证）

### 第零波 · 地基补齐（Q0）— 补 Phase 10 未落地

- [ ] `mvn test -Dtest=RelevanceGatewayNarrowingTest` 通过：ConsistencyGuard/ContinuityChecker/EntityExtractor/CharacterProfileUpgrader 经 RelevanceGateway 取切片，构造器注入验证，不再全量 `findAll*`（复现证据 C 的 W2-05 未落地）。
- [ ] `mvn test -Dtest=CanonConsumedByCheckersTest` 通过：上述 5 检查器注入并消费真典相关 ACTIVE 事实作为校验依据（复现 W2-07 未落地）。
- [ ] `mvn test -Dtest=CharacterSingleSourceTest` 通过：CharacterProfileUpgrader 写真典 CHARACTER_STATE 不覆盖 Character；ContextAssembler 可变状态读真典；Character 留稳定身份（复现 line 61 未做）。
- [ ] `mvn test -Dtest=ClosedLoopRepairClassificationTest` 通过：三分类仲裁（重写/supercede/早期根源标记 NEEDS_MANUAL_REVIEW），`NEEDS_MANUAL_REVIEW` 出现赋值点（复现 W3-08 只 1/3）。
- [ ] `mvn test -Dtest=RewriteRecheckTest` 通过：continuity HIGH 重写后重跑检查，引入的新矛盾可见（复现 evidence B 不复检）。
- [ ] `mvn test -Dtest=ArchiveCompactorTest`（扩展）通过：foreshadowings/narrative_threads/knowledge_entries 归档衰减（复现 W3-03 只 2/5）。

### 第一波 · 过程化前置门禁（Q1）

- [ ] `mvn test -Dtest=ConceiveGateTest` 通过：WorldBuilder/CharacterDesigner/Plotter 生成后各自门禁（力量体系自洽/势力不重复/人物去重/分卷节奏/伏笔密度/章节量），不过关带反馈重生成。
- [ ] `mvn test -Dtest=GatePolicyTest` 通过：软门禁策略——`gateMaxRetries` 次后放行并标记 `quality_risk`；INTERACTIVE 模式 PAUSED 供人工 resume；不硬阻塞。
- [ ] `mvn test -Dtest=PlanGateTest` 通过：章节梗概写正文前预检（真典冲突/线索推进/cast 合理性），不过关重生成梗概而非写完正文再重写。

### 第二波 · 质检委员会 + 统一仲裁（Q2）

- [ ] `mvn test -Dtest=QualityCouncilTest` 通过：四检测器先出报告不重写，汇总为 CouncilReport。
- [ ] `mvn test -Dtest=ArbiterTest` 通过：汇总判级→合并 correctionBrief→三分类→动作 PASS/REWRITE/BLOCK。
- [ ] `mvn test -Dtest=MergedRewriteRecheckTest` 通过：一次重写喂入全部问题；重写后全部检测器复检；解除互斥。
- [ ] `mvn test -Dtest=RewriteBudgetPolicyTest` 通过：per-project 0/1/2 重写预算，仲裁层统一调度。
- [ ] `mvn test -Dtest=QualityCircuitBreakerTest` 通过：熔断阈值可配置；重写失败率熔断。

### 第三波 · 可观测性 + 稳定性 + 回归网（Q3）

- [ ] `mvn test -Dtest=QualityEventLogTest` 通过：7 类质量事件正确 emit。
- [ ] `./scripts/diagnose.sh`（手动验证）输出质量诊断分类。
- [ ] `mvn test -Dtest=FailLoudTest` 通过：ConsistencyGuard/rewriteWithCorrection 失败必发事件 + 计数，无静默吞。
- [ ] `python3 scripts/compare-baseline.py` 对 `minEvaluationScore` 阈值生效（`golden.json` 设阈值后 FAIL 可被捕获）。
- [ ] `mvn test -Dtest=FinalAuditGateTest`（扩展）通过：真典 SUPERSEDED>20 告警 + 成本回执 + 卷边界全局扫描（吸收 p10-w3-09）。
- [ ] `mvn test` 全量绿；`./scripts/e2e.sh` PASS，compare-baseline PASS。

## 影响面

- **涉及模块**：`orchestrator/`（NovelOrchestrator 门禁接入 + 委员会/仲裁重构 + 复检）、`agent/`（新增 Arbiter/ConceiveGate/PlanGate；改造 ConsistencyGuard/ContinuityChecker/EntityExtractor/CharacterProfileUpgrader/QualityEvaluation/CharacterProfileUpgrader）、`memory/`（RelevanceGateway 扩展、ContextAssembler 人物单轨读取、真典 cap 动态化）、`service/`（ArchiveCompactor 补 3 类、熔断策略）、`telemetry/`（EventLog 7 类新事件 + EventContext 扩展）、`domain/`（Chapter.quality_risk）、`persistence/sqlite/`（quality_risk 列迁移）。
- **涉及 prompt 模板**：不改既有 `.st` 变量名。新增可选模板 `conceive-gate.st`/`plan-gate.st`/`arbiter.st`（新文件）。
- **数据库 schema 变更**：`chapters` 加可空 `quality_risk` 列（默认 null 兼容）；走 `safeAddColumn` 幂等链。
- **质量基线影响**：Q3 重新启用 `minEvaluationScore` 后需 `compare-baseline.py --bless` 建立含质量阈值的基线。
- **配置**：`WritingPreferences` 新增 `gateMaxRetries`/`rewriteBudget`/`qualityCircuitBreakerThreshold`（均有默认值，向后兼容）。

## Feature 拆分

> 拆分规则见 AGENTS.md「需求拆分协议」。每个 feature 1-3 个文件、可独立验证、附 machine-verifiable acceptance 与 verify_cmd。Phase 11。四波：Q0 地基补齐（无跨波依赖可先行）；Q1 前置门禁（依赖 Q0 真典）；Q2 委员会+仲裁（依赖 Q0）；Q3 可观测+稳定性+回归（横切，部分可先行）。

### 第零波 · 地基补齐（Q0）— 补 Phase 10 未落地，先行

| id | name | 依赖 | acceptance | verify_cmd | 文件 |
|----|------|------|------------|------------|------|
| p11-q0-01 | 检查器接入 RelevanceGateway 收窄 | [] | ConsistencyGuard/ContinuityChecker/EntityExtractor/CharacterProfileUpgrader 构造器注入并经门面取切片，不再全量 findAll*；输入 token 受控 | `mvn test -Dtest=RelevanceGatewayNarrowingTest` | `agent/ConsistencyGuard.java`、`agent/ContinuityCheckerAgent.java`、`memory/RelevanceGateway.java` |
| p11-q0-02 | 检查器消费真典 ACTIVE 事实 | [p11-q0-01] | 5 检查器注入并消费真典相关 ACTIVE 事实作为校验依据（grep StoryFact 引用>0） | `mvn test -Dtest=CanonConsumedByCheckersTest` | `agent/ConsistencyGuard.java`、`agent/ContinuityCheckerAgent.java`、`memory/RelevanceGateway.java` |
| p11-q0-03 | 人物单轨化 | [p11-q0-02] | CharacterProfileUpgrader 写真典 CHARACTER_STATE 不覆盖 Character.ability/growthArc；ContextAssembler 可变状态读真典；Character 留稳定身份 | `mvn test -Dtest=CharacterSingleSourceTest` | `agent/CharacterProfileUpgrader.java`、`memory/ContextAssembler.java` |
| p11-q0-04 | 闭环三分类仲裁落地 | [p11-q0-02] | 仲裁 正文错→重写/真典演进→supercede/早期根源→NEEDS_MANUAL_REVIEW+纠正真典；NEEDS_MANUAL_REVIEW 出现赋值点 | `mvn test -Dtest=ClosedLoopRepairClassificationTest` | `orchestrator/NovelOrchestrator.java`、`domain/Chapter.java` |
| p11-q0-05 | continuity 重写后复检 | [p11-q0-04] | continuity HIGH 重写后重跑 ContinuityChecker，引入的新矛盾可见且记 issue | `mvn test -Dtest=RewriteRecheckTest` | `orchestrator/NovelOrchestrator.java` |
| p11-q0-06 | 归档衰减补齐 5 类 | [] | ArchiveCompactor 增加 foreshadowings/narrative_threads/knowledge_entries 归档（合并/RETIRED/语义去重） | `mvn test -Dtest=ArchiveCompactorTest` | `service/ArchiveCompactor.java` |

### 第一波 · 过程化前置门禁（Q1）

| id | name | 依赖 | acceptance | verify_cmd | 文件 |
|----|------|------|------------|------------|------|
| p11-q1-01 | ConceiveGate 抽象 + WorldBuilder 门禁 | [] | QualityGate 抽象；世界观生成后力量体系自洽/势力不重复/题材一致性校验，不过关带反馈重生成 | `mvn test -Dtest=ConceiveGateTest` | `service/ConceiveGate.java`、`orchestrator/NovelOrchestrator.java` |
| p11-q1-02 | CharacterDesigner + Outline 门禁 | [p11-q1-01] | 人物去重/动机自洽/能力体系内/对手张力；分卷节奏/伏笔密度/主线钩子/章节量达标 | `mvn test -Dtest=ConceiveGateTest` | `service/ConceiveGate.java` |
| p11-q1-03 | 软门禁策略 + quality_risk + 人机暂停 | [p11-q1-01] | gateMaxRetries 次后放行并标记 quality_risk；INTERACTIVE 模式 PAUSED 供 resume；不硬阻塞 | `mvn test -Dtest=GatePolicyTest` | `orchestrator/NovelOrchestrator.java`、`domain/Chapter.java`、`persistence/sqlite/` |
| p11-q1-04 | PlanGate 章节梗概预检 | [p11-q0-02] | 写正文前梗概 vs 真典冲突/线索推进/cast 合理性预检；不过关重生成梗概 | `mvn test -Dtest=PlanGateTest` | `service/PlanGate.java`、`orchestrator/NovelOrchestrator.java` |

### 第二波 · 质检委员会 + 统一仲裁（Q2）

| id | name | 依赖 | acceptance | verify_cmd | 文件 |
|----|------|------|------------|------------|------|
| p11-q2-01 | QualityCouncil 评审汇总 | [] | 四检测器先出报告不重写，汇总为 CouncilReport{issues,scores,suggestions} | `mvn test -Dtest=QualityCouncilTest` | `service/QualityCouncil.java`、`orchestrator/NovelOrchestrator.java` |
| p11-q2-02 | Arbiter 仲裁器 + 三分类 | [p11-q2-01,p11-q0-04] | 汇总判级→合并 correctionBrief→三分类根因→动作 PASS/REWRITE/BLOCK | `mvn test -Dtest=ArbiterTest` | `service/Arbiter.java` |
| p11-q2-03 | 合并重写 + 全量复检 | [p11-q2-02] | 一次重写喂入全部问题；重写后全部检测器复检；解除互斥（预算统一调度） | `mvn test -Dtest=MergedRewriteRecheckTest` | `orchestrator/NovelOrchestrator.java` |
| p11-q2-04 | 重写预算可配置 | [p11-q2-03] | per-project 0/1/2 重写预算，仲裁层统一调度；超预算降级为标记 quality_risk | `mvn test -Dtest=RewriteBudgetPolicyTest` | `domain/WritingPreferences.java`、`service/Arbiter.java` |
| p11-q2-05 | 质量熔断策略回收 | [p11-q2-03] | 阈值可配置（回收 3→10 权宜放宽）；新增重写失败率熔断 | `mvn test -Dtest=QualityCircuitBreakerTest` | `orchestrator/NovelOrchestrator.java` |

### 第三波 · 可观测性 + 稳定性 + 回归网（Q3）

| id | name | 依赖 | acceptance | verify_cmd | 文件 |
|----|------|------|------------|------------|------|
| p11-q3-01 | 质量事件遥测 | [] | EventLog 新增 7 类质量事件并正确 emit；EventContext 扩展 | `mvn test -Dtest=QualityEventLogTest` | `telemetry/EventLog.java`、`telemetry/EventContext.java` |
| p11-q3-02 | diagnose.sh 质量诊断分类 | [p11-q3-01] | 输出重写成功率/issue 根因/熔断次数/FINAL 失败原因/quality_risk 章节 | `./scripts/diagnose.sh`（手动） | `scripts/diagnose.sh` |
| p11-q3-03 | fail-loud 消除静默吞异常 | [] | ConsistencyGuard/rewriteWithCorrection 失败必发事件 + 计数，无 catch(Exception ignored) | `mvn test -Dtest=FailLoudTest` | `agent/ConsistencyGuard.java`、`orchestrator/NovelOrchestrator.java` |
| p11-q3-04 | compare-baseline 重新启用质量评分门禁 | [] | golden.json 设 minEvaluationScore；compare-baseline 校验生效（FAIL 可捕获） | `python3 scripts/compare-baseline.py` | `baselines/golden.json`、`scripts/compare-baseline.py` |
| p11-q3-05 | 质量闭环测试网 | [p11-q2-03] | 质检反馈重写三路径/ConsistencyGuard 单测/仲裁复检/门禁测试补齐；集成测试不再置 null | `mvn test` | `src/test/...` |
| p11-q3-06 | FinalAuditGate 扩展（吸收 p10-w3-09） | [p11-q0-04,p11-q3-01] | 真典 SUPERSEDED>20 告警 + 成本回执 + 卷边界全局扫描；p10-w3-09 标 completed | `mvn test -Dtest=FinalAuditGateTest` | `orchestrator/NovelOrchestrator.java` |

## 风险与备选方案

**风险与缓解：**
- **Q0 揭露 Phase 10「声称完成」与代码事实不符**：本 spec 诚实记录并作为 Q0 补齐，而非在未落地地基上叠加。缓解：Q0 每项均有独立测试断言「已接入」，回归可见。
- **门禁/仲裁增加 LLM 成本**：ConceiveGate/PlanGate/Arbiter 各多 1 次调用。缓解：(a) 门禁默认低成本的纯规则档，语义档可选；(b) Arbiter 单次调用替代 4 检测器各自重写，净成本未必上升；(c) 重写预算可配 0；(d) 复用 W1 成本熔断兜底。
- **软门禁「放行 + 标记」可能累积 quality_risk**：缓解：quality_risk 章节进 `diagnose.sh` 可见 + FINAL 软告警；INTERACTIVE 模式可人工介入。
- **人物单轨化迁移风险**：Character 卡历史数据可能缺真典对应事实。缓解：迁移期双读（真典优先、Character 兜底），Q0-03 测试覆盖兼容路径；`lastValidatedChapter` 补全。
- **仲裁三分类误判**：早期根源 vs 正文错分类错误会导致误标 NEEDS_MANUAL_REVIEW。缓解：仅 HIGH+根因置信度高时才标 manual review，其余 supercede；FAIL-loud 可观测。
- **打破重写互斥后单章成本上升**：解除互斥但保留「预算统一调度」，默认预算仍为 1，仅调度方式从「先命中」改为「仲裁合并」——成本不变、质量收益提升。

**被否决的备选方案：**
- **硬门禁（不过关永不推进）**：否决。长篇作业无法收尾、可能永久卡死；与用户确认采用「软门禁 + 人机协同」。
- **保留分立检测器只解除互斥**：否决。纠偏指令仍分散、无三分类、无统一复检；改为 Arbiter 统一仲裁收益更大（用户已选）。
- **真典分两期做**：否决。用户已选「全做」；分两期会留下人物双轨漂移这一最痛缺口过久。
- **改正文回溯重写**：否决（延续 Phase 10 边界）。

## 评审检查清单（spec 自检）

- [x] 所有 feature 均有 `dependencies` / `acceptance` / `verify_cmd`，acceptance 为机器可验证断言（Q3-02 例外为脚本手动验证，已标注）。
- [x] 每个 feature 标注 1-3 个文件，满足「需求拆分协议」。
- [x] 四大支柱均有对应 feature 与验收；证据 A/B/C/D 的每个缺口均有 feature 覆盖。
- [x] 分波边界明确：Q0 地基补齐先行（无跨波依赖）；Q1 前置门禁（依赖 Q0 真典）；Q2 委员会+仲裁（依赖 Q0）；Q3 可观测+回归（横切）。
- [x] 状态语义显式定义并与现有 `Chapter.Status`/`NovelState` 对齐，新增列可空兼容迁移。
- [x] 无 TBD/占位段；所有验收命令对齐仓库现有 `mvn test -Dtest=` / `scripts/e2e.sh` / `compare-baseline.py` 约定。
- [x] 已与用户确认：软门禁+人机协同、统一仲裁+合并重写、真典全做、落 spec+拆 feature。
- [x] Phase 10 声称完成但代码未落地项（W2-05/W2-07/W3-08/W3-03/line 61）已在 Q0 显式补齐，spec 自洽。
