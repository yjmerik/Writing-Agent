# 协作修复方案已登记（未实施） — 2026-09-13

Last Updated: 2026-09-13

## Current

复验main b1c8289发现旧窗口可放行新稿等遗留问题；新增Phase36–40共58个not-started feature，261个历史completed保留，合计319项。active_feature=null。current_phase=40表示最高已登记阶段，不表示已实施。

本轮只做设计、脱敏复验记录、backlog与交接维护，不修改业务代码。文档分支codex/docs-collaboration-repair-plan-20260912。

## Files

- docs/specs/2026-09-12-collaboration-repair-program.md：总契约、迁移、API与验收矩阵。
- docs/superpowers/plans/2026-09-12-collaboration-repair-entry.md：外部工具唯一执行入口。
- docs/superpowers/plans/2026-09-12-collaboration-repair-p36.md 至 p40.md：逐项实现/验证步骤。
- docs/reports/2026-09-12-collaboration-ui-reverification.md：不依赖个人目录的复验事实。
- feature_list.json：每项files/dependencies/acceptance/verify_cmd/execution_plan/quality_gate。

## Verification

本轮设计基线./init.sh通过：736 tests / 0 failures / 0 errors / 1 skipped，前端构建通过。新规划专项尚未实现或执行，不能引用此全量结果为58项完成证据。文档最终验证与提交信息见docs/harness/evidence/2026-09-12-collaboration-repair-plan.md。

## Blockers

无新增业务实现。当前产品仍有高优先级旧稿批准漏洞，详见复验；测试绿不代表完整验收通过。真实文学实验、真实模型录制与质量bless需在执行会话按授权和人工评审完成。

## Next Steps

获得实现授权后优先p36-02→03→04→07，再p36-01→05，p36-12/13迁移测试后→06关闭旧入口；其余按dependencies逐个闭环。外部工具先读执行入口、AGENTS.md和feature_list；保留原有未跟踪内容，不推送远端。
