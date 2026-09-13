# 协作修复实施交接（p36-02/03 已完成） — 2026-09-13

Last Updated: 2026-09-13

## Current

p36-02（不可变审阅身份与批准审计表，主分支提交 51d417a）与 p36-03（章节批准与屏障的原子比较提交，主分支提交 28d1de9）均已实现、验证并合入本地 main。feature_list 319 项 = 263 completed + 56 not-started，active_feature=null。

仓储层现在具备：`ChapterApproval` 身份/请求/结果/审阅上下文模型、`chapter_approvals`（UNIQUE(project_id, idempotency_key)）与 `review_barrier_history` 表、单连接事务的 `approveChapter` 原子比较提交（幂等重放/双重批准守卫/身份与 reviewSetHash 比较/屏障按稿号解决并记历史/同章检查点处理/停点退出）与 `readReviewContext` 一致快照读。

## Files

- 分支 codex/p36-02-approval-schema：f7b36c2 → 51d417a（已合入）。
- 分支 codex/p36-03-atomic-approval：1fc90b7（SPI 契约+RED）→ 28d1de9（实现，已合入）。
- 证据：docs/harness/evidence/p36-02-p36.json、p36-03-p36.json；完成记录 completions.json → p36-02-p36 / p36-03-p36（verified）。
- 执行入口 docs/superpowers/plans/2026-09-12-collaboration-repair-entry.md；总 spec 与 p36–p40 分计划不变。

## Verification

- p36-02：RED 3 errors（no such table）→ 专项 4/4；合并后全量 740/0/0/1skip。
- p36-03：RED 8 errors（UnsupportedOperation）→ 专项 8/8（真并发单赢家、注入失败全回滚、连跑 4 次稳定）；合并后干净 main 全量 748/0/0/1skip。
- 两项均无 prompt/上下文/前端改动，未跑 e2e/baseline，未 bless（quality_gate：无生成输入变更）。

## Blockers

旧窗口正文v2可放行另一窗口v3的产品漏洞仍未闭环：HTTP 接口在 p36-04、前端统一版本按钮在 p36-05、旧入口关闭在 p36-06；reviewSetHash 的 issues/directives/dispositions 集合待 p37/p38 接入（当前空集合）。不得把仓储层契约测试当作端到端修复证据。临时测试服务已关闭；真实评分/模型预算/bless 属执行时授权事项。

## Next Steps

1. 从 main 新建 codex/p36-04-versioned-review-api 分支实现 p36-04：新增 web/ReviewDecisionController，GET /api/projects/{id}/chapters/{n}/review-context、POST 同路径 /review-decisions（identity+reviewSetHash+idempotencyKey+reason → ChapterApproval.Result）。
2. 控制器注入 NovelRepository 与 WritingTaskExecutor：先 acquire 项目工作权再调原子仓储、finally 释放；路径与 identity 所有者不符 400，缺字段 400，版本/待办冲突 409 且返回当前身份，WORK_OWNERSHIP_CONFLICT 沿用现有码。
3. 先写 VersionedReviewDecisionTest RED（MockMvc 真实 POST：v2读取→PUT 产生v3→提交v2 409 且v3待审；缺版本400；跨项目拒绝；同请求重试幂等）。保留工作区原有未跟踪文件，不推送远端。
