# Backlog 清空（NCS-152/153/165 完成）— 2026-10-02

Last Updated: 2026-10-02

## Current

phase 54 backlog 全部清空：NCS-152（人物性格母题去重）、NCS-153（场景锚点
铁律约束）、NCS-165（replay 组装分叉修复）均 completed 合入 main。
golden 现行基线=mystery-20261001 语料（2026-10-01 bless）；NCS-153 语料
mystery-20261002 replay×2 一致且全项优于 golden 门槛（未 bless，golden 保持
更严下限）。backlog 无剩余项——下一个需求入口：诊断脚本无失败分类时向用户请求。

## Files

- main：a15bbb9（152+165）、620c0fe（153）
- 语料：data/e2e-fixtures/mystery-20261001（152/165 用）、mystery-20261002（153 用）
- evidence：docs/harness/evidence/2026-10-0{1,2}-ncs-15x-verified.json 等

## Verification

- 全量 1466/0F/0E/1S（main 合并后）；双校验 PASS
- 152/165：replay×2 全章节逐字节一致 0 miss（分叉根因=实体一致性段无稳定
  次级排序，已修）；golden rebless（n-gram 0.0626 样本口径，理由在 evidence）
- 153：replay×2 逐字节一致；compare-baseline 全 PASS（n-gram 0.0515，
  铁律 12 生效后首轮 0.1042→正常带）

## Blockers

- 无

## Next Steps

- 155/163 实机走查补录（下次真实 checkpoint 修订 / 批准被拒场景）
- 18095 实例重载新包（含 152/153/165）——先处置《剑冢守夜人》第 10 章检查点
- 新需求入口：./scripts/diagnose.sh + compare-baseline 无异常时向用户请求
