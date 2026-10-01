# NCS-152 in-progress（bless 待决策）+ NCS-165 阻塞发现 — 2026-10-01

Last Updated: 2026-10-01

## Current

NCS-152（人物性格母题去重）实现完成在分支 codex/ncs-152-character-motif-diversity：
先红后绿 3 单测、全量 1464 绿、语料 mystery-20261001 已重建（真实 LLM 3 章 11672 字
0 失败，比旧语料多写 2 章）、replay×2 相互逐字节一致。ch3 组装分叉（record prompt
为 replay 严格子集）阻断 compare-baseline —— 已登记 NCS-165 专项（bug）。
NCS-153 因 one-feature-at-a-time 未开始。

## Files

- 分支 codex/ncs-152-character-motif-diversity（实现 + 语料 + 诊断证据）
- docs/harness/evidence/2026-10-01-replay-ch3-divergence.json（分叉取证）

## Verification

- 全量 1464/0F/0E/1S（分支）；check-harness / check-verify-cmds PASS
- compare-baseline：结构/套路词项过；FAIL=失败3+错误3（ch3 miss 退避）+
  n-gram 0.0785>0.0426（2 章样本 vs golden 1 章样本口径差）

## Blockers

- NCS-165：≥3 章语料的 ch3 组装分叉未定位（需带组装级诊断重录复现）
- NCS-152 bless 需人工：接受 NCS-150 同形态门禁缺口，或先修 NCS-165 重录闭环

## Next Steps

- 用户决策后：a) bless 152 → 合并 → 153（同样会遇到分叉）或 b) 修 NCS-165 →
  重录 152 语料闭环 → 153（推荐，一次修复惠及所有未来语料重建）
