# 遗留问题定点修复 — 2026-09-21

Last Updated: 2026-09-21

## Current
NCS-063代码修复已completed，codex/ncs-063-fact-validity以4aeafdf合入main；active_feature=null。
事实提取先校验整批数据；无效响应明确失败，FACT_HARVEST保留可恢复账本，合法空数组兼容。
按用户要求，仅修遗留代码缺陷，LLM输出不稳定独立登记，不扩展架构。

## Files
docs/harness/evidence/2026-09-21-ncs-063-focused.json
模型遗留：docs/harness/evidence/ncs033/llm-residuals-20260921.md

## Verification
分支{'tests': 1151, 'failures': 0, 'errors': 0, 'skipped': 1}；干净main{'tests': 1151, 'failures': 0, 'errors': 0, 'skipped': 1}；编译与专项均通过。

## Blockers
原真实E2E/文学验收未通过，已保留原语料和屏障；未bless。代码修复完成不代表真实LLM文学质量通过。

## Next Steps
按单特性协议继续本报告剩余问题；不扩展原优化方案的其他待办。
