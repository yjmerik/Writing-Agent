# NCS 方案落地进行中 — 2026-09-19

Last Updated: 2026-09-19

## Current

novel-complete-solution 方案 30 个 NCS feature 按 M1–M6 逐个闭环。
**M1（001-007）/M2（008-012）/M3（013-017）/M4（018-021）已完成合入 main（21/30）**。
M4 交付：SceneContract（条件注入）、计划偏离对照（三选一决定）、卷末回读（阻断/确认）、按正文更新计划。
active_feature=null；下一特性 NCS-022（审美档案，M5 起点）。

## Files

- M4：ChapterSynopsis.sceneContract、SceneContractAgent/Service/Controller + scene-contract.st、
  PlanDeviation 域/仓储/服务 + plan-deviation.st + OutlineView 偏离面板、
  VolumeReadback 域/仓储/服务 + volume-readback.st + 卷过渡阻断 + 成书审查回读卡片、
  ReplanFromTextService（回读结论注入再规划）
- e2e 语料 mystery-20260919（NCS-008 重建后全程稳定）

## Verification

至 NCS-021（每 feature 分支+干净 main 检出双验证）：
- mvn test 1053 项 0 失败/0 错误/1 跳过（既有）；e2e replay+baseline PASS；npm build PASS
- 真实 LLM 验证：决策提取、场景合同生成（10 字段）；浏览器 UI：修订工作区/合同卡片/时点视图
- 证据 docs/harness/evidence/2026-09-19-ncs-*.json（001..021）

## Blockers

无。

## Next Steps

- NCS-022 审美档案（M5）：认可片段/减少表达/偏好维度存储版本化+创作约定页维护+写作上下文注入
- 注入设计延续条件化模式：有档案条目才注入，避免语料分叉
- 之后 NCS-023..026（M5）→ M6（027-030）→ 最终验收
