# NCS 方案落地交接 — 2026-09-19

Last Updated: 2026-09-19

## Current

无进行中特性。M1+M2+M3 完成（17/30）。spec：docs/specs/2026-09-19-novel-complete-solution.md。
恢复入口：从 main 新建分支，下一个 NCS-018。

## Files

- M3 最近完成：service/{RevisionRequestService,ImpactAnalysisService,RevisionRepairService,GroupAcceptanceService}、
  domain/{RevisionRequest,ImpactItem}、web/RevisionRequestController、frontend RevisionWorkspace
- 完成索引 completions.json：ncs-001..ncs-017 verified

## Verification

干净检出全绿：mvn test 1036/0F/0E/1S；e2e+baseline PASS；npm build PASS；
修订工作区经 ego-browser 四步流程验证（真实 API）。

## Blockers

无。p40-05/06 deferred。环境密钥/干净检出流程注记：
- set -a; source data/.prod-env-keys; set +a; source scripts/load-env.sh
- 干净检出：cp -R frontend/node_modules → npm build（否则 ApiCacheControlTest 失败）→ mvn test；
  e2e 需复制 data/e2e-fixtures 到检出 data/（注意 cp 目标目录须已存在，否则语料目录会变成 data 本身）

## Next Steps

- NCS-018 SceneContract：ChapterSynopsis 加结构化合同字段（schema+域+outline UI 编辑+条件注入：
  仅有合同时追加注入段——无合同语料指纹不变）；生成走按需端点/开关（同 NCS-012 决策）
