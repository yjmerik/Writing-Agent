# Task 8 Report — p26-08 世界观与人物完整字段编辑

日期：2026-09-03

## 状态与范围

- 状态：COMPLETED
- 基线：`abc2410`
- 提交：`9820cd9 fix(content): edit complete world and character fields`；review fix：`fix(review): preserve content canon audit chains`
- 范围只包含人工世界观/人物完整字段编辑、类型/目标校验，以及 `manual-content-management` 中对应的真实 UI 保存与刷新子断言。
- 未修改自动任务、mode switch/resume、大纲/章节编辑、Agent 修订预览、browser harness、Agent、prompt、上下文或产出质量逻辑。

## RED 证据

先创建 `WorldCharacterFullEditTest`，通过真实 Spring HTTP、SQLite repository、StoryCanon 与 MemoryRevision 观察边界行为。第一次有效 RED 为 **4 tests / 4 failures / 0 errors**：

1. 世界观用 `ECONOMY` 更新后重读仍为旧 `GEOGRAPHY`，证明类型未持久化。
2. 人工创建 `ITEM` 返回 200，证明旧类型与目标 allowlist 不一致；同一测试同时锁定全部 9 个合法类型可写。
3. `INVALID` 更新返回 200，并会修改名称/正文、写 StoryCanon、增加 MemoryRevision；测试要求 400 后实体 type/name/content/version、canon snapshot 与 revision 全部不变。
4. 使用另一个 projectId 的 URL 更新目标世界观/人物返回 200；测试要求 400，并锁定 owner/attacker 两个项目的 canon 与 revision 均不变。

所有失败均来自现有产品缺陷，无 Spring context、SQLite 或测试环境错误。

## GREEN 实现

- `CanonPatchService` 定义人工世界观类型精确 allowlist：`GEOGRAPHY`、`POWER_SYSTEM`、`FACTION`、`HISTORY`、`RULE`、`CULTURE`、`ECONOMY`、`RACE`、`CUSTOM`。
- `createWorld` 与 `patchWorld` 在任何 repository、StoryCanon、MemoryRevision 写入前校验类型；`patchWorld` 与 `patchCharacter` 在任何 setter 前校验目标 `projectId`。
- `KnowledgeEntry` 只增加持久化类型所需的最小 `setType`。领域 `Type.ITEM` 与前端中文 label/color 保留给系统生成和存量展示；人工 Select 不提供 ITEM，人工 API 明确拒绝它。
- 世界观已有卡以受控 draft 编辑类型、名称、正文并一次“保存设定”；新增表单复用相同中文类型 Select。
- 人物已有卡显式编辑 `name`、`role`、`personality`、`ability`、`motivation`、`appearance`、`relationships`、`growthArc`，只在“保存人物”时一次提交。失败分支不删除 draft，且不展示 `speakingStyle` 等 task 外字段。
- 成功响应直接更新对应卡片并清理该卡 draft，未引入整页竞态或其他内容管理语义。

## Review round 1

独立评审指出两个 Important：改名/清空会留下可召回的 stale canon，以及人物整卡的三个 CHARACTER_STATE 写入互相取代。严格 TDD 扩展同一真实 HTTP/SQLite 测试，RED 为 **7 tests / 3 failures / 0 errors**：world 与 character 改名后旧 subject 仍 ACTIVE，人物整卡状态断言只剩 growthArc。

- 更新实体前捕获旧 subject 的 WORLD_RULE、CHARACTER_STATE、RELATIONSHIP_STATE ACTIVE 事实。
- 新断言存在时，先为新 subject 写 HUMAN_PATCH，再以既有 `StoryCanon.supersedeFact(old,new)` 建立跨 subject 审计链；旧事实成为 SUPERSEDED 且 `supersededBy` 精确指向新事实。没有真实断言时以 `retire` 归档对应旧事实，不删除审计记录。
- 人物 CHARACTER_STATE 从更新后完整实体一次组合 role/personality/ability/motivation/appearance/growthArc，空值不产生标签；relationships 作为 RELATIONSHIP_STATE 独立一次写入。
- 重复相同整卡保存、ability-only partial 保存后，每个 type+subject 均恰好一条 ACTIVE；partial 的状态断言继续包含未提交的动机与成长弧。
- 集成测试直接断言 `canon.recall` 与 `RelevanceGateway.relevantFacts` 不再输出旧 subject。ContextAssembler 的私有 bible builder 在生产路径通过 RelevanceGateway 消费同一 recall；既有 `StoryBibleConsumptionTest` 已锁定只注入 ACTIVE，且随 full/init 通过，因此未扩张 ContextAssembler 测试 seam。

## 产品 E2E

增强既有 `manual-content-management`，没有创建新 case、移除 `KNOWN_FAILURES` 或弱化大纲/章节子断言：

- 世界观：真实打开 Ant Select、选择“经济”、填写名称/正文、点击保存；API 重读 type/name/content 精确一致，整页刷新后 Select/Input/TextArea 仍一致。
- 人物：真实填写八字段、点击单一“保存人物”；API 重读八字段精确一致，整页刷新后八个受控值及保存入口仍存在。
- 最终结果：**0 PASS / 1 XFAIL / 0 ERROR**。`worldSave` 的 opened/selected/clicked/persisted/refreshed 三字段全部 true；`characterSave` 的 clicked/persisted/refreshed eight fields 全部 true。aggregate 仅因 p26-09..16 尚未实现的大纲/章节子项继续 XFAIL。

## 验证门禁

- Focused：`WorldCharacterFullEditTest,CanonPatchServiceTest,CanonActiveUniquenessTest,HitlCreateWorldCharacterTest` → **17 tests / 0 failures / 0 errors**。
- Frontend：`npm run build` → PASS（TypeScript + Vite，3043 modules；仅既有 bundle warning）。
- Product expected：`manual-content-management --expect-known-failures` → **0 PASS / 1 XFAIL / 0 ERROR**，p26-08 子断言全绿。
- Product strict：`initial-setup-summary`、`checkpoint-card-targeting` → 各 **1 PASS / 0 XFAIL / 0 ERROR**。
- JDK21 `mvn test` → PASS，**532 tests / 0 failures / 0 errors / 1 skipped**。
- JDK21 `./init.sh` → PASS，同为 **532 / 0 / 0 / 1**，另通过 compile、27 个 prompt 完整性和前端生产构建。
- 本 feature 不改变 Agent/prompt/上下文或产出质量，因此按 brief 不执行 replay 质量门禁。

## 自审

- INVALID 与跨项目请求都在目标 setter 和任何 canon/revision 写入前退出；测试对实体版本、字段及两个项目的 side effects 做行为断言。
- 人工 allowlist 精确排除 ITEM，但系统/存量 `KnowledgeEntry.Type.ITEM` 与前端中文显示未删除；没有迁移或改写存量数据。
- 受控 UI 只在成功时清除目标 draft；失败时输入留在页面，人物不再逐字段 `onBlur` 保存。
- 改名旧事实不再停留 ACTIVE：审计记录保留为 SUPERSEDED，并由 `supersededBy` 指向新 subject HUMAN_PATCH；清空保留为 RETIRED。未修改 StoryCanon SPI 或 SQLite 实现。
- 人物整卡与 partial 都从保存后的实体构造状态，不再出现 ability/motivation 被 growthArc 覆盖，也不会生成空标签噪声。
