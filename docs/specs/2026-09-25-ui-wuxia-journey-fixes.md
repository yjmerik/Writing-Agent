# Spec: 武侠 UI 全流程体验问题修复（P0 完结死锁 + P1×4 + P2×4）

> 来源：2026-09-23/24《听雪刀》武侠长篇 UI 全流程体验（`docs/reports/2026-09-23-ui-wuxia-journey/体验报告.md`）。
> 用户授权：针对体验发现的问题设计方案进行修复。

## 背景与动机

18 章成书全流程真实体验暴露一处 P0 结构性死锁与多项 P1/P2 缺陷：

- **P0 状态机无法到达 FINAL**：完结判定被硬绑在 `currentChapterIndex >= WritingPreferences.targetChapters`
  一个隐式条件上（NovelOrchestrator.java:2489-2491 的 tryExtendLayeredOutline 闸门），而：
  1. 每完成 volumeIndex%5==0 的卷，`replanRemainingOutline`（NO:2208-2287）无视 targetChapters
     固定规划 `nextStart..nextStart+7` 8 章——卷卷相生（实测卷5 尾声回收卷完成即生成卷6=第19-26章）；
  2. 拒绝卷大纲并指示"不要生成后续卷"时，Plotter 空候选被当错误（`validateVolumeCandidate`
     NO:991 抛「Plotter 返回空卷大纲候选」→409），系统无法表达"没有更多内容"；
  3. 作者宣告完结无入口：`/final-audit` 仅 NEEDS_REVISION 可用（ProjectActionPolicy.java:45），
     `finishBookWithGate` 只能由 runWritingLoop 的"大纲耗尽"分支触达；PAUSED_AT_CHECKPOINT 下无任何完结动作；
  4. 大纲删除可被延迟生成"再生"（作者删除未写卷后，`currentChapter < targetChapters` 时
     tryExtendLayeredOutline 重新生成同名卷并再次暂停）；解析 0 章时 `parseAndAppendVolume`
     无条件先加空卷（NO:2613-2614），残留空卷触发 PUT「卷号必须唯一/严格递增」400。
- **P1-1**：UNKNOWN_CHECK 卡的「重新检查」按钮无 onClick（ReviewClassificationView.tsx:61-64），
  未查清项无 UI 出路，批准永远 409。
- **P1-2**：批准 409 反馈近乎不可见——「未解决 HIGH」409 无结构化 code（GlobalExceptionHandler
  对 IllegalStateException 只回 `{error}`），前端其余 409 分支仅 3 秒 toast（InteractiveReview.tsx:898-903），
  持久 Alert 只在 REVIEW_CONFLICT 设置且位置远离批准按钮；禁用按钮无静态视觉说明。
- **P1-3**：压缩 24→10 后 `outline.premise`（总纲）从不重写（PlanEditionService.confirmTarget 只裁
  synopsis），延迟生成第 10 章时 Plotter 看到的"全书总纲"仍是 24 章旧结构（含第14/22章回收位），
  规划出"新篇开头"而非终局；ContextAssembler premise 稳定段缓存也不失效。
- **P1-4**：伏笔承诺通胀——提取器 `priority==HIGH → mustResolve=true`、审计升级
  `mustResolve` 缺省 true（ForeshadowingImportanceAuditor.java:134）；大纲耗尽时
  `appendForeshadowingRecoveryVolumeIfNeeded`（NO:1880-1940）在 INTERACTIVE 模式也无询问自动
  追加 8 章尾声回收卷；`estimateTotalChapters` 用大纲最大章号（含回收卷）而非压缩后目标。
- **P2-1**：`selectBlockingHighIssues`（SqliteNovelRepository.java:1669-1691）不按 chapter_version
  过滤问题行——旧稿 v3 的 HIGH 一样阻塞 v4 批准，豁免裁决又必须精确匹配当前稿身份，
  手动保存后过期问题滚雪球阻塞（实测 4→9→17 条）。
- **P2-2/2-3**：「协作写作进行中」chip 只看任务 RUNNING 不看人审暂停（IdentityBar.tsx:84-87）；
  「让 Agent 修改」应用成功后 Modal 不自闭（AgentRevisionDialog.applyPreview 依赖父回调）；
  「影响预览」每次点击清空已填处置（OutlineView.tsx:64 `setPromiseDecisions({})`）；
  窄屏 Sider 塌缩到 width=0 后仍 position:fixed zIndex:1000 拦截点击（ProjectWorkspace.tsx:146-173）。
- **P2-4**：修订管线 LLM 调用无 EventContext.setProjectId（全仓仅 runConceiving/runWritingOneChapter
  两处设置）——修改意见重写、让 Agent 修改、段落编辑、异步派生刷新的 LLM_CALL 事件缺 projectId，
  成本无法归集。

## 目标

1. **P0**：18 章写完并批准的书能通过产品流程到达 FINAL——自动延展尊重 targetChapters、
   空卷候选表达"全书完"、作者可显式宣告完结。
2. **P1**：未查清项可从界面重检；批准 409 原因常驻可见；压缩后的目标与结构约束贯穿延迟生成；
   尾声回收卷由作者确认而非自动追加。
3. **P2**：过期 HIGH 不再阻塞批准（降级为提示）；前端状态/弹窗/遮罩残留修复；
   修订管线遥测携带 projectId。

## 非目标

- 不改任何 `.st` 模板与上下文组装逻辑（无 e2e 基线影响；premise 追加段为确定性文本拼接，
  不经 LLM）。NCS-083 的 premise 重写为追加权威覆盖段，不重新生成总纲正文。
- 不实现"保存新版本时自动重跑全部受影响检查"（LLM 成本与非确定性）；P2-1 采用
  "过期问题在批准门禁中降级为提示"路径，人工「补查当前稿连续性」仍是质量出口。
- 不改动任务型占坑（WritingTask）终止语义与协作循环的占坑协议。
- 不做大纲 PUT 校验规则（已写卷不可改/章号递增）的放宽——结构校验保持，通过
  完结出口与再生闸门消除死锁。
- 不处理 AUTO 模式的回收卷自动追加语义（保持现状；本 spec 只约束 INTERACTIVE）。

## 验收标准

- [ ] NCS-077：`mvn test -Dtest=OutlineReplanningTest` 通过——`currentChapter >= targetChapters`
      时中段重规划不产生新卷；未到目标时规划区间不超过 targetChapters；解析 0 章不残留空卷。
- [ ] NCS-078：`mvn test -Dtest=EmptyVolumeCandidateFinishesBookTest` 通过——AFTER_VOLUME_OUTLINE
      检查点拒绝指令返回空候选时不再 409，未写章被移除、大纲耗尽时项目进入 FINAL/NEEDS_REVISION。
- [ ] NCS-079：`mvn test -Dtest=DeclareFinishTest` + `mvn test -Dtest=ProjectActionPolicyTest` 通过——
      WRITING/VOLUME_DONE/PAUSED_AT_CHECKPOINT/NEEDS_REVISION 下 POST /projects/{id}/finish
      触发成书门禁、待审检查点被拒绝关闭、状态到达 FINAL 或 NEEDS_REVISION；CONCEIVING/PLANNING 拒绝；
      FINAL 幂等拒绝。`npm run build` 通过且 RunConsole 出现「宣告完结」入口。
- [ ] NCS-080：`cd frontend && npm run build` 通过；UNKNOWN_CHECK 卡「重新检查」点击后经
      review-context 身份调用 recheck 端点、完成后分类数据刷新。
- [ ] NCS-081：`mvn test -Dtest=ReviewDecisionApproveBlockedTest` 通过——未解决 HIGH 的 409
      带 `code=APPROVAL_BLOCKED`；`npm run build` 通过，批准按钮附近常驻展示 409 原因，
      禁用态有静态文字说明。
- [ ] NCS-082：`mvn test -Dtest=FinalForeshadowingRecoveryTest` 通过——INTERACTIVE 模式大纲耗尽
      且 mustResolve 未回收时不自动追加回收卷（事件可观测）；`POST /recovery-volume` 显式追加；
      审计 mustResolve 缺省 false、总数口径优先 prefs.targetChapters。
- [ ] NCS-083：`mvn test -Dtest=PlanEditionServiceTest` 通过——确认压缩后 premise 含
      【篇幅与结构（当前有效）】权威覆盖段并失效上下文缓存；规划全书末卷时 planVolume 收到
      收束指令（stub 捕获断言）。
- [ ] NCS-084：`mvn test -Dtest=ReviewVersionBindingTest` 通过——绑定旧稿版本的未解决 HIGH
      不再命中批准阻塞，当前稿 HIGH 仍阻塞。
- [ ] NCS-085：`cd frontend && npm run build` 通过；章节暂停待人审时 chip 显示暂停而非协作进行中；
      应用成功后 Modal 自闭；重开影响预览不清空已填处置；塌缩 Sider 不拦截点击。
- [ ] NCS-086：`mvn test -Dtest=RevisionTelemetryProjectIdTest` 通过——修改意见重写/段落编辑/
      让 Agent 修改预览触发的 LLM_CALL 事件携带 projectId。
- [ ] 每个特性 `mvn compile && mvn test` 全量通过；干净 main 合并后复验通过。

## 影响面

- **涉及模块**: orchestrator（NovelOrchestrator/ProjectActionPolicy）、service（PlanEditionService/
  AgentRevisionService/RevisionRepairService）、persistence（SqliteNovelRepository）、agent
  （ForeshadowingImportanceAuditor）、web（OrchestratorController/ReviewDecisionController/
  ContentController）、telemetry（EventContext 传播）、frontend（ReviewClassificationView/
  InteractiveReview/AgentRevisionDialog/OutlineView/IdentityBar/RunConsole/ProjectWorkspace/api client）
- **涉及 prompt 模板**: 无（模板变量与渲染不变；NCS-083 只改传入 planVolume 的 Java 参数值）
- **数据库 schema 变更**: 无
- **质量基线影响**: 无（不改 .st 模板与上下文组装；premise 覆盖段为确定性文本，回放语料
  不受影响——E2E 不覆盖篇幅压缩场景，详见各特性证据）

## Feature 拆分

| id | name | 依赖 | acceptance | verify_cmd |
|----|------|------|------------|------------|
| NCS-077 | 重规划与延展尊重目标章数 | [] | 中段重规划在 currentChapter>=targetChapters 时不产生新卷；区间不越 target；解析 0 章不残留空卷 | `mvn test -Dtest=OutlineReplanningTest` |
| NCS-078 | 空卷大纲候选为全书完信号 | [NCS-077] | 卷大纲检查点拒绝返回空候选时移除未写章而非 409；大纲耗尽即走成书门禁 | `mvn test -Dtest=EmptyVolumeCandidateFinishesBookTest` |
| NCS-079 | 作者宣告完结入口 | [] | POST /finish 从写作态触发门禁、关闭待审检查点、到达 FINAL/NEEDS_REVISION；策略与前端入口齐备 | `mvn test -Dtest=DeclareFinishTest,ProjectActionPolicyTest` |
| NCS-080 | 重新检查按钮接通 | [] | UNKNOWN_CHECK 卡点击→review-context 身份→recheck→刷新；npm build 通过 | `cd frontend && npm run build` |
| NCS-081 | 批准 409 常驻可见 | [] | 未解决 HIGH 409 带 APPROVAL_BLOCKED code；前端就近常驻 Alert+禁用说明 | `mvn test -Dtest=ReviewDecisionApproveBlockedTest` |
| NCS-082 | 回收卷作者确认与审计口径 | [NCS-077] | INTERACTIVE 不自动追加回收卷；显式端点追加；审计 mustResolve 缺省 false、总数取 target | `mvn test -Dtest=FinalForeshadowingRecoveryTest` |
| NCS-083 | 压缩结构贯穿延迟生成 | [] | premise 增权威覆盖段+缓存失效；末卷 planVolume 收到收束指令 | `mvn test -Dtest=PlanEditionServiceTest` |
| NCS-084 | 过期问题不阻塞批准 | [] | 旧稿版本 HIGH 不命中阻塞；当前稿 HIGH 仍阻塞 | `mvn test -Dtest=ReviewVersionBindingTest` |
| NCS-085 | 前端状态与弹窗残留修复 | [] | 暂停 chip/Modal 自闭/预览不重置/Sider 遮罩四项修复；npm build 通过 | `cd frontend && npm run build` |
| NCS-086 | 修订管线遥测归属 | [] | 修订链路 LLM_CALL 事件携带 projectId | `mvn test -Dtest=RevisionTelemetryProjectIdTest` |

## 风险与备选方案

- **NCS-078 语义风险**：Plotter 解析格式漂移也可能产出空候选，被误读为"全书完"。
  缓解：仅当移除未写章后大纲真正耗尽才直接进门禁；结果经 checkpoint revision before/after
  摘要如实呈现，作者可见变化。若不移除路径仍走原校验。
- **NCS-082 行为变化**：INTERACTIVE 从"自动追加回收卷"变为"停在 VOLUME_DONE 等作者决策"，
  已有依赖自动追加的测试需同步调整语义（FinalForeshadowingRecoveryTest）。
- **NCS-084 放宽风险**：旧稿 HIGH 在新稿未复检时不再阻塞——以「过期」标注 + 人工补查为质量
  出口，裁决台账（NCS-071）仍是显式豁免通道；当前稿检出的问题不受影响。
- **回滚**：各特性独立分支独立提交，revert 单个 merge commit 即可回滚单项行为。
- **被否决方案**：a) 保存新版本自动重跑全部受影响检查（LLM 成本高、失败模式多，采用降级路径）；
  b) premise 用 LLM 重生成（非确定性、可能丢设定，采用确定性权威覆盖段）；c) 放宽大纲 PUT
  结构校验（保持结构完整性，死锁由完结出口解除）。
