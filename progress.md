# 武侠旅程缺陷修复十连（NCS-077..086） — 2026-09-25

Last Updated: 2026-09-25

## Current

2026-09-23/24《听雪刀》UI 全流程体验报告（P0 死锁 + P1×4 + P2×4）全部修复完毕，
10 个特性（NCS-077..086）逐个走完「失败测试复现 → 修复 → 全量回归 → ff-only 合并 →
干净 main 合并后验证 → 完成登记」闭环，真实死锁项目验证通过。

- **P0 三连（状态机可到达 FINAL）**：NCS-077 中段重规划尊重 targetChapters（卷卷相生根因，
  解析 0 章不残留空卷）；NCS-078 空卷大纲候选=合法"全书完"信号（拒绝并指示完结不再 409，
  大纲耗尽直接进门禁）；NCS-079 作者宣告完结入口 POST /finish（+RunConsole 按钮，
  关闭待审检查点后触发行成书门禁）。
- **P1**：NCS-080 UNKNOWN_CHECK「重新检查」接通 recheck；NCS-081 批准 409 带
  code=APPROVAL_BLOCKED + 前端常驻 Alert/禁用说明；NCS-082 INTERACTIVE 回收卷确认制
  （POST /recovery-volume + 审计 mustResolve 缺省 false + 总数口径=target）；
  NCS-083 压缩后 premise 权威覆盖段 + 末卷延迟生成收束指令。
- **P2**：NCS-084 旧稿版本 HIGH 不再阻塞批准；NCS-085 前端四残留（暂停 chip/Modal 自闭/
  预览不重置/Sider 遮罩）；NCS-086 修订管线 LLM_CALL 携带 projectId。
- **真实数据验证**：复制 ui-wuxia-0923 库以新构建启动 18097，《听雪刀》死锁项目
  POST /finish → 检查点全关、NEEDS_REVISION（9 项必查未查清，如实反馈；对应 NCS-080 出口）；
  recovery-volume 有界 appended=false。

## Files

spec: docs/specs/2026-09-25-ui-wuxia-journey-fixes.md（验收项全部勾选）
报告: docs/reports/2026-09-25-ui-wuxia-fixes/验证报告.md（逐特性证据 + 真实数据验证）
backend: NovelOrchestrator / ProjectActionPolicy / OrchestratorController / ReviewDecisionController
  (+ApprovalBlockedException) / PlanEditionService / ForeshadowingImportanceAuditor /
  SqliteNovelRepository / AgentRevisionService / RevisionRepairService
frontend: ReviewClassificationView / InteractiveReview / RunConsole / IdentityBar /
  AgentRevisionDialog / OutlineView / ProjectWorkspace / api client / types
测试净增 29 项（1202→1231）；feature_list.json + completions.json 十条 verified 登记。

## Verification

逐特性：先失败测试（bugfix gate）→ GREEN → 分支全量 mvn test → ff-only 合并 →
干净 main 复跑 compile+verify_cmd+全量。最终 main：mvn test 1231/0F/0E/1S；
npm build + tsc 无错。真实死锁项目（data/ui-wuxia-0925-verify 副本，18097）：
/finish 打通完结出口、/recovery-volume 有界。无 prompt/上下文组装改动，未触发 e2e 基线重建。

## Blockers

无（业务）。遗留两条均为此前已记录：
1) `./init.sh` 的 ncs-070-e2e-verified 检查器 schema FAIL（branch 前缀规则 vs 纯 evidence 提交），
   待用户裁决，本轮未动检查器；
2) NCS-029..057 等 phase50 既有 not-started 项按原计划顺延。

## Next Steps

- 下轮可做：真实 UI 全动线回归（新前端 × 18097 副本实例走完「重检→裁决→批准→宣告完结」），
  或从 feature_list.json 选下一个 not-started 项。
- 武侠死锁现场保留：18098 原实例（旧构建）+ ui-wuxia-0923 原库未动，可对照验证。
