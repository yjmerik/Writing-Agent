# LLM输出稳定化方案设计与backlog登记 — 2026-09-21

Last Updated: 2026-09-21

## Current
NCS-033/063/064/061/062修复已在main（1161 tests 0F/0E/1S）。
本轮针对llm-residuals-20260921.md五项模型侧遗留完成方案设计并登记backlog：NCS-065..070。
纯文档维护（spec+backlog+状态入口），未改业务代码，active_feature=null。

## Files
docs/specs/2026-09-21-llm-output-stabilization.md：设计方案（根因取证+分层修复+验收）。
feature_list.json：新增NCS-065..070（not-started，含acceptance/verify_cmd/依赖）。
关键取证：completeViaStream无终止校验（真典单字符[的传输层根因）；实体提取有效预算4096
vs约9.4k字符输出需求（截断根因）；finish_reason全链路缺失；复验缺项无补问路径。

## Verification
纯文档改动；JSON经python3解析校验（401项feature，末6项为新登记）。
分支：codex/docs-llm-output-stabilization，独立提交后ff-only合入main；
合并提交与合并后main门禁结果（mvn compile && mvn test）以git log与本文件提交时的运行为准。
无关未提交文件（.zcode/plans、docs/reports/2026-09-05-ui-experience）未纳入提交。

## Blockers
无。方案已登记待用户授权实现；golden未动、未重录、未解除人工屏障（与上轮边界一致）。

## Next Steps
实现顺序：NCS-065（流终止+截断信号）→066（EXTRACTION路由预算）→067（实体拆分瘦身）
→068（有界重试）→069（复验补问）→070（真实重录+bless门禁）。
每项走特性分支闭环；NCS-058真人文学验收依赖链不变。
