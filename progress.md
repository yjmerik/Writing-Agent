# LLM输出稳定化实现与真实重录验收 — 2026-09-22

Last Updated: 2026-09-22

## Current
NCS-065..069 已逐项按协议实现、合入本地 main 并登记完成（active_feature=NCS-070 in-progress）。
NCS-070 真实重录验收的机器可验证项全部完成：record+replay×2 三次一致、诊断零失败分类、
EXTRACTION 零截断（实体27/关系40/真典21，修复前恒为0）、数值基线 PASS。
第2章守卫 HIGH（正确引用前文）→ 复核屏障 → 任务 PAUSED，完整验收诚实判 FAIL。

## Files
docs/specs/2026-09-21-llm-output-stabilization.md：方案（上轮合入）。
src/main/java/com/writing/novel/llm/：NCS-065 终止校验+finishReason+LLM_TRUNCATED。
src/main/java/com/writing/novel/agent/：NCS-067/068 实体两阶段拆分+有界重试；NCS-069 复验缺项补问。
application.yml：NCS-066 EXTRACTION 路由（16k/0.1/关思考，DashScope enable_thinking）。
docs/harness/evidence/2026-09-21-ncs-06X-focused.json ×5 + 2026-09-21-ncs-070-e2e.json。
data/e2e-evidence/ncs065-070-20260921/：两批真实运行产物（本地）。
data/e2e-fixtures/ncs065-070/mystery-20260922/：可回放语料46条（本地）。

## Verification
每项先RED后GREEN；分支与干净main各自 mvn compile && mvn test 通过；
最终 main 全量 1187 tests / 0 failures / 0 errors / 1 skipped（累计新增26项）。
E2E：干净导出+author-intent+6h轮询的record一次、replay两次，报告/正文SHA-256/DB计数一致；
diagnose 失败分类0条；compare-baseline.py PASS。golden未更新、无远端推送、旧语料未改动。

## Blockers
NCS-070 剩两个人工门禁：--bless 需用户确认新旧质量对比；第2章复核屏障需人工处理。
补充登记：纠偏复验整体格式无效时仍直接屏障（NCS-069只补缺项）——后续候选feature。
首轮record被e2e.sh 5400s轮询上限掐断（两次30分钟网关超时），已用放宽上限的临时包装器重录；
e2e.sh 本身未改动。

## Next Steps
用户确认后：compare-baseline.py --bless（重建理由已记入证据）→ NCS-070 完成；
人工处理第2章屏障后可续写。文学质量真人验收仍归 NCS-058。
