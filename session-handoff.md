# UI 交互修复三连（NCS-074/075/076） — 2026-09-23

Last Updated: 2026-09-23

## Current
F3/F4/F5 三项 P2 交互缺陷修复完成并合入 main，真实 UI 复验通过。
- NCS-076（e9ca928）：NEEDS_MANUAL_REVIEW 章节显示屏障引导 Alert + 按钮「确认已复核并批准」；
  检查点卡片新增「打开第 N 章正文」直达。前端入口走既有版本化 review-decisions。
- NCS-075（647daa9）：beginWork 冲突按持有者分类返回 WORKER_FINALIZING / SYNC_WORK_BUSY /
  通用码；前端批准对 WORKER_FINALIZING 以同一幂等键自动重试（≤3 次×15s），
  REVIEW_CONFLICT 行为不变，其余 409 如实展示原因。
- NCS-074（eba271c）：beginWork 登记内存态 SyncWorkInfo；workspace 暴露
  activeWork{kind,label,startedAt,elapsedSeconds}；新端点 POST /active-work/cancel
  仅取消匿名占坑并释放工作权（任务型占坑走 NCS-072/073 终止路径）。
  协作栏显示进行中操作与「取消该操作」；补查按钮旁显示已用时+「取消等待」。
  诚实边界：取消不中断在途调用，原操作若完成仍按稿件身份落库。

## Files
spec: docs/specs/2026-09-23-ui-interaction-fixes.md
report: docs/reports/2026-09-23-ui-interaction-fixes/验证报告.md
backend: WritingTaskExecutor / WorkConflictException / GlobalExceptionHandler / ContentController
frontend: InteractiveReview.tsx / CollaborationBar.tsx / api/client.ts
feature_list.json + docs/harness/completions.json：三条 completed/verified 登记。

## Verification
逐特性：先失败测试（WritingTaskExecutorBeginWorkConflictTest 4/0F、
WritingTaskExecutorSyncWorkTest 6/0F）→ 实现 GREEN → ff-only 合并 → 干净 main 复验。
最终 main：mvn test 1202/0F/0E/1S（bash source scripts/load-env.sh 后运行）；
npm build PASS；ego-browser 真实 UI 三项全过。F3 遗留屏障当场解除：
铜壶漏断第 2 章 DONE v2、barrier RESOLVED、AFTER_CHAPTER×2 APPROVED、项目 WRITING。
无 prompt/上下文改动，未触发 e2e 基线重建；无远端推送。

## Blockers
无（业务）。遗留：`./init.sh` 仅剩 1 条 FAIL——`ncs-070-e2e-verified` 记录 branch
"main(e2e-acceptance)" 不满足检查器的 codex/ 前缀规则；该验收为纯 evidence 提交（协议允许
在 main），与检查器 schema 冲突，需用户裁决（放宽检查器 vs 补一条 codex 分支语义），
本会话未擅自改检查器。其余遗留格式问题（065-069/071-073 短SHA与evidence结构）已迁移合规。

## Next Steps
下一特性从 feature_list.json 选 not-started 项（当前无 phase 50 未完成项）。
文学质量真人验收归 NCS-058。验证实例保留运行于 18099
（data/ui-verify-20260922/，重启命令见 docs/reports/2026-09-23-ui-interaction-fixes）。
Ego Lite 有可用升级，待用户确认。
