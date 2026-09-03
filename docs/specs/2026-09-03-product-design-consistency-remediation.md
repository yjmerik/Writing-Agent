# Spec: 产品设计一致性审计问题闭环修复

> 来源：`docs/audits/2026-09-02-product-design-consistency-audit.md`。  
> 决策日期：2026-09-03。用户明确将自动任务架构排除在本期范围；P1-01 与依赖自动任务续跑的 P0-03 后续另立架构专项。

## 背景与动机

产品审计确认 16 项功能、状态语义和交互问题：3 项 P0、8 项 P1、5 项 P2。当前 `scripts/product-audit-e2e.sh` 已将 9 条关键路径固化为 0 PASS / 9 XFAIL / 0 ERROR 的红灯基线。本期修复其中 14 项；P1-01“自动模式持续运行”和 P0-03“交互切自动后自动续跑”涉及后台任务生命周期、重启恢复和单 worker 协调，按用户决定不在本期实施。

本轮以“操作必须真实生效、内容必须可控、界面必须可信”为业务目标。自动任务相关模式语义留给后续架构专项；本期不以界面文案伪装其已完成。实施严格限制在审计问题范围内，不顺带重构 NovelOrchestrator、替换 UI 技术栈或调整文学质量策略。

## 目标

1. 修复审计报告中除 P1-01、P0-03 外的 14 项问题，并让每项行为有机器可验证的回归证据。
2. 世界观、人物、大纲和章节均可人工新增与修改，并在各层级提供带自然语言指令、预览和确认应用的 Agent 修改入口。
3. 页面按钮、进度、审核对象、质量状态、移动布局和无障碍语义与真实业务状态一致。

## 非目标

- 不改变现有 7 个题材预设、27 个生产 Prompt 的文学风格目标或质量阈值。
- 不修改 StoryCanon 的 HUMAN_PATCH 保护窗口、ACTIVE 唯一性和 supersede 审计语义。
- 不引入新的前端框架、数据库产品、任务队列或外部服务。
- 不处理审计报告之外的 bundle 分包、Orchestrator 全面拆分、质量基线评分启用等观察项。
- 不实现 P1-01 自动模式端到端持续运行、持久化自动任务、应用重启恢复或自动暂停/恢复。
- 不实现 P0-03 交互切自动后自动批准 checkpoint 并继续运行；`interactive-to-auto` 场景本期保留为显式已知失败。
- 不把“浏览器能点击”当作完成；每项必须同时验证持久化结果与页面状态。

## 已批准的产品决策

### 本期自动任务边界

- 保留现有自动/交互模式及手动推进语义，不新增后台持续调度器或 `AUTO_RUN` 任务类型。
- 本期仍修复状态—操作矩阵和真实反馈，使页面不承诺尚未实现的“全自动生成，无需干预”。
- `auto-zero-intervention` 与 `interactive-to-auto` 两个浏览器场景继续列入 `KNOWN_FAILURES`，最终验收必须证明已知失败集合精确收敛为这两项且无其他 XFAIL/ERROR。
- 后续架构专项应重新评估持久化任务、重启恢复、ProjectWorkCoordinator 所有权和模式切换事务边界；本 spec 不预设其实现。

### 审核与改写

- 所有批准、拒绝、修改操作都显式携带并校验 `projectId + checkpointId`，不得回退到“最新 checkpoint”。
- `AFTER_INITIAL_SETUP`、`AFTER_VOLUME_OUTLINE`、`AFTER_CHAPTER` 分别路由到对应的设定、卷大纲、章节修订路径。
- 只有新结果生成并保存成功后才能把 checkpoint 标为 `MODIFIED`、`REJECTED` 或 `APPROVED`；失败时原 checkpoint 保持待处理。
- API 返回修改对象、修改前摘要、修改后摘要和版本信息；页面在成功反馈中展示结果，不使用虚假成功文案。
- 独立章节重写不依赖 checkpoint，使用独立目标状态和提交函数。

### 人工内容管理与版本

- 世界观允许编辑类型、名称、正文；类型使用中文选项映射现有枚举并由服务端校验。
- 人物允许编辑姓名、角色定位及现有描述字段；修改继续通过 CanonPatchService 记录 HUMAN_PATCH。
- 大纲只允许对未写部分新增、删除、重排；已写章节的索引和所属结构不可被覆盖。服务端校验章号唯一、递增且与已写边界兼容。
- 章节支持人工新增草稿、直接编辑标题和正文、保存、查看版本、比较并回退。所有正文变更统一产生不可变版本快照，并触发现有摘要失效、向量重建和后处理更新。
- Agent 修改统一采用“选择对象 → 输入指令 → 生成预览 → 用户确认应用”；预览不直接写库，应用时校验对象版本，避免覆盖并发人工修改。

### 可信界面

- 服务端状态响应提供允许动作和暂停原因，前端只展示当前有效的主操作；无大纲或非写作状态不开放“写一章”。
- 暂停进度由 checkpoint 类型决定；章节审核显示“写作完成 → 等待章节审核”。
- 项目列表写作阶段按 `currentChapter / targetChapters` 计算并显示数字；构思和规划仅使用明确的里程碑分段。
- 质量评价区分 `NOT_EVALUATED`、`RUNNING`、`SUCCEEDED`、`FAILED`；缺失结果不得显示为 0 分，错误统一使用站内通知并支持重试。
- 移动端标题与操作分行；侧栏控件使用可聚焦 Button；段落编辑有显式入口和可访问名称。
- 统一“章节管理”“种下伏笔”“回收”等用户术语，并用自动检查阻止已知错字回归。

## 架构与数据流

### 1. Checkpoint 修订入口

`CheckpointController` 只负责参数和响应；`NovelOrchestrator` 作为唯一 Agent 调度者新增按 checkpoint 类型修订的方法。修订结果使用小结果对象携带目标类型、前后摘要和版本。控制器先验证 checkpoint 属于当前项目且仍为 `PENDING`，再调度修订，最后改变 checkpoint 状态。

### 2. 内容版本与预览

SQLite 新增 `chapter_versions` 表，保存变更前快照、来源和时间；`NovelRepository` 暴露查询和恢复所需的显式方法。章节重写、段落编辑、直接编辑和回退都经过同一章节修订边界。

通用 Agent 预览由新的辅助职责类通过外置模板生成结构化候选结果。预览响应携带源对象版本；确认应用复用 CanonPatchService、Outline 结构校验或章节修订服务，不绕过现有业务约束。

### 3. 前端状态收敛

章节元数据刷新不再无条件清空正文缓存；审批后显式保留或重取当前选中章节。异步按钮分别维护自身 loading/error 状态。审核弹窗保存明确的 checkpoint 或 chapter 目标，不能读取易变化的“当前对象”。

## 错误处理与并发约束

- checkpoint 不属于项目、已处理或目标版本变化时返回冲突错误，不修改任何状态。
- Agent 返回空内容、结构错误或持久化失败时保留原内容与待审核状态，并提供可重试错误。
- 人工编辑和 Agent 应用在存在活动写作任务时返回冲突；用户先暂停后再修改。
- 大纲和章节结构校验在写库前完成，失败不产生部分更新。
- 章节版本回退本身也生成新版本，不删除历史记录。

## 验收标准

- [ ] `./scripts/product-audit-e2e.sh --expect-known-failures` 最终仅有 `auto-zero-intervention` 与 `interactive-to-auto` 两项 XFAIL，其余场景全部 PASS，且 0 ERROR。
- [ ] 初始设定和卷大纲带意见修订后内容/版本变化，返回前后摘要，失败不处理 checkpoint。
- [ ] 无待审核项时独立重写章节，版本递增、正文变化、弹窗关闭且正文持续可见。
- [ ] 构造两个待审核项后，每张卡的批准、拒绝和修改请求都命中所点 ID。
- [ ] 世界观、人物、大纲和章节的新增/完整编辑刷新后保持一致；章节版本可比较和回退。
- [ ] 世界观、人物、卷、章节和段落均可生成 Agent 修改预览，未确认前不写库，确认后只修改目标对象。
- [ ] 每种 NovelState 的主操作、禁用原因、暂停阶段和列表进度均符合状态—操作矩阵。
- [ ] 质量评价四种状态不矛盾，失败使用统一通知并可重试。
- [ ] 375px 页面无标题挤压或控件遮挡；侧栏和段落操作可通过键盘执行并具备可访问名称。
- [ ] 用户文案检查无“人種”“人收”“章节阅读”等已知错误术语。
- [ ] 每个 feature 完成时执行其 `verify_cmd`、`mvn compile && mvn test`、`./init.sh` 和至少一个对应的真实浏览器 E2E 场景，更新 evidence 后单独提交，再进入下一个 feature。
- [ ] 最终执行 `./scripts/e2e.sh`、`python3 scripts/compare-baseline.py`、`./scripts/product-audit-e2e.sh --expect-known-failures` 和 `./init.sh`；常规门禁全绿，产品审计套件严格匹配两项延期场景且无 ERROR。

## 影响面

- **涉及模块**: `orchestrator/`、`service/`、`web/`、`domain/`、`spi/`、SQLite 适配器、React 页面与组件、浏览器 E2E harness。
- **涉及 prompt 模板**: 新增通用内容修订模板；不改变既有模板变量名。新增模板须通过对应 Agent 测试并执行质量 E2E/基线对比。
- **数据库 schema 变更**: 新增 `chapter_versions`，使用 `CREATE TABLE IF NOT EXISTS` 向前迁移；不删除或改写现有章节数据。
- **质量基线影响**: 业务产出 Prompt 不变；通用修订模板只在人工触发时使用。仍按项目规则执行 replay E2E 与 `compare-baseline.py`，不自动 bless。

## Feature 拆分

> 每个 feature 只进入一个实现与审核循环；依赖未完成前不得开始后续项。状态台账和证据文件属于 Definition of Done 记录，不扩大业务实现范围。

| id | name | 审计关联 | 依赖 | acceptance | verify_cmd |
|----|------|----------|------|------------|------------|
| p26-01 | Checkpoint 目标归属与状态校验 | P1-07 | [] | 所有操作校验 projectId、checkpointId、PENDING；错误对象返回冲突且不改状态 | `mvn test -Dtest=CheckpointTargetValidationTest` |
| p26-02 | 初始设定与卷大纲审核改写真正路由 | P0-01 | [p26-01] | 两类指令均产生内容/版本变化和前后摘要；失败保留 PENDING | `mvn test -Dtest=CheckpointRevisionRoutingTest` |
| p26-03 | 审核卡操作绑定与确认摘要 | P1-07,P1-08 | [p26-01,p26-02] | 多卡批准/拒绝/修改命中自身 ID；卡片展示对象摘要与跳转 | `./scripts/product-audit-e2e.sh --case checkpoint-card-targeting` |
| p26-04 | 独立章节重写接线与反馈 | P0-02 | [] | 无 checkpoint 时版本递增、正文变化、弹窗关闭并显示结果 | `./scripts/product-audit-e2e.sh --case standalone-chapter-rewrite` |
| p26-05 | 审批后章节正文加载收敛 | P1-03 | [] | 批准、刷新元数据后当前正文仍可见且 loading 结束 | `./scripts/product-audit-e2e.sh --case approve-keeps-chapter-visible` |
| p26-06 | 状态—操作矩阵与服务端门禁 | P1-02 | [] | 每种状态返回允许动作；无大纲/非写作态拒绝且页面不开放写章 | `mvn test -Dtest=ProjectActionPolicyTest` |
| p26-07 | 初始设定审核内容摘要 | P1-08 | [p26-02] | checkpoint payload 含世界观、人物、原旨/大纲摘要，页面可跳转对应内容 | `mvn test -Dtest=InitialSetupCheckpointPayloadTest` |
| p26-08 | 世界观与人物完整字段编辑 | P1-04 | [] | 类型中文下拉且校验；名称、角色定位和描述字段可修改并持久化 | `mvn test -Dtest=WorldCharacterFullEditTest` |
| p26-09 | 未写大纲结构校验与变更 API | P1-05 | [] | 可增删卷章和重排未写内容；已写边界、章号唯一递增由服务端保护 | `mvn test -Dtest=OutlineStructureEditTest` |
| p26-10 | 大纲人工结构编辑界面 | P1-05 | [p26-09] | 页面提供新增、删除、排序、保存和校验反馈，刷新后结构一致 | `./scripts/product-audit-e2e.sh --case manual-content-management` |
| p26-11 | 章节不可变版本历史存储 | P1-05 | [] | 每次正文变更保留旧快照；历史可查询；现有库无损迁移 | `mvn test -Dtest=ChapterVersionHistoryTest` |
| p26-12 | 章节新增、直接编辑与回退 API | P1-05 | [p26-11] | 可新增草稿、编辑标题/正文、查询/回退版本；派生产物正确失效 | `mvn test -Dtest=ChapterDirectEditTest` |
| p26-13 | 章节正文编辑与版本界面 | P1-05 | [p26-12] | 页面可保存正文、查看差异、回退和新增章节，刷新后一致 | `./scripts/product-audit-e2e.sh --case manual-content-management` |
| p26-14 | 通用内容修订 Agent 与外置 Prompt | P1-06 | [] | 对五类目标生成结构化候选，不写库；空/非法响应 fail loud | `mvn test -Dtest=ContentRevisionAgentTest` |
| p26-15 | Agent 修订预览与乐观锁应用 API | P1-06 | [p26-08,p26-09,p26-12,p26-14] | preview 不写库；apply 校验源版本并只修改目标对象 | `mvn test -Dtest=AgentRevisionApiTest` |
| p26-16 | 各层级统一“让 Agent 修改”界面 | P1-06 | [p26-15] | 世界观、人物、卷、章节、段落均可输入意见、预览差异、确认应用 | `./scripts/product-audit-e2e.sh --case manual-content-management` |
| p26-17 | 暂停阶段与真实项目进度 | P2-01,P2-04 | [p26-06] | 暂停步骤由 checkpoint 映射；写作进度按已写/目标章计算并显示数字 | `mvn test -Dtest=ProjectProgressViewTest` |
| p26-18 | 质量评价四态与统一错误反馈 | P2-05 | [] | 未评估/失败不显示 0 分或未发现问题；成功 0 分仍可区分；支持重试 | `./scripts/product-audit-e2e.sh --case quality-evaluation-state` |
| p26-19 | 移动端关键布局与可访问交互 | P2-02 | [] | 375px 无遮挡；侧栏和段落编辑按钮可聚焦、有名称、可键盘执行 | `./scripts/product-audit-e2e.sh --case mobile-critical-path` |
| p26-20 | 用户术语与异常文案门禁 | P2-03 | [] | 修正已知错字和标签名；构建检查禁止错误术语回归 | `./scripts/user-copy-check.sh` |
| p26-21 | 本期产品审计全量验收收口 | 本期14项 | [p26-01..p26-20] | 14 项证据齐全；仅两项自动任务场景保持 XFAIL；常规 E2E、基线和 init 全绿 | `./scripts/product-audit-e2e.sh --expect-known-failures && ./scripts/e2e.sh && python3 scripts/compare-baseline.py && ./init.sh` |

## 每个 Feature 的交付门禁

1. 在 `feature_list.json` 中仅将当前 feature 标为 `in-progress`；其他修复保持 `not-started`。
2. Bug 必须先添加能复现问题的 failing test 或严格浏览器断言，并保存红灯证据。
3. 只修改当前 feature 必需的生产代码、测试和 E2E 断言。
4. 依次运行专属 `verify_cmd`、`mvn compile && mvn test`、`./init.sh`、对应浏览器场景；涉及 Prompt/Agent/上下文时追加 `./scripts/e2e.sh` 与 `compare-baseline.py`。
5. 更新 `feature_list.json` evidence 与 `progress.md`，单独提交；提交成功后才能把下一 feature 标为 `in-progress`。

## 风险与被否决方案

- **否决仅修前端接线**：无法兑现 checkpoint 真正改写、内容持久化和服务端对象安全。
- **否决一次性重写整个审核/内容模块**：无法满足单 feature、测试先行和逐提交要求，回归定位困难。
- **否决直接把 9 个 XFAIL 从已知集合删除**：本期七个场景必须因真实持久化和页面断言通过才转绿；两个自动任务场景继续显式保留。
- **风险：初始设定重做产生部分写入**。实现必须先验证候选结果并在成功后处理 checkpoint；失败路径以测试证明原审核仍可重试。
- **风险：章节回退污染记忆**。版本回退复用章节修订后处理，摘要、向量和实体/伏笔派生产物不得停留在旧正文。
- **风险：通用 Agent 修改范围过宽**。预览只生成候选，确认应用复用各领域已有写入约束，不允许 Agent 直接跨对象写库。
