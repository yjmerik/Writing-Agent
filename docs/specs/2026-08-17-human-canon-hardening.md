# Spec: 人机真典硬化（Human Canon Hardening）

> 文件名约定：`docs/specs/YYYY-MM-DD-<slug>.md`。新需求必须先落 spec，再拆 feature。
> 本 spec 为决策完备文档。范围锁定为 **B 硬化波**：修 QA 阻断 bug，并把 Phase 13 已承诺、UI/作业层未闭环的人机面补完。
> 上游 spec：`docs/specs/2026-08-16-million-word-human-canon.md`（Phase 13）。本波不重开其锁定决策。

## 背景与动机

Phase 13 已落地 CanonPatch、暂停/续写、真典按章召回、OutlineReplanner、Arbiter 主循环、人审 DTO、篇幅选择、设定可编辑 API。2026-08-17 浏览器全量实测证明：**后端骨架在，作业与人机闭环仍断**。

实测项目：债册春秋（INTERACTIVE，2 章 / 6489 字）+ QA一次性项目（CONCEIVING，删不掉）。

| 实测失败 | 代码事实 | 对百万字/人机目标的伤害 |
|---|---|---|
| 删除项目 HTTP 500 | `SqliteNovelRepository.deleteProject` 对 `projects` 执行 `WHERE project_id=`，该表主键是 `id`；`outlines`/`volumes`/`chapter_synopses`/`story_facts`/`writing_tasks` 等不在级联列表 | 无法清理失败项目，库膨胀 |
| QA「推进」构思失败 | `doConceiving` 题材推断后调用 `worldBuilder.build`；异常 `getMessage()==null` → `GlobalExceptionHandler` 返回「内部错误」；世界观/人物仍 0 | 新项目无法进入可写状态 |
| 「连续写作」契约裂 | 后端 `POST /write-all` 立即返回 `{taskId,status,totalChapters}`；前端仍当同步结果读 `chaptersWritten`/`totalWords`，超时 1h | 长篇连写不可用 |
| 段落改写 UI 误报失败 | `editPassage` 先落库再跑抽取/收割；前端 `editPassage` 超时 120s。实测正文已改（2800→2817，v3） | 辅助改写看起来坏了 |
| 「写一章」看似不暂停 | INTERACTIVE 在 `doWriteOneChapter` **全部后处理结束后**才挂 `AFTER_CHAPTER`；后处理可达数分钟；用户中途点暂停与 writer 抢状态 | 交互审核窗口不可见 |
| 批准落到 PLANNING | UI `checkpoints[last]`；列表 ASC。两个 PENDING 时只批最新，旧卷大纲检查点残留；`resume` 取 latest | 人审状态机乱跳 |
| 人物只能改能力 | `PUT /characters/{id}` 已接受 personality/ability/motivation/appearance/relationships/growthArc；UI 只绑 ability | 「改人物后续必遵守」只覆盖能力 |
| 真典无人入口 | `GET/POST/PUT /facts` 已有；前端无入口 | 人无法直接补铁律 |
| 侧栏「新建」只回列表 | `navigate('/')`，不开创建 Modal | 工作流中断 |
| `CULTURE` 显示原文 | `TYPE_LABELS` 缺项 | 设定面不可读 |

## 目标

1. **作业可创建、可删除、可构思成功或 fail-loud**：删除不再 500；构思要么产出世界观/人物/大纲，要么返回带原因的错误。
2. **长作业可观测、可停**：write-all 前端轮询 `taskId`；写一章/改段落先返回已保存正文，后处理异步；暂停取消活跃任务且不与 in-flight writer 双写状态。
3. **人可改所有已有设定面，后续未写章必读到补丁**：人物全字段、真典列表增改、世界观类型中文、检查点按 id 批准；一律走现有 `CanonPatchService`。

## 非目标

沿用 Phase 13，禁止本波重开：

- 不改正文回溯重写（已写章不因设定变更自动连锁重写）
- 不加新检测器 / 新 Agent
- 不做完整 SSE（继续轮询）
- 不把默认 `targetChapters` 改回 400
- 不重写 `NovelOrchestrator` 为框架
- 不做卷梗概树全量可视化编辑器（未写章梗概若已有 PUT 则只补最小 UI；没有则本波只保证 premise）
- 不改 `.st` 模板变量名

## 设计决策（锁定）

### D1. 删除级联

`deleteProject(id)` 必须：

1. 在**一个 SQLite 事务**里删除该项目所有行。
2. `projects` 用 `WHERE id=?`。
3. 其余表凡有 `project_id` 列一律按 `project_id` 删。至少覆盖：
   `knowledge_entries, characters, chapters, summaries, foreshadowings, style_profiles, continuity_issues, checkpoints, entities, entity_edges, evaluation_records, outlines, volumes, chapter_synopses, story_facts, writing_tasks, writing_preferences, chapter_reviews, generation_logs, narrative_threads`（以 `SqliteSchemaInitializer` 实际建表为准，实现时对照全表清单，禁止漏表）。
4. 向量：`vectorStore.deleteByPrefix(projectId)` 或现有按项目清索引 API；没有则在本 feature 补 `deleteByProject`。
5. 禁止字符串拼接 SQL；全部 `PreparedStatement`。

### D2. 构思 fail-loud

- `step()` / `runConceiving` 捕获的异常必须 `log.error(..., e)` 并抛出带非空 message 的异常（包装 NPE/SQLException：`e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage())`）。
- `GlobalExceptionHandler`：`message == null` 时返回 `内部错误: <exceptionSimpleName>`，不再只回「内部错误」。
- 构思成功判据（全部满足才允许离开 CONCEIVING）：至少 1 条 worldview、至少 1 个角色、outline 非空。任一缺失 → 保持 CONCEIVING，返回 500/422，body 写明缺哪一步。
- 幂等：已有 genre 则跳过推断；已有世界观则跳过 WorldBuilder（现有逻辑保留）。失败重试不得留下半截不可解释状态而不报错。

### D3. 长作业：先保存，后处理异步

拆两段，禁止把抽取/收割算进「正文已完成」的 HTTP 等待：

**写一章 / 改段落 / 整章重写**

1. LLM 生成 + 落库 + `version++` + 失效摘要/向量前缀 → **立即返回** Chapter（`status=WRITTEN` 或现有非 DONE 状态）。
2. 后处理（entity extract、foreshadow、fact harvest、consistency 等）投入现有 `WritingTaskExecutor` 或同类单项目串行队列，完成后置 `DONE`；INTERACTIVE 在**正文落库后立即** `PAUSED_AT_CHECKPOINT` + `AFTER_CHAPTER`，不等后处理。
3. 前端：写一章/改段落超时 ≥ 180s 仅覆盖生成段；返回后刷新正文；后处理用轻量轮询 `GET /status` 或 chapter.status，不阻塞「已改完」toast。
4. `POST /pause`：`cancelByProject` + 置 PAUSED；若该项目有 in-flight 写章，取消后处理队列，**不回滚已保存正文**。

**连续写作**

- 后端保持异步 `{taskId,status,totalChapters,message}`。
- 前端 `writeAll`：POST 后轮询 `GET /writing-tasks/{taskId}`，直到 DONE/CANCELLED/FAILED；展示 `chaptersWritten/currentChapter`。
- 按钮文案改为「连续写作（最多 N 章）」；N 默认取 `min(50, targetChapters - currentChapter)`，禁止写「写完全书」除非 N 覆盖剩余全部。
- 进行中显示暂停；暂停走现有 `POST /pause`。

### D4. 检查点按 id 批准

- `InteractiveReview` 列出**全部** PENDING，每条自带批准/拒绝/修改；禁止只操作 `checkpoints[last]`。
- `resume` / 侧栏「续写」继续用 `findLatestPendingCheckpoint`（最新）。
- 批准某一条只改那一条的 status；若仍有 PENDING，项目保持 `PAUSED_AT_CHECKPOINT`。
- 全部 PENDING 清空后：`AFTER_CHAPTER`/`ON_CONTINUITY_FIX` → WRITING；`AFTER_INITIAL_SETUP`/`AFTER_VOLUME_OUTLINE` → PLANNING。

### D5. 人机面补齐（只接线，不新 API）

| 面 | 行为 |
|---|---|
| 人物卡 | personality / ability / motivation / appearance / relationships / growthArc 均可编辑；blur 或保存走现有 PUT；CanonPatch 映射不变 |
| 真典 | 新 Tab 或世界观页底部：列表 ACTIVE facts；新增 assertion+type；编辑 assertion。走 GET/POST/PUT `/facts` |
| 世界观类型 | `TYPE_LABELS` 补 `CULTURE=文化`，未知 type 显示 `type` 原文 + 灰色 tag |
| 侧栏新建 | 打开与列表页相同的创建 Modal（抽共享组件或把 `createOpen` 提到路由 state `?new=1`） |
| 大纲 | 保持 premise 可编辑；未写章梗概若本波时间不够可不动（非本波 P0） |

### D6. 测试策略

Bugfix gate：每个 bug 先写复现失败测试，再改实现。

- `SqliteNovelRepositoryDeleteTest`：建项目+outline+world+fact，delete，assert 各表 0 行且不抛。
- `GlobalExceptionHandlerTest`：null message → body 含异常类名。
- `ConceivingGateTest`：mock WorldBuilder 抛 NPE / 返回空，assert 仍 CONCEIVING 且异常非空。
- `WriteAllContract`：前端或 API 集成测试：POST write-all 响应含 `taskId`，不含同步 `chaptersWritten` 作为完成标志。
- `EditPassageReturnsBeforeHarvestTest`：harvest 阻塞，editPassage 仍在超时内返回且 content 已变。
- `InteractivePauseAfterPersistTest`：后处理阻塞时，chapter 已在且 state=PAUSED、存在 AFTER_CHAPTER。
- `ApproveSpecificCheckpointTest`：两条 PENDING，批准较新的，旧的仍 PENDING，state 仍 PAUSED。
- 前端：人物全字段保存、facts CRUD、CULTURE 标签、新建打开 Modal——能单测的用 React/RTL；否则 `verify_cmd` 用 API 测后端，UI 在 feature evidence 记手动步骤。

## 验收标准

- [ ] `mvn test -Dtest=SqliteNovelRepositoryDeleteTest` 通过；删除 QA 项目不再 500，`GET /projects` 不再出现该 id
- [ ] 对空构思项目 `POST /run`：成功则 worldview≥1 且 characters≥1 且 outline 非空；失败则 HTTP 4xx/5xx 且 `error` 非「内部错误」光杆字符串
- [ ] `POST /write-all` 响应 JSON 含 `taskId`；前端轮询直到终态，不再把首次响应当「写完」
- [ ] 段落改写：保存后 5s 内 UI 成功 toast；正文含改写指令对应内容；后处理失败不回滚正文
- [ ] INTERACTIVE `POST /write-chapter` 返回时 `state=PAUSED_AT_CHECKPOINT` 且存在该章 `AFTER_CHAPTER`（即使后处理未完）
- [ ] 两条 PENDING 时批准指定 id，另一条仍 PENDING
- [ ] `PUT /characters/{id}` 改 personality 后 GET 可见；真典出现 `【HUMAN_PATCH】` 或等价 ACTIVE CHARACTER_STATE
- [ ] `GET /facts` 在 UI 有入口；POST 一条后列表可见
- [ ] `mvn compile && mvn test` 全绿
- [ ] 不改 prompt 模板变量名；不跑 e2e bless（本波不改写作质量路径的 prompt/组装公式，除非 editPassage 拆异步被测证明不改输出）

## 影响面

- **涉及模块**: persistence/sqlite, orchestrator, web (exception + write-all 客户端), service (CanonPatch 仅被 UI 更多字段调用), frontend pages/components
- **涉及 prompt 模板**: 无
- **数据库 schema 变更**: 无新表；删除路径覆盖已有表
- **质量基线影响**: 无（不改写作 prompt / ContextAssembler 公式）。若拆异步导致章节 status 枚举变化，只更新编排测试，不 --bless

## Feature 拆分

| id | name | 依赖 | acceptance | verify_cmd |
|----|------|------|------------|------------|
| p14-01 | deleteProject 按表正确级联 | [] | 建含 outline/facts 的项目后 DELETE 200，各表 0 行 | `mvn test -Dtest=SqliteNovelRepositoryDeleteTest` |
| p14-02 | 异常 fail-loud + 构思完整性门闩 | [] | NPE 返回含类名；空世界观不离开 CONCEIVING | `mvn test -Dtest=ConceivingGateTest,GlobalExceptionHandlerTest` |
| p14-03 | 写章/改段落先返回后处理异步 + INTERACTIVE 立即暂停 | [p14-02] | 阻塞 harvest 时仍返回且 PAUSED | `mvn test -Dtest=EditPassageReturnsBeforeHarvestTest,InteractivePauseAfterPersistTest` |
| p14-04 | 前端对接异步 write-all + 文案 | [] | client 轮询 taskId；无「写完全书」除非 N=剩余 | `mvn test -Dtest=WritingTaskExecutorTest` + 前端 `npm test`（若无则 API 契约测） |
| p14-05 | 检查点按 id 批准、列出全部 PENDING | [] | 批准新的，旧的仍 PENDING | `mvn test -Dtest=ApproveSpecificCheckpointTest` |
| p14-06 | 人物全字段 + 真典 UI + CULTURE + 新建 Modal | [p14-01] | PUT personality 写真典；facts 可增改 | `mvn test -Dtest=CanonPatchServiceTest` |

## 风险与备选方案

| 风险 | 缓解 |
|---|---|
| 构思 500 根因未在 QA 中打出栈 | p14-02 先 fail-loud；再用复现测试钉死 WorldBuilder 空/NPE |
| 拆异步后 status 语义变化导致旧测试失败 | 只改编排测试；正文落库契约保持 |
| 暂停与 in-flight 写章竞态 | pause 只 cancel 后处理、不删已写章；单项目串行队列 |
| 前端无现成 npm test | 契约测放后端；UI 改动保持最小 |

**被否决**：C 新产品面（卷树编辑器、SSE、新检测器）——与 Phase 13 非目标冲突。A 只修 FAIL——人物/真典/检查点仍无法支撑「过程人工介入」。
