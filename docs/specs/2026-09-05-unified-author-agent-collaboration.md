# Spec: 统一作者与 Agent 长篇协作

日期：2026-09-05。需求：Agent 自动推进长篇创作，作者随时介入内容编辑、细节审核，然后继续协作。本文取代 Phase 26 对自动持续任务的延期决定。

## 背景与动机：架构和交互审查

| 严重性 | 实证位置 | 当前行为与影响 | 本期处理 |
|---|---|---|---|
| P0 | `WritingTaskExecutor.startConceiving/executeConceive`、`OrchestratorController.run` | 构思只执行一次 step；SOLO 只是少建检查点，并不会自动进入写作。产品审计 auto-zero-intervention 仍是 XFAIL。 | 新增覆盖整个生命周期的 AUTO_RUN 任务 |
| P0 | `OrchestratorController.pause`、`NovelOrchestrator.pause/runConceiving` | 暂停直接改小说阶段，慢构思结束又写回阶段；“已暂停”不代表工作权已释放。 | 接手只请求停止，保留小说阶段；释放工作权后才展示可编辑 |
| P0 | `ContentController.rejectIfWriting` | 多个设定修改只先查忙碌，没有持有工作权；检查后可能与新任务并发。 | 内容变更完整覆盖共享工作权 |
| P1 | `ProjectWorkspace`、`ProjectListPage` | 自动/交互模式割裂同一创作；暂停/继续藏在侧栏；控制台与内容页隔离。 | 全局协作栏，审核节奏替代运行模式 |
| P1 | `RunConsole` | busy/taskId 只在当前组件内，切页/刷新丢失；批写固定 50 章且显示暂停为成功完成。 | 服务端状态作为协作栏真相；刷新重连、明确运行/停下/等待/失败/完成 |
| P1 | `WritingTask.instruction`、SQLite task mapper | 指令 transient；任务时间读取时重新生成。 | 指令与原始时间持久化，重启任务明确收敛为待恢复 |
| P1 | `CheckpointController`、`resumeFromCheckpoint` | 恢复和批准耦合；切模式也未触发续跑。审核动作和 worker 可争用状态。 | 协作继续不自动批准；审核处理持有同一工作权；处理后一个继续入口 |
| P2 | `NovelOrchestrator`（约 2700 行） | 编排同时承担生命周期、修订与质量；再复制一个生产流水线会扩大分叉。 | 复用现有写作循环和质量门禁，新增薄协作入口，不移动无关 Agent |

已有能力可以复用：章节 expectedVersion 与不可变历史、Agent 修订预览/应用、StoryCanon 人工事实保护、MemoryRevision、后处理账本、单项目 owner-token。新流程必须经过这些路径，不能绕开质量或覆盖作者内容。

## 目标

1. 从 CONCEIVING 一次启动，持续生成框架、正文、后续卷并执行终审；无审核/质量/成本阻塞时到 FINAL，不受 50 章 UI 批次限制。
2. 作者在任意内容页请求接手，当前完整构思步骤或章节及后处理安全结束后释放工作权；可修改设定、大纲、正文或让 Agent 修改，再明确继续。暂停不伪造审核点、不丢原阶段。
3. 同一个工作台显示当前工作、真实进度、审核入口、接手/继续按钮和审核节奏。刷新、切页不会失去后台任务。

## 非目标

- 不替换 24 个 Agent，不重新设计小说 Prompt，不引入聊天框代替结构化编辑。
- 不同时运行多个写入者，不在模型调用中途强行终止并提交半章；“随时介入”表示随时提出接手，安全边界后保存。
- 不在服务器重启后静默发起新的付费调用。中断记录持久化为 PAUSED，作者查看后继续；浏览器关闭不影响存活服务器上的任务。
- 不自动改写所有受历史修改影响的已写章节。沿用失效、真典与后续质量审查，既有正文修改由作者明确发起。

## 设计

### 两个正交状态

小说阶段仍使用 NovelState。工作任务使用 WritingTask 的 RUNNING/PAUSED/COMPLETED/FAILED/CANCELLED；AUTO_RUN 表示跨阶段协作任务。停止请求持久化，RUNNING 且停止请求为 true 时为界面“正在停下”。只有没有项目 owner 才允许内容保存。任务 COMPLETED 必须对应 FINAL；待审核、待修订、预算或质量中断都不算完成。

### 生命周期

`开始/继续 → 获取项目工作权 → 检查待审核和终态 → 构思/规划 → 既有 runWritingLoop → FINAL 或明确阻塞 → 释放工作权`。

runWritingLoop 保留连续失败重试、质量熔断、人工复核、分层卷扩展和最终门禁。不得逐章另起循环，否则连续质量计数会清零。构思/规划步骤前后均检查停止请求和预算。继续加载当前仓储内容，绝不回放旧稿快照。人审后显式继续，与一般内容编辑保持一致。

### 审核节奏与兼容

界面提供“仅主动接手 / 每章审阅 / 卷末审阅”。内部兼容旧 SOLO/INTERACTIVE 和 checkpointSpan，后者仍决定章末/卷末检查点；改变偏好不批准已有审核项。INTERACTIVE 存量项目仍在初始框架和卷规划处审核。旧低层 API 保留用于既有脚本，协作栏使用新入口。

### 接手与编辑

请求停止仅操作任务，不改项目阶段。可先阅读内容；正在停下期间保存仍由服务器拒绝。工作权释放后，作者使用原编辑器保存并更新记忆。所有人工设定变更与审核处理都需在读校验到提交期间持有同一个 ProjectWorkCoordinator owner；章节/大纲已经有 owner 的路径不可重复加锁。

### 状态接口

- `GET /api/projects/{id}/collaboration`：小说阶段、最新任务、busy、stopRequested、pendingReviews、canContinue、blockedReason、reviewPolicy、currentChapter、totalWords。
- `POST /collaboration/start`：可选 instruction；null 沿用最近协作指令，空串明确清除；返回同项目正在运行的协作任务或新任务。待审核不自动批准。
- `POST /collaboration/stop`：幂等请求停止，返回真实状态。
- `PUT /collaboration/review-policy`：MANUAL/CHAPTER/VOLUME，空闲时修改，保留待审核项。

## 验收标准

- [x] 持久化契约：指令、停止请求和任务时间经过 SQLite 重建一致；旧数据库自动加列。`mvn test -Dtest=CollaborationTaskPersistenceTest`
- [x] 执行契约：一次启动经过构思和规划到 FINAL；重复启动同任务；接手期间 owner 不释放，完成后 PAUSED 且阶段保留；待审核/人审/预算不绕过；重启 PAUSED；无进展停止。`mvn test -Dtest=CollaborativeRunTest`
- [x] 接口契约：读取、启动、接手、审核节奏、人工变更互斥。`mvn test -Dtest=CollaborationApiTest,CollaborationEditOwnershipTest`
- [x] 浏览器：默认工作台启动、切至内容页仍可接手、刷新状态、审核后继续、三章 FINAL。`./scripts/collaboration-e2e.sh`
- [x] 全量：`mvn compile && mvn test`、`cd frontend && npm run build`、匹配 mystery fixture 的 `./scripts/e2e.sh` 和 `python3 scripts/compare-baseline.py`。

## 影响面

- **涉及模块**：WritingTask / SQLite task 存储、WritingTaskExecutor、协作控制器、内容/审核写入边界、React 工作台/列表/协作栏、E2E。
- **涉及 prompt 模板**：无。复用已有作者指令注入路径。
- **数据库 schema 变更**：writing_tasks 增 instruction、stop_requested；旧记录默认 null/false。
- **质量基线影响**：编排入口变更需要 replay 和质量对比；不更新 golden。

## Feature 拆分

每项 1–3 个业务/测试文件，状态登记文档不计入实现面；按顺序完成。

| id | name | 依赖 | acceptance | verify_cmd |
|---|---|---|---|---|
| p27-01 | 协作任务领域及持久化 | [] | 新字段与旧 schema 兼容 | `mvn compile` |
| p27-02 | 持久化回归与持续生命周期任务 | [p27-01] | SQLite 契约、跨阶段推进、安全停止测试通过 | `mvn test -Dtest=CollaborationTaskPersistenceTest,CollaborativeRunTest` |
| p27-03 | 统一协作接口与审核偏好 | [p27-02] | MVC 契约通过 | `mvn test -Dtest=CollaborationApiTest` |
| p27-04 | 人工修改与审核工作权闭环 | [p27-03] | 并发回归及既有编辑测试通过 | `mvn test -Dtest=CollaborationEditOwnershipTest,WorldCharacterFullEditTest,CheckpointTargetValidationTest` |
| p27-05 | 全局协作栏与 API 接线 | [p27-03] | 前端构建成功 | `cd frontend && npm run build` |
| p27-06 | 工作台与新建统一协作语义 | [p27-05] | 旧模式文案移除，前端构建与文案门禁通过 | `cd frontend && npm run build` |
| p27-07 | 协作浏览器闭环与发布验证 | [p27-04,p27-06] | 三章 FINAL、接手编辑继续及完整门禁通过 | `./scripts/collaboration-e2e.sh` |

## 风险与备选方案

仅改模式名称无法修复后台推进；建立另一套聊天 Agent 调度会重复现有可靠性机制。选用现有执行器、仓储和 owner，以新增任务类型集中生命周期控制。风险是安全停下耗时可能达到一次模型步骤或整章后处理；界面必须诚实显示。恢复不会自动批准审核或跳过质量失败。旧接口作为兼容入口保留，本次新交互不依赖它们的历史暂停语义。

## 实施审查补充

- p27-08（依赖 p27-06）：ProjectWorkspace 的章节 key 包含 chapterIndex，后台每章完成会销毁编辑组件。改为稳定项目 key，通过 refreshSignal 刷新已保存内容；草稿/修订弹窗打开时延后刷新。章节页的继续动作统一委托协作入口。验证 frontend build + 协作浏览器草稿保持。
- p27-09（依赖 p27-06）：doWriteOneChapter 幂等条件仅依据模型偏好的 minChapterLength，人工 500–1999 字完成章可能被覆盖。完成章满足人工门槛就保留；未完成的提前草稿阻塞续写，作者完成后再继续。先 failing test 后修复，验证 `mvn test -Dtest=AuthorChapterPreservationTest`。p27-07 依赖这两项。
- p27-10（依赖 p27-06/p27-08）：旧任务枚举契约和旧 browser-e2e 默认页前提升级为协作语义；显式进入单步工具检查既有 SSE 与懒加载，终态以全局协作栏为准，保留全部八项行为断言。验收 `mvn test -Dtest=WritingTaskTest` 与 `./scripts/browser-e2e.sh --port 18095`。
- p27-11（依赖 p27-03/p27-05）：同步人工保存只有工作权没有任务记录；协作状态不得投影旧任务的 stopRequested。先用 MVC 测试复现，再将当前无任务工作返回 null task/false stopRequested，并显示“正在处理内容”。验收 `mvn test -Dtest=CollaborationApiTest` + frontend build。
- p27-12（依赖 p27-02）：补齐正常服务关闭的恢复契约。shutdownNow 中断活动步骤后不能记录为业务失败；保留 PAUSED、小说阶段与创作指令，阻止再进入下一步，作者重启后明确继续。先用等待中的步骤复现，再修改 WritingTaskExecutor；验证 `mvn test -Dtest=CollaborativeRunTest`。

### p27-07 浏览器验收补全（同日用户复验要求）

加强 p27-07 的交互验收，并以 p27-12 补齐原有服务器恢复契约；不增加业务范围或改动架构。

| 路径 | 必须断言的结果 |
|---|---|
| 开始、切内容页、刷新 | 全局协作栏常驻，一次启动完成三章与终审，任务 COMPLETED |
| 框架审核、人工精修、继续 | 世界观修改保留；后台刷新不销毁正在编辑的章节草稿；继续到 FINAL |
| 写作期间接手、编辑、再次继续 | 安全边界后 PAUSED、阶段保留、章节停止增长；继续后新增章节且既有正文和作者设定不被覆盖 |
| 每章审核与创作要求 | 在第 1、2 章分别等待；刷新和继续保留要求，主动留空后持久清除；切换节奏不批准原审核项 |
| 卷末审核 | 前两章继续推进，第 3 章卷末等待明确批准，批准并继续后才完成终审 |
| 移动端 | 375px 无横向溢出，全局协作控件存在 |

以上路径使用 ego-browser 执行 UI 动作并读回服务端持久结果；草稿保持场景特意通过外部审核动作触发后台刷新。产品套件另外覆盖世界观、人物、大纲、正文编辑、版本冲突、历史恢复、Agent 改写和审核卡定位；旧浏览器套件覆盖键盘操作、终态、质量阻塞、SSE 与懒加载。运行采用隔离 Scenario 数据；真实模型生成质量由已有 replay/baseline 验证，不把测试桩文学内容当作模型效果评测。

## 最终验收（2026-09-05）

Phase 27 共 12 项全部 completed，包含同日复验补齐的服务正常关闭恢复契约（p27-12）。业务实现沿用本方案的执行器、编排器、持久层与现有编辑入口。

- `./init.sh`：615 tests / 0 failures / 0 errors / 1 skipped；编译、28 prompts、frontend build PASS。
- ego-browser：协作 6/6、产品 11/11（0 XFAIL / 0 ERROR）、旧界面 8/8，共 25/25 PASS。前述交互矩阵已覆盖。
- 关闭恢复：先用 11 tests / 2 failures 复现，再修复为 11/11 PASS；异常中断与 Agent 吞中断均不会错误完成或继续下一步。
- mystery replay：3 章 / 10,191 字 / 54 次回放调用 / 0 LLM errors / 0 chapter failures；baseline PASS，未更新 golden。此回放验证原批写路径质量，AUTO_RUN 到 FINAL 由浏览器验证。
- `user-copy-check.sh`、`git diff --check` PASS；feature_list.json、progress.md、session-handoff.md 已同步。

最终证据：`data/verification/phase27/final/`（init.log、shutdown-red/green.log、collaboration/latest-results.json、product/summary.json、legacy.log、replay-report.json、quality.log）。UI 验收使用隔离 Scenario 服务及页面快照，未调用真实模型；本次测试服务与新建协作浏览器空间已清理。代码在当前工作区，未重启既有写作服务。
