# Backlog 清空 + 实机走查闭环 — 2026-10-02

Last Updated: 2026-10-02

## Current

NCS-152/153/165 闭环后完成实机走查（本地 18096 新包，INTERACTIVE，真实 LLM）：
- NCS-154/155 预览-确认链 API 全链路通过（reject 点名→diff 预览库不变→
  discard 拦截过度改写→二次 reject→confirm 按 id 落库）
- 走查撞出并修复 2 个真缺陷：NCS-166（构思线程静默死亡→Throwable 兜底落
  FAILED+事件；复验 30 秒可见 vs 旧 46 分钟挂起）、NCS-167（PLANNING 断头路
  →runPlanning 幂等补跑大纲）
- 新登记 NCS-168：初建检查点 reject 后无新一轮 PENDING（每轮仅一次修订机会）
- NCS-152 直接受益目击：三轮构思 8 卡性格母题各异
- 18095 实例仍旧包；《剑冢守夜人》停第 10 章（作者决策保留）

## Files

- main：da71947（166/167）、c87551c（incident 取证）+ 本轮走查证据
- docs/harness/evidence/2026-10-02-ncs-155-walkthrough.json

## Verification

- 全量 1470/0F/0E/1S（main 合并后）；双校验 PASS（499 features）
- 走查全链路见 evidence；153 语料 replay×2 一致且全项优于 golden 门槛

## Blockers

- 无

## Next Steps

- NCS-168（reject 再审核闭环）+ 163 浏览器级走查（批准被拒场景）
- 18095 重载新包（含 152/153/165/166/167）；先处置第 10 章检查点
