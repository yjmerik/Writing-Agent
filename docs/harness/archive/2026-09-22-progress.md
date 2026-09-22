# UI全动线回归验证（F1修复后） — 2026-09-22

Last Updated: 2026-09-22

## Current
按用户要求完成第二轮全动线回归：打包重启（main HEAD）+ ego-browser 新项目《铜壶漏断》
完整生命周期 + 既有项目核查。上轮20项动线回归通过；NCS-071/072/073修复全部在真实界面
复验生效（裁决→批准→继续写作；终止任务→已停止）。新发现F4/F5（P2）登记NCS-074/075；
F3（resolve-review无UI入口）二次实证。active_feature=null。

## Files
docs/reports/2026-09-22-ui-walkthrough/UI全动线回归验证报告.md：本轮报告。
feature_list.json：新增NCS-074/075（not-started）。
验证实例保留运行于18099（数据目录data/ui-verify-20260922/，本地）。

## Verification
纯验证无代码改动。铜壶漏断2/2章9583字：第1章A级8/10已批准；第2章守卫未完成屏障→
补查成功（5.3分钟）→屏障待人工确认（F3无入口，保持诚实状态）。
卷末回读生成与展示、成书覆盖清单、终止任务×3、模型配置10路由均通过。

## Blockers
无。F3/F4/F5已登记（NCS-074/075及既有F3记录）。

## Next Steps
授权后实现NCS-073类前端入口缺口（resolve-review入口）与NCS-074/075。
文学质量真人验收仍归NCS-058。
