# 架构质量深化设计与登记（NCS-096..137）— 2026-09-29

Last Updated: 2026-09-29

## Current

应用户要求对现有架构做深度审查（长程一致性/风格一致性/内容召回/LLM harness
四域五路代码深扫），按需求拆分协议完成方案设计与 backlog 登记，未动业务代码。

- 审查结论：长程记忆是四条平行管线（级联摘要/真典/向量 chunk/台账图）无统一
  相关性裁决与使用反馈；检查多闭环少（RepairArbiter 死代码、重写不回滚派生事实、
  检查器漏检被固化为真典）；风格无例文锚点、styleScore 生产恒 null、基线追随正文；
  LLM 两套路由语义并存、结构化解析 12+ 处复制、半章可入库、严格串行。
- 方案：两个横切地基（上下文注入清单 NCS-096、check-the-checker 金标集 NCS-137）
  + 四域 42 个 feature，分 P0（13 项止血闭环）/P1（19 项上限提升）/
  P2（10 项规模化与成本）三波；与既有 not-started NCS-034..058 划清边界
  （成本持久化=054/055、租约=036..038、outbox=041..044、检索评测=057 不重复登记）。

## Files

- spec（设计全文+边界映射+风险）：docs/specs/2026-09-29-arch-quality-deepening.md
- 登记：feature_list.json 追加 NCS-096..NCS-137（phase 51，全部 not-started，
  含 acceptance/verify_cmd/依赖），current_phase=51
- 归档：docs/harness/archive/2026-09-26-{progress,session-handoff}.md
- 本分支：codex/docs-arch-quality-deepening（纯文档，无业务代码改动）

## Verification

- python3 scripts/check-harness.py 通过（468 features，42 项新增 not-started，
  无依赖环，active_feature=None）
- 纯文档维护：按协议执行既有编译与测试门禁（mvn compile && mvn test），
  结果见本次合并提交说明；不新增业务测试

## Blockers

无（业务）。ncs-070 检查器 schema 遗留待用户裁决（沿用）。

## Next Steps

1. 人工 UI 验收（18100，《沉钟记》NEEDS_REVISION 现场保留）仍待执行——
   与本 spec 实施并行不冲突。
2. P0 波次逐 feature 闭环（一次一个，从 NCS-096 注入清单起步——它是
   NCS-097/114/137 的共同地基）；改注入上下文的 feature 合入时按 DoD
   重建回放语料并记录理由。
3. ncs-070 检查器 schema 遗留待用户裁决。
