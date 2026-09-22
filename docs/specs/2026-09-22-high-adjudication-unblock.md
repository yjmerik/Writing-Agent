# Spec: HIGH 问题裁决 UI 与暂停任务出路（修复 F1 死锁）

> 来源：2026-09-22 UI 全动线验证 F1（`docs/reports/2026-09-22-ui-walkthrough/UI全动线验证报告.md`）。
> 关联 backlog：NCS-071/072/073。

## 背景与动机

真实 UI 验证复现的死锁链：第 1 章守卫产出 HIGH（时间标记矛盾）→ 作者批准被**正确**阻断
（`SqliteNovelRepository` 审计 P1-2 门禁：未解决 HIGH 且无有效裁决不得批准）→ 作者的合法出路全部断裂：

1. **裁决提交 UI 缺失**。后端机制完备：`POST /api/projects/{id}/dispositions` 接受
   FALSE_POSITIVE / EXCEPTION / DEFER（强制理由 + 绑定当前稿 identity：chapterIndex/version/
   contentHash，实时校验），批准门禁只认绑定**当前稿**的 FALSE_POSITIVE/EXCEPTION 裁决。
   但前端全站没有提交裁决的表单；质量评价标签的「作为创作例外保留」按钮 tooltip 承诺
   "记入处置台账，附理由"，实际只做页面跳转（`navigate()` 到章节路由），与承诺不符。
2. **暂停任务占坑无出路**。新写作任务经 `ProjectWorkCoordinator.tryAcquire` 单 owner 占坑，
   PAUSED 任务的 worker 已停但 owner 释放依赖线程退出路径；`cancel(taskId)` 只设置取消标志
   （"循环将在当前章完成后退出"），对 PAUSED 任务无终态转移、不释放 owner → 「拒绝并重写」
   与新协作任务全部 409（"已有进行中的写作任务"）；「停止当前任务」虽可点击（busy=true）
   但对 PAUSED 任务无效果；「从当前进度继续」因待审核 checkpoint 禁用。

结果：作者遇到未解决 HIGH + 暂停任务时，项目在界面上无法继续——只能等重写或人工改库。

## 目标

1. 作者能在审查抽屉对 HIGH 问题提交裁决（误报/创作例外/暂挂+理由），裁决入台账后批准门禁
   按既有语义放行——不放宽任何门禁条件。
2. PAUSED 写作任务可被作者显式终止为 CANCELLED 终态并释放项目工作权；终止后「拒绝并重写」
   「从当前进度继续」可正常发起新任务。
3. 全链路可解释：裁决与终止都有理由/审计留痕（复用既有 dispositions 台账与任务记录）。

## 非目标

- 不改批准门禁语义（selectBlockingHighIssues 的版本+哈希绑定、理由强制均保持）。
- 不做批量裁决、不自动裁决（每次裁决都是作者显式行为）。
- 不改 RUNNING 任务的生命周期（p20-05 防 stale worker 重叠的接管禁令对运行中任务保持）。
- DEFER 不豁免当前批准（既有语义：推迟不等于解决）——UI 需明示。
- 不在本 spec 内处理 `resolve-review` 端点的 UI 入口（人工复核解除，另行评估）。

## 验收标准

- [ ] NCS-071：审查抽屉「检测问题」每条 HIGH 行有「裁决」入口；表单含处置类型（误报/创作
  例外/暂挂）、必填理由、暂挂时必填目标章；提交调用 dispositions API 成功后，问题行显示
  已裁决状态，审查面板刷新后批准按钮可按门禁放行（后端门禁测试证明豁免成立）；
  「作为创作例外保留」改为跳转并预打开对应问题的裁决表单。`cd frontend && npm run build` 通过。
- [ ] NCS-072：先失败测试复现「cancel 对 PAUSED 任务无效」，再修复——cancel 对 PAUSED 任务
  执行终态转移（PAUSED→CANCELLED）+ 释放项目工作权 + bump 写租约 epoch（防残存 worker 写入，
  复用 NCS-001 租约防护）；对 RUNNING 任务保持既有取消标志语义不变。
  `mvn test -Dtest=WritingTaskExecutorPausedCancelTest`（新增）通过。
- [ ] NCS-073：任务与版本视图对 PAUSED 任务显示「终止任务」（调用 cancel 端点），终止后任务
  记录显示 CANCELLED；「拒绝并重写」遇 409 时错误提示含「先在任务与版本终止暂停任务」指引。
  `cd frontend && npm run build` 通过。
- [ ] 端到端（人工验收）：真实项目重现 HIGH+PAUSED → 裁决 → 批准 → 继续写作；以及
  HIGH+PAUSED → 终止任务 → 拒绝并重写 → 新稿复验。两条出路各走通一次并留证。
- [ ] 全量 `mvn compile && mvn test` 0 failures / 0 errors；前端构建通过。

## 影响面

- **涉及模块**: `frontend/src/components/`（InteractiveReview 审查抽屉、ReviewClassificationView、
  TasksView）、`frontend/src/api/client.ts`（dispositions 提交 + cancel 复用）、
  `src/main/java/com/writing/novel/service/WritingTaskExecutor.java`（cancel 语义升级）。
- **涉及 prompt 模板**: 无。
- **数据库 schema 变更**: 无（review_dispositions、writing_tasks 均为既有表）。
- **质量基线影响**: 无（不改生成链路与注入上下文；纯交互与任务生命周期，不触发语料重建）。

## Feature 拆分

| id | name | 依赖 | acceptance | verify_cmd |
|----|------|------|------------|------------|
| NCS-071 | HIGH 问题裁决表单 | [] | 审查抽屉问题行裁决表单（类型/必填理由/暂挂目标章），提交 dispositions API，成功后刷新台账与审查上下文；「作为创作例外保留」深链预开表单；构建通过 | `cd frontend && npm run build` |
| NCS-072 | 暂停任务终止语义 | [] | 先失败测试复现 cancel 对 PAUSED 无效；修复后 PAUSED→CANCELLED 终态转移+释放工作权+bump 写租约；RUNNING 语义不变；后端测试通过 | `mvn test -Dtest=WritingTaskExecutorPausedCancelTest` |
| NCS-073 | 任务终止入口与 409 指引 | [NCS-072] | PAUSED 任务显示「终止任务」，终止后记录 CANCELLED；拒绝重写 409 提示含指引；构建通过 | `cd frontend && npm run build` |

实施顺序：NCS-071（主链路：裁决→批准→继续）与 NCS-072→073（占坑兜底）可并行排期；
每个 feature 独立分支走完整合并协议。NCS-071 完成即解除死锁主链路，072/073 补齐重写出路。

## 风险与备选方案

- **释放 PAUSED 任务 owner 的安全性**：若暂停 worker 线程仍存活，释放后与新任务短暂并存。
  缓解：cancel 时 bump 写租约 epoch（既有 NCS-001 防护，旧 worker 的一切派生写会被拒）；
  终态转移后旧 worker 在下一检查点自行退出；测试覆盖「取消 PAUSED 后立即启动新任务」。
- **DEFER 语义误解**：作者可能以为暂挂即可批准。UI 明示"暂挂不解除当前章批准门禁"。
- **误裁决风险**：裁决强制理由且不可撤销（既有台账设计），表单展示问题全文与稿件版本，
  提交前二次确认。
- **回滚**：三特性相互独立、均为 UI/服务层小改动，可单独 revert；无 schema 变更。
- **被否决方案**：自动裁决/批量裁决（削弱作者主权）；放宽批准门禁允许带 HIGH 放行
  （推翻审计 P1-2 设计）；强制接管 RUNNING 任务（违背 p20-05）。
