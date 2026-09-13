# 协作修复实施交接（p36-02/03/04/07 已完成） — 2026-09-13

Last Updated: 2026-09-13

## Current

p36-02（51d417a）、p36-03（28d1de9）、p36-04（76dcfd0）、p36-07（222d887）均已实现、验证并合入本地 main。入口计划优先链 p36-02→03→04→07 完成。feature_list 319 项 = 265 completed + 54 not-started，active_feature=null。

后端版本化批准与修订失效链路完整：身份模型/审计表 → 单连接事务 approveChapter（幂等/守卫/比较提交）→ HTTP review-context/review-decisions → 修订事务内屏障重绑+批准失效+历史（doEdit 事务后吞异常 rebind 已删除）。

## Files

- 分支 codex/p36-02-approval-schema、codex/p36-03-atomic-approval、codex/p36-04-versioned-review-api、codex/p36-07-revision-barrier-atomicity 均已 ff 合入 main。
- 证据：docs/harness/evidence/p36-02/03/04/07-p36.json；完成记录 completions.json → p36-0{2,3,4,7}-p36（verified）。
- 执行入口 docs/superpowers/plans/2026-09-12-collaboration-repair-entry.md；总 spec 与 p36–p40 分计划不变。

## Verification

- 四项均 RED→GREEN→合并后干净 main 复跑：全量 757 tests / 0 failures / 0 errors / 1 skipped；专项 p36-02 4/4、p36-03 8/8、p36-04 4/4、p36-07 5/5。
- 四项均无 prompt/上下文/前端改动，未跑 e2e/baseline，未 bless（quality_gate：无生成输入变更）。

## Blockers

UI 侧验证与旧入口关闭未开始：p36-05 依赖 p36-01（QA 设施未建）+ p36-04（已完成）；p36-06 依赖 p36-05/12/13。reviewSetHash 的 issues/directives/dispositions 集合待 p37/p38。不得把后端测试当作端到端修复证据。真实评分/模型预算/bless 属执行时授权事项。

## Next Steps

1. 下一特性 p36-01（建立真实浏览器回归入口，无依赖）：新增 qa/collaboration/package.json + package-lock.json + smoke.spec.ts；verify_cmd `npm --prefix qa/collaboration test -- smoke.spec.ts`；依赖安装与 chromium 安装仅发生在此特性，锁精确版本；QA_BASE_URL 未设置或非 localhost 即失败；错误端口/空白页/页面运行异常必须非零退出；截图/trace/hash 写 QA_ARTIFACT_DIR。
2. p36-01 完成后即可做 p36-05（前端统一已读版本按钮 + review-version.spec.ts）。
3. 保留工作区原有未跟踪文件（.zcode/plans、docs/reports/2026-09-05-ui-experience），不推送远端。
