# LLM输出稳定化实现与真实重录验收 — 2026-09-22

Last Updated: 2026-09-22

## Current
NCS-065..069 已逐项按协议实现、合入本地 main 并登记完成（active_feature=NCS-070 in-progress）。
NCS-070 真实重录验收的机器可验证项全部完成：record+replay×2 三次一致、诊断零失败分类、
EXTRACTION 零截断（实体27/关系40/真典21，修复前恒为0）、数值基线 PASS。
第2章守卫 HIGH（正确引用前文）→ 复核屏障 → 任务 PAUSED，完整验收诚实判 FAIL。

## Files
实现提交（均已ff-only合入main）：c149912(065) 46b2aef(066) 9a7fd55(067) 5dd167d(068) 5a016c6(069)；
各配套docs证据与登记提交见 git log。NCS-070 证据：docs/harness/evidence/2026-09-21-ncs-070-e2e.json；
可回放语料：data/e2e-fixtures/ncs065-070/mystery-20260922/（46条，本地）；
原始产物：data/e2e-evidence/ncs065-070-20260921/（record2-replay/ 与 supplementary-run1/）。

## Verification
每项先RED后GREEN；分支与干净main各自 mvn compile && mvn test 通过；
最终 main 全量 1187 tests / 0 failures / 0 errors / 1 skipped（新增26项：
Termination 8 + Routing 5 + Split 4 + Retry 6 + GapRefill 3）。
E2E record 复现命令（隔离导出目录内）：
E2E_FIXTURE_DIR=data/e2e-fixtures/ncs065-070/mystery-20260922 python3 run-record.py --mode replay
（run-record.py 为 docs/harness/evidence/ncs033/run-intent-e2e.py 的等价包装器+轮询上限放宽，
未改动仓库内 scripts/e2e.sh）。golden未更新、未bless、无远端推送。

## Blockers
NCS-070 剩两个人工门禁：--bless 需用户确认新旧质量对比；第2章复核屏障需人工处理
（守卫对『盐引脚钱混旧铜』凭空证据的判断，引用了前文，非NCS-033修复前的召回缺失模式）。
补充登记：纠偏复验整体格式无效时仍直接屏障（NCS-069只补缺项）——后续候选feature。

## Next Steps
用户确认后：compare-baseline.py --bless（重建理由已记入证据）→ NCS-070 完成；
人工处理第2章屏障后可续写。文学质量真人验收仍归 NCS-058。
