# NCS-063 定点修复 — 2026-09-21

Last Updated: 2026-09-21

## Current
NCS-063 in-progress；分支codex/ncs-063-fact-validity。事实提取先校验整批数据；无效响应明确失败，FACT_HARVEST保留可恢复账本，合法空数组兼容。
按用户本轮限定范围，仅修代码缺陷，LLM输出不稳定登记，不扩展架构。

## Files
feature_list.json, docs/specs/2026-09-21-fact-harvest-validity.md, src/main/java/com/writing/novel/agent/FactHarvesterAgent.java, src/main/java/com/writing/novel/orchestrator/ChapterPostProcessor.java, src/test/java/com/writing/novel/orchestrator/FactHarvestValidityTest.java, src/test/java/com/writing/novel/agent/FactHarvesterTest.java, src/test/java/com/writing/novel/agent/FactHarvesterScopedTest.java, src/test/java/com/writing/novel/web/StubLlmGatewayTestConfig.java, docs/harness/archive/2026-09-21-before-ncs-063-focused-progress.md

## Verification
分支全量 {'tests': 1151, 'failures': 0, 'errors': 0, 'skipped': 1}；RED/GREEN日志见本轮证据。等待合并后验证。

## Blockers
真实LLM质量风险独立登记，未bless、未解除旧稿屏障。

## Next Steps
合并本特性、主分支复验、登记代码修复完成，再推进本报告其余缺陷。
