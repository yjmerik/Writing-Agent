# Spec: 人机设定扩容与作业可观测（HITL Expansion）

> 文件名约定：`docs/specs/YYYY-MM-DD-<slug>.md`。新需求必须先落 spec，再拆 feature。
> 决策完备。范围锁定为方案 **B**：人可新增设定/人物 + Agent 暂停态增量补卡 + 未写规划可编 + 真典/伏笔/人审可操作 + 构思/写章任务化。
> 上游：`docs/specs/2026-08-16-million-word-human-canon.md`、`docs/specs/2026-08-17-human-canon-hardening.md`。
> 用户确认：2026-08-18，INTERACTIVE 默认仍每章暂停（`checkpointSpan=1`）。

## 背景与动机

Phase 13–14 打通了「改已有卡 → 真典 HUMAN_PATCH」和删除/fail-loud/异步段落。2026-08-18 交互复查证明：**构思结束后世界观/人物集合闭合**。

| 卡点 | 代码事实 |
|---|---|
| 不能新增世界观条目 | `ContentController` 仅 `PUT /worldbuilding/{id}`，无 POST；`WorldBuilderAgent.build` 只在 `doConceiving` |
| 不能新增人物卡 | 无 `POST /characters`；`expandCharacterEnsemble` 只在构思；写作中新角色进 Entity 图，每 5 章才可能升级 |
| 大纲树只读 | UI 只 PUT premise；`patchOutline` 能整包换卷但 UI 不发 volumes |
| 真典不能改/废 | PUT facts 已有，UI 未接；无 RETIRED 入口 |
| 伏笔无人种/人收 | 仅 `POST /audit-foreshadowings`，无 UI |
| `NEEDS_MANUAL_REVIEW` 无动作 | DTO 有旗，UI 不渲染 |
| 构思 300s abort | `client.ts` 默认 300000；长篇 conceiving 多步 LLM，前端失败、后端继续 |
| write-all 不能取消 | `POST .../writing-tasks/{id}/cancel` 存在，RunConsole 未接 |
| 单次写一章不登记 active task | `rejectIfWriting` 只看 write-all，边写边改设定可能打架 |
| 偏好每下都 chat LLM | 滑条/开关走 `chatPreferences`，PUT `/preferences` 未用 |

百万字第 80 章需要新势力/新配角时，人加不进卡面，Agent 写出来的人进不了结构化记忆，后续章无法稳定遵守。

## 目标

1. **设定集合可增长**：人可新增世界观条目和人物卡；暂停态可让 Agent 增量补设定/补人物，或把图实体提升为人设卡。一律 CanonPatch，后续未写章能读到。
2. **未写规划可编可重画**：`chapterIndex > current` 的梗概/卷名可编辑；按钮触发 OutlineReplanner，已写梗概不动。
3. **作业可看可停可取消**：构思与写章走 task 轮询，不再 300s 当失败；write-all 可取消；有 active task 时设定写 409、删项目 409。

## 非目标

- 已写章节不自动连锁重写
- 不加新检测器 / 新 Agent 种类（复用 WorldBuilder / CharacterDesigner / Upgrader / Replanner）
- 不做 SSE（任务轮询）
- 不物理删除人物行、设定行、章节
- 不把默认 `targetChapters` 改回 400
- 不改 `.st` 变量名
- 不把 INTERACTIVE 默认从每章暂停改成每卷（仅增加可选 `checkpointSpan`）

## 设计决策（锁定）

### D1. 人新增世界观 / 人物

```
POST /api/projects/{id}/worldbuilding
  body: { type, name, content }
  → KnowledgeEntry + CanonPatch WORLD_RULE 或 OBJECT_STATE（ITEM）
  → 409 if rejectIfWriting

POST /api/projects/{id}/characters
  body: { name, role, personality?, ability?, motivation?, appearance?, relationships?, growthArc? }
  → Character + CanonPatch CHARACTER_STATE
  → 409 if rejectIfWriting
```

UI（`WorldbuildingView`）：「添加设定」「添加人物」表单；提交后刷新列表。已有条目仍走 PUT。

`rejectIfWriting`：`hasActiveTask(projectId)` 为真则 409。active 包含 write-all **以及** 进行中的 `write-chapter` / `run` 任务。

废止用真典 RETIRED/SUPERSEDE，不提供 DELETE 人物/设定。

### D2. Agent 增量补卡（仅非 RUNNING）

```
POST /api/projects/{id}/expand/world
  body: { instruction }
  → WorldBuilder 增量追加 KnowledgeEntry，禁止 deleteKnowledgeEntriesByProject
  → 每条走 CanonPatch（source=SYSTEM 或 HUMAN 若人点的按钮则 HUMAN）

POST /api/projects/{id}/expand/characters
  body: { instruction }
  → CharacterDesigner.design，existingChars 传入已有名单，不覆盖旧卡

POST /api/projects/{id}/characters/promote/{entityId}
  → 单条图实体 → Character 卡（抽出 CharacterProfileUpgrader 的单实体路径）
```

人点的扩容按钮 `source=HUMAN`（真典前缀 HUMAN_PATCH）。每 5 章自动升级可保留，UI 也可手动提升。

写作主循环仍不自动调 WorldBuilder。

### D3. 未写规划

- UI：大纲树中 `chapterIndex > project.currentChapterIndex` 的 synopses 与未写卷 title/summary 可编辑；保存 PUT 整包 outline（已写卷原样回传）。
- `POST /api/projects/{id}/replan`：调用 `OutlineReplanner.replanRemaining`；INTERACTIVE 创建 `AFTER_VOLUME_OUTLINE` 并 PAUSED。
- 已写章梗概只读。

### D4. 真典 / 伏笔 / 人审

- 真典 UI：改 assertion → 现有 PUT；「废止」→ status RETIRED（若无 RETIRED 写路径则 SUPERSEDE 为「已废止：…」）。
- 伏笔：
  - `GET /api/projects/{id}/foreshadowings`
  - `POST /api/projects/{id}/foreshadowings` `{content, mustResolve?}`
  - `POST /api/projects/{id}/foreshadowings/{fid}/resolve`
  - UI：折叠面板，人種/人收。
- `NEEDS_MANUAL_REVIEW`：章节列表 Tag；按钮「已处理」将 chapter.status 置 DONE（不重写正文）；「忽略」同 DONE 并记一条 SYSTEM 真典可选。

### D5. 作业任务化

- `POST /run`：若当前为 CONCEIVING（或长操作），启动后台任务，立即返回 `{taskId,status}`；前端轮询 `GET /writing-tasks/{taskId}`（或通用 `GET /tasks/{id}`，优先复用 WritingTask 模型并扩展 type=CONCEIVE|WRITE_CHAPTER|WRITE_ALL）。
- 前端 `api.run` 不再 300s abort 当最终失败；轮询到 DONE/FAILED。
- `POST /write-chapter` 登记 active task，返回时机：正文落库后（后处理异步，p14-03 已部分做）。
- RunConsole：write-all 显示 chaptersWritten；**取消**调用已有 cancel API。
- `maxChapters` 默认 `min(50, targetChapters - currentChapter)`。
- `DELETE /projects/{id}`：hasActiveTask → 409「请先暂停或取消写作」。

### D6. 偏好与检查点间隔

- 对话密度滑条与三个开关：`PUT /preferences` JSON，不调 LLM。
- 聊天框仍走 `/preferences/chat`。
- `WritingPreferences.checkpointSpan`：`1`（每章，默认）| `0`（仅手动暂停）| `-1`（卷末）。INTERACTIVE `doWriteOneChapter` 末尾仅当 span 命中才 PAUSED+AFTER_CHAPTER。

## 验收标准

- [ ] `POST /worldbuilding` 201/200，GET 列表多一条；真典有对应 ACTIVE 事实。`mvn test -Dtest=HitlCreateWorldCharacterTest`
- [ ] `POST /characters` 后 GET characters 多一张卡，真典 CHARACTER_STATE。同上测试。
- [ ] 有 write-all active task 时上述 POST → 409。
- [ ] `POST /expand/characters` 不修改已有 character id 的 name/ability。`mvn test -Dtest=HitlExpandAgentsTest`
- [ ] promote entity → 新 Character，图实体仍在。
- [ ] 编辑未写梗概 PUT 后 GET 可见；已写章梗概未变。`mvn test -Dtest=UnwrittenSynopsisEditTest`
- [ ] `POST /replan` 只替换 `chapterIndex > current`。
- [ ] 真典 PUT 后 assertion 变；废止后 GET active 不含该条。
- [ ] 伏笔 POST + resolve 后 status RESOLVED。
- [ ] 章节 NEEDS_MANUAL_REVIEW 经 API 清为 DONE。
- [ ] 构思：POST /run 立即返回 taskId；轮询至终态。前端不因 300s abort 显示失败（若任务仍 RUNNING）。`mvn test -Dtest=ConceiveTaskTest`
- [ ] write-all cancel 后 task CANCELLED，state 非 RUNNING。
- [ ] 有 active task 时 DELETE project → 409。
- [ ] 改密度滑条不产生 LLM_CALL 事件（或 chatPreferences 不被调用）。
- [ ] `mvn compile && mvn test` 全绿
- [ ] 不改 prompt 模板变量名；不 --bless 除非 ContextAssembler 输入形状被测证明不变

## 影响面

- **涉及模块**: web (Content/Orchestrator/Checkpoint/Project), orchestrator, service (CanonPatch, OutlineReplanner, WritingTaskExecutor, CharacterProfileUpgrader), agent (WorldBuilder/CharacterDesigner 增量入口), frontend (WorldbuildingView, OutlineView, InteractiveReview, RunConsole, ProjectWorkspace)
- **涉及 prompt 模板**: 无变量改名；增量补卡复用现有 `.st`，instruction 进已有 userMessage 槽
- **数据库 schema 变更**: `writing_preferences` 增加 `checkpoint_span INTEGER DEFAULT 1`（增量 ALTER，与现有 pragma 迁移风格一致）；`writing_tasks` 若无 type 列则加 `task_type TEXT DEFAULT 'WRITE_ALL'`
- **质量基线影响**: 无（不改写作 prompt 公式）。任务化不改变章正文生成参数

## Feature 拆分

| id | name | 依赖 | acceptance | verify_cmd |
|----|------|------|------------|------------|
| p15-01 | POST 世界观/人物 + UI 添加 + write-chapter 也 409 | [] | POST 后列表+真典增加；active 时 409 | `mvn test -Dtest=HitlCreateWorldCharacterTest` |
| p15-02 | Agent 增量补设定/人物 + 实体提升 | [p15-01] | expand 不覆盖旧卡；promote 出新卡 | `mvn test -Dtest=HitlExpandAgentsTest` |
| p15-03 | 未写梗概可编 + POST replan | [p15-01] | 已写梗概不变；未写被替换 | `mvn test -Dtest=UnwrittenSynopsisEditTest` |
| p15-04 | 真典改/废 + 伏笔人種人收 + 人审清状态 | [] | active 不含废止；resolve 后 RESOLVED | `mvn test -Dtest=HitlCanonForeshadowReviewTest` |
| p15-05 | 构思/写章任务轮询 + write-all 取消 + 删项目护栏 | [] | POST /run 立即 taskId；cancel 生效；DELETE 409 | `mvn test -Dtest=ConceiveTaskTest` |
| p15-06 | 偏好 PUT；checkpointSpan | [p15-05] | 滑条不调 LLM；span=0 写章不自动 PAUSED | `mvn test -Dtest=CheckpointSpanTest` |

## 风险与备选方案

| 风险 | 缓解 |
|---|---|
| WorldBuilder 增量仍清空旧条目 | 测试锁定源码路径无 `deleteKnowledgeEntriesByProject`；增量 prompt 只要求「追加」 |
| 构思任务化与现有同步测试冲突 | 保留 `runConceiving` 同步给单测；HTTP `/run` 走 executor |
| checkpointSpan 改默认 | 默认 1，现有 INTERACTIVE 测试不改 |
| 前端无 npm test | 契约测在后端；UI 改动最小 |

**被否决**：C（SSE、默认每卷暂停、删章）——与 Phase 13 非目标及本次确认冲突。A 只给人 CRUD——正文新角色仍进不了人设卡。
