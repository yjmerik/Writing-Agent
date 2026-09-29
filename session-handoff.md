# 架构质量深化设计与登记（NCS-096..137）— 2026-09-29

Last Updated: 2026-09-29

## Current

2026-09-29 会话完成架构深度审查（四域五路代码深扫）→ 方案设计 → backlog 登记，
纯文档提交，无业务代码改动。分支 codex/docs-arch-quality-deepening。

- spec: docs/specs/2026-09-29-arch-quality-deepening.md（总体设计四域+两横切、
  与 NCS-034..058 边界映射、42 项 feature 表、风险与被否决方案）
- 登记: feature_list.json 新增 NCS-096..NCS-137（phase 51，not-started），
  current_phase=51，updated_at=2026-09-29
- P0 13 项 = 止血闭环：注入清单(096)→同源封闭核对(097)、仲裁闭环(098)、
  派生回滚(099)、风格闭环(100/101)、例文锚点(102/103)、量化指标进基线(104)、
  LlmJson(105/106)、截断门禁(107)、路由收敛(108)

## Files

- docs/specs/2026-09-29-arch-quality-deepening.md（新增）
- feature_list.json（追加 42 项 + current_phase/updated_at）
- progress.md / session-handoff.md（刷新，旧内容归档
  docs/harness/archive/2026-09-26-{progress,session-handoff}.md）

## Verification

- python3 scripts/check-harness.py：PASS（468 features / 42 not-started 新增 /
  无依赖环 / active_feature=None / 三个状态入口日期一致 2026-09-29）
- 纯文档维护门禁：mvn compile && mvn test（bash source scripts/load-env.sh 后执行）
- 无 .st 模板改动，不触发 e2e 基线重建

## Blockers

无（业务）。ncs-070 检查器 schema 遗留待用户裁决（沿用）。

## Next Steps

1. 开始 P0：从 NCS-096 上下文注入清单起步（NCS-097/114/137 的共同地基），
   codex/ncs-096-injection-manifest 分支，TDD 先行。
2. 改注入上下文的 feature（097/110..114/102/117）合入时按 Definition of Done
   重建回放语料（E2E_FIXTURE_DIR record）+ 连续两次 replay 一致 + 人工确认后
   --bless，重建理由记入 evidence。
3. 人工 UI 验收（18100，《沉钟记》走 FINAL）仍待执行，与实施并行。
4. ncs-070 检查器 schema 遗留待用户裁决。
