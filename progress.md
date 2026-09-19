# NCS 方案落地进行中 — 2026-09-19

Last Updated: 2026-09-19

## Current

novel-complete-solution 方案 30 个 NCS feature 按 M1–M6 逐个闭环。
**M1（001-007）/M2（008-012）/M3（013-017）已全部完成合入 main（17/30）**。
M3 交付跨章修订闭环：统一修订请求→影响分析→修复候选→整组接受→修订工作区 UI。
active_feature=null；下一特性 NCS-018（SceneContract，M4 起点）。

## Files

- M3：RevisionRequest/RevisionRequestRepository/RevisionRequestService（六入口归一+范围批准分离）、
  ImpactAnalysisService（词法三分类+覆盖说明）、RevisionRepairService（隔离候选挂接）、
  GroupAcceptanceService（原子+回滚+幂等+保护锚点）、RevisionWorkspace.tsx（四步 UI）
- e2e 语料 mystery-20260919（NCS-008 重建，双 replay 一致，baseline PASS）

## Verification

至 NCS-017（每 feature 分支+干净 main 检出双验证）：
- mvn test 1036 项 0 失败/0 错误/1 跳过（既有）；e2e replay+baseline PASS；npm build PASS
- 浏览器验证：创作约定/身份栏/时点视图/决策视图/修订工作区（登记→批准→分析→影响卡片）
- 证据 docs/harness/evidence/2026-09-19-ncs-*.json（001..017）

## Blockers

无。环境注记见 session-handoff。

## Next Steps

- NCS-018 SceneContract（设计决策同 NCS-012：结构化字段+条件注入+按需生成，
  默认管线不变避免语料分叉；无合同的 synopsis 注入不变→指纹不变）
- 之后 NCS-019/020/021（M4）→ M5（022-026）→ M6（027-030）
