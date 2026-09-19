# NCS 方案落地进行中 — 2026-09-19

Last Updated: 2026-09-19

## Current

novel-complete-solution 方案（docs/specs/2026-09-19-novel-complete-solution.md）30 个 NCS feature
按 M1–M6 逐个闭环。**M1（001-007）与 M2（008-012）已全部完成合入 main**。
active_feature=null；下一特性 NCS-013（统一修订请求与范围批准，M3 起点）。

## Files

- M1：WriteLease（epoch 租约）、FinalRequestBudget（窗口核算）、AuthorIntent（创作约定）、
  IntentView/IdentityBar（UI）、WorkspaceView（统一读取）、CheckResultEnvelope（检查信封）
- M2：谓词化断言（StoryFact+SqliteStoryCanon+fact-harvest.st）、SupportWithdrawal（撤回传播）、
  CharacterAsOf（时点 API+视图）、CharacterDecision（决策记录+经历视图）
- e2e 语料重建：data/e2e-fixtures/mystery-20260919（record 3章/9730字/双 replay 一致/baseline PASS）

## Verification

至 NCS-012（各 feature 均含分支+干净 main 检出双验证）：
- mvn test 1017 项 0 失败 / 0 错误 / 1 跳过（既有）
- e2e replay 3 章 COMPLETED/0 错误；compare-baseline PASS（NCS-008 语料重建后未退化，未触发 bless）
- npm build PASS；浏览器验证（创作约定/身份栏/时点视图/决策视图）全过
- 证据 docs/harness/evidence/2026-09-19-ncs-00{1..9}.json、-ncs-01{0,1,2}.json

## Blockers

无。环境注记：
- mvn test 需 set -a; source data/.prod-env-keys; set +a; source scripts/load-env.sh
- 干净检出：cp -R frontend/node_modules → npm build → mvn test；e2e 需复制 data/e2e-fixtures（gitignore）
- prompt 类 feature 触发语料重建（NCS-008 已重建一次；后续 NCS-018/019/020/022 若改 e2e 管线 prompt 需再重建）

## Next Steps

- NCS-013 统一修订请求（deps: []，从 main 新建 codex/ncs-013-revision-requests）
- 之后 NCS-014/015/016/017（M3 修订闭环）→ M4（018-021）→ M5（022-026）→ M6（027-030）
