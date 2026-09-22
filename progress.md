# UI全动线验证与EXTRACTION配置修复 — 2026-09-22

Last Updated: 2026-09-22

## Current
按用户要求完成前端打包启动（独立实例18099/隔离数据目录）+ ego-browser 全动线验证：
20项用户动线通过（含真实生成第1章、作者编辑逐字保留、批准门禁、篇幅调整承诺处置、
导出等）；发现3项，F2（模型配置页缺EXTRACTION路由）已当场修复合main（6d3a242）；
F1（HIGH裁决UI缺失+暂停任务占坑死锁）登记为NCS-071待办。active_feature=null。

## Files
docs/reports/2026-09-22-ui-walkthrough/UI全动线验证报告.md：完整验证报告。
feature_list.json：新增NCS-071（not-started）。
src/main/java/com/writing/novel/web/ModelConfigController.java：+EXTRACTION scope（修复）。
data/ui-verify-20260922/：验证实例数据（本地）。

## Verification
修复走RED→GREEN：ModelConfigControllerTest 断言10条路由+提取任务条目；
干净main全量1187/0/0/1通过；实例重启后UI显示「提取任务 EXTRACTION 16000」。
真实运行触发NCS-065（LLM_TRUNCATED诚实屏障）与NCS-068（重试成功）新机制，均正确工作。

## Blockers
F1死锁待实现（NCS-071）：HIGH未决+PAUSED任务占坑时界面无出路；裁决提交UI缺失。

## Next Steps
授权后实现NCS-071（先失败测试复现死锁场景）。实例保留运行于18099供用户查看。
