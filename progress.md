# 全链路 Session 日志（NCS-172）+ NCS-169 闭环 — 2026-10-03

Last Updated: 2026-10-03

## Current

- NCS-169 闭环：用户提供的按量付费 embedding key 注入 18095 并重启，
  零新增 401；语义召回最终确认随下次自然写作
- NCS-172 完成合入：全链路 Session 日志（data/logs/session.log）——LLM 调用
  prompt/response 全文 + model/tokens/耗时 + 任务生命周期/批准/检查点/长任务
  操作条目，sessionId 串联（taskId/workId）；默认 150MB 超限序号滚动
  （novel.session.max-bytes/max-files 可配，滚动件保留 3 个）
- 18095 新包为合并后版本时需再重启一次以获得 session 日志能力

## Files

- main：090af32（169）、b3f6256+eef8f48（172）
- spec：docs/specs/2026-10-03-session-log.md

## Verification

- 全量 1480/0F/0E/1S（main 合并后，连续两次绿；一次 Historical 偶发已记录观察）
- 滚动语义测试：1KB 上限 5 大行守恒滚动；网关串联成败两路

## Blockers

- 无

## Next Steps

- 18095 下次重启后即具备 session 日志；ch11 检查点待作者审阅
- NCS-170（场景锚点评测）/171（任务日志调查）随下轮旅程
