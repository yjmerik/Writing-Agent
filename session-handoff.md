# 交接：backlog 清空 — 2026-10-02

Last Updated: 2026-10-02

## Current

NCS-152/153/165 全部 completed 合入 main（main=620c0fe，active_feature=None）。
152：CharacterDesigner 注入既有卡性格母题摘要+≥2卡去重硬约束（motifGuide 条件块）；
165：实体一致性注入段稳定排序（根因=同 lastSeenChapter 行序跨运行不稳定 →
prompt 指纹分叉 → fixture miss；EntityConsistencyDeterminismTest 锁定）；
153：chapter-writing 场景锚点约束（铁律第 12 条，两轮语料迭代后生效）。

## Files

- 语料：mystery-20261001（152/165，golden 配套）、mystery-20261002（153）
- evidence：docs/harness/evidence/2026-10-01-ncs-15x / 2026-10-02-ncs-153

## 环境注意

- mvn test 必须 source scripts/load-env.sh；切分支后先 clean compile 再 e2e
- golden=mystery-20261001 bless（2026-10-01，n-gram 0.0689 上限带）；153 语料
  全项优于该门槛故未 bless——未来 e2e 默认用 20261002 亦可（报告对 golden 判）
- e2e record 单次 40-50 分钟（屏障停点=语料设计行为）；replay 约 6 分钟
- 18095 实例仍旧包；《剑冢守夜人》停第 10 章，重载前先处置检查点

## Verification

- 全量 1466/0F/0E/1S（main 合并后干净检出）；双校验 PASS（469 completed）
- 152/165、153 语料各自 replay×2 逐字节一致 0 miss；compare-baseline PASS

## Blockers

- 无

## Next Steps

1. 155/163 实机走查补录；18095 重载新包（含 152/153/165）
2. 新需求入口：diagnose.sh + compare-baseline 无异常时向用户请求
3. 经验：语料必须在注入确定性修复之后录制（先修分叉再录，否则 record 期
   偶然序会被固化）；质量要求区约束力弱于写作铁律区（153 两轮迭代实证）
