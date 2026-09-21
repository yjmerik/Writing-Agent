# NCS-033 定点修复 — 2026-09-21

Last Updated: 2026-09-21

## Current
NCS-033 in-progress；分支codex/ncs-033-guard-evidence。现有守卫补充最近两章有界正文，保留首尾取证证据并排除当前/未来稿件；不改检索架构。
按用户本轮限定范围，仅修代码缺陷，LLM输出不稳定登记，不扩展架构。

## Files
feature_list.json, docs/specs/2026-09-21-ncs-focused-fixes.md, docs/harness/evidence/ncs033/llm-residuals-20260921.md, src/main/java/com/writing/novel/agent/ConsistencyGuard.java, src/main/resources/prompts/consistency-guard.st, src/test/java/com/writing/novel/agent/ConsistencyGuardContextTest.java, docs/harness/archive/2026-09-21-before-ncs-033-focused-progress.md

## Verification
分支全量 {'tests': 1143, 'failures': 0, 'errors': 0, 'skipped': 1}；RED/GREEN日志见本轮证据。等待合并后验证。

## Blockers
真实LLM质量风险独立登记，未bless、未解除旧稿屏障。

## Next Steps
合并本特性、主分支复验、登记代码修复完成，再推进本报告其余缺陷。
