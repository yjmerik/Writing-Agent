# Spec: 角色姓名锚定（character-name-anchor）

> 触发事件：时代护栏复评后，原 HIGH（朝代年号混用）消失，但暴露新的 HIGH 一致性问题——大纲中主角名"沈墨"，而人物卡/前情摘要/正文为"顾蘅秋"，改名未统一。

## 背景与动机

- 根因定位：`NovelOrchestrator.runConceiving` 在分层大纲模式（百万字默认 targetChapters=400）下调用 `planMasterOutline(projectId, concept, ...)`，**未传入主角名**。主角名只存在于 `theme` 变量，而 `theme` 仅传给传统模式的 `plotter.plan()`。
- 后果：master-outline.st 模板只有 `{{concept}}`（无名字），LLM 自行取名（沈墨），与 CharacterDesigner 产出的主角卡（顾蘅秋）漂移；卷大纲/正文继承错误名。
- 这是百万字分层大纲路径的系统性 bug，任何长篇都会复现。

## 目标

1. master outline 与 volume outline 生成时显式接收并使用主角名 + 人物摘要。
2. ChapterWriter 显式锚定主角名；ContinuityChecker 检测姓名漂移。
3. 通用：主角名从人物卡自动取得，无需人工标注；无主角卡时安全降级。

## 非目标

- 不解决配角名漂移（配角数量多，单独议题）。
- 不做姓名归一化/实体消歧（依赖现有 EntityGraphService）。

## 验收标准

- [ ] `mvn test -Dtest=MasterOutlineNameInjectionTest` 通过：planMasterOutline 接收主角名并渲染到模板变量。
- [ ] `mvn test -Dtest=VolumeOutlineNameInjectionTest` 通过：planVolume 接收主角名。
- [ ] `mvn test -Dtest=CharacterNameAnchorTest` 通过：ChapterWriter 约束含主角名锚点；ContinuityChecker 注入姓名漂移检测。
- [ ] `mvn test` 全绿。
- [ ] 3 章 e2e record + 质量评估：大纲/人物卡/正文主角名一致（人工确认或一致性维度不出现改名 HIGH）。

## 影响面

- **涉及模块**: agent（PlotterAgent, ChapterWriterAgent, ContinuityCheckerAgent）、orchestrator（NovelOrchestrator）、prompts（master-outline.st, volume-outline.st）
- **涉及 prompt 模板**: master-outline.st / volume-outline.st **新增变量** `{{protagonistName}}`（不改已有变量名）
- **数据库 schema 变更**: 无
- **质量基线影响**: 需重跑 e2e 并 `--bless`

## Feature 拆分

| id | name | 依赖 | acceptance | verify_cmd |
|----|------|------|------------|------------|
| cn-01 | master outline 主角名注入 | [] | planMasterOutline 接收 protagonistName+characterSummary；模板含主角名原则；大纲使用此名 | `mvn test -Dtest=MasterOutlineNameInjectionTest` |
| cn-02 | volume outline 主角名注入 | [cn-01] | planVolume 接收 protagonistName；volume-outline.st 含主角名 | `mvn test -Dtest=VolumeOutlineNameInjectionTest` |
| cn-03 | 写作与检查的姓名锚定 | [] | ChapterWriter 约束含"主角名必须为X"；ContinuityChecker 注入姓名漂移检测 | `mvn test -Dtest=CharacterNameAnchorTest` |

## 风险与备选方案

- **风险**：主角名含特殊字符破坏模板。缓解：Map.of 已支持任意字符串；模板用「」包裹。
- **风险**：无主角卡（CharacterDesigner 失败）时 protagonistName 为空。缓解：空时不注入约束，退化为现状。
