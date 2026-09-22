# UI全动线验证与EXTRACTION配置修复 — 2026-09-22

Last Updated: 2026-09-22

## Current
前端打包+独立实例（18099/隔离数据目录）+ ego-browser 全动线验证完成：
20项用户动线通过；F2（配置页缺EXTRACTION）已修复合main 6d3a242；
F1（HIGH裁决UI缺失+暂停任务占坑死锁）登记NCS-071待办。active_feature=null。

## Files
docs/reports/2026-09-22-ui-walkthrough/UI全动线验证报告.md：20项动线+3项发现详情。
修复提交：6d3a242（分支codex/ui-extraction-config-scope，ff-only合main）。
NCS-071已登记backlog（not-started，含验收标准）。
验证实例：端口18099，数据目录data/ui-verify-20260922/（本地），验证后保留运行。

## Verification
RED→GREEN：ModelConfigControllerTest 10条路由断言；干净main全量1187/0/0/1。
重启实例后UI确认「提取任务 EXTRACTION」可见。
真实生成第1章过程中LLM_TRUNCATED与STRUCTURED_OUTPUT_RETRY均如实触发并正确处理。

## Blockers
F1/NCS-071待实现：HIGH未决+PAUSED任务占坑→界面无出路；裁决提交UI缺失
（后端dispositions API完备）。

## Next Steps
授权后实现NCS-071。文学质量真人验收仍归NCS-058。
