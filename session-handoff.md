# 武侠旅程缺陷修复十连（NCS-077..086） — 2026-09-25

Last Updated: 2026-09-25

## Current

《听雪刀》体验报告 9 类缺陷（P0×1 + P1×4 + P2×4）全部修复并合入 main，
每特性独立分支、失败测试先行、合并后干净 main 复验、verified 登记。

| 特性 | 分支 | 状态 |
|---|---|---|
| NCS-077 重规划尊重 targetChapters+空卷不残留 | codex/ncs-077-replan-respects-target | completed |
| NCS-078 空候选=全书完信号 | codex/ncs-078-empty-candidate-finishes | completed |
| NCS-079 作者宣告完结 POST /finish + UI | codex/ncs-079-author-finish | completed |
| NCS-080 重新检查按钮接通 | codex/ncs-080-recheck-button | completed |
| NCS-081 批准 409 常驻可见 | codex/ncs-081-approval-blocked-visibility | completed |
| NCS-082 回收卷确认制+审计口径 | codex/ncs-082-recovery-volume-consent | completed |
| NCS-083 压缩结构贯穿延迟生成 | codex/ncs-083-compressed-structure-inheritance | completed |
| NCS-084 过期 HIGH 不阻塞批准 | codex/ncs-084-stale-issues-no-block | completed |
| NCS-085 前端四残留修复 | codex/ncs-085-frontend-residue-fixes | completed |
| NCS-086 修订管线遥测 projectId | codex/ncs-086-revision-telemetry-projectid | completed |

## Files

spec: docs/specs/2026-09-25-ui-wuxia-journey-fixes.md
验证报告（含真实死锁项目 /finish 实测）: docs/reports/2026-09-25-ui-wuxia-fixes/验证报告.md
scripts/register-completion.py：本轮新增的完成登记辅助脚本。
feature_list.json + docs/harness/completions.json：十条 verified + evidence JSON（docs/harness/evidence/2026-09-25-ncs-0*.json）。

## Verification

最终干净 main：mvn test 1231/0F/0E/1S（净增 29 项测试）；npm build + tsc 无错；
python3 scripts/check-harness.py 仅剩既有 ncs-070 检查器 schema FAIL（待用户裁决）。
真实数据：data/ui-wuxia-0925-verify 副本（新构建，18097 已停，重启命令见验证报告五节）
——死锁项目 POST /finish 检查点全关→NEEDS_REVISION（9 项必查未查清，出口=质量页重新检查后重审）；
POST /recovery-volume 有界。原 18098 实例与 ui-wuxia-0923 原库未动。
无 .st/上下文组装改动，未触发 e2e 基线重建。

## Blockers

无（业务）。ncs-070-e2e-verified 检查器 schema 冲突仍待用户裁决（放宽检查器 vs 补 codex 分支语义）。

## Next Steps

1. 真实 UI 全动线回归（新前端 × 副本实例）：重检未查清项→裁决→批准→宣告完结→FINAL，
   与 18098 旧现场对照；建议作为下一轮首个任务（可登记新 feature）。
2. 或从 feature_list.json 选 not-started 项（NCS-034 起有依赖需核对）。
3. NCS-058 真人艺术验收仍归档等待。
