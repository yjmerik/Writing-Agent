# NCS 方案落地交接 — 2026-09-19

Last Updated: 2026-09-19

## Current

无进行中特性。M1（NCS-001..007）+ M2（NCS-008..012）全部完成合入 main（12/30）。
spec：docs/specs/2026-09-19-novel-complete-solution.md。恢复入口：从 main 新建分支，
按 feature_list.json 的 NCS DAG 序（下一个 NCS-013）。

## Files

- 最近完成 NCS-012：domain/CharacterDecision、persistence/CharacterDecisionRepository、
  agent/CharacterDecisionExtractor、service/CharacterDecisionService、web/CharacterDecisionController、
  prompts/character-decision.st、frontend CharacterDecisionsView
- 完成索引：docs/harness/completions.json（ncs-001..ncs-012 verified）

## Verification

干净检出全绿（每 feature）：mvn test 1017/0F/0E/1S；e2e replay+baseline PASS
（语料 mystery-20260919）；npm build PASS；关键 UI 经 ego-browser 验证（含真实 LLM 决策提取）。

## Blockers

无。p40-05/06 保持 deferred。

## Next Steps

- NCS-013「统一修订请求与范围批准」：六类修改入口归一 revision_requests + 范围批准/内容采纳分离
- 环境密钥与干净检出流程见 progress.md 注记
