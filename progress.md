# 旅程三修复批次（NCS-139..153）— 2026-09-30

Last Updated: 2026-09-30

## Current

phase 53 已完成 9 项并合入 main（139 统一长任务轮询端点 / 140 网关逻辑调用
墙钟上限+fallback 单次 / 141 工作权锁死线程接管 / 142 模型连通性自检 / 143
带簿放行 / 145 长任务冷却重投 / 146 批准后自动恢复 / 148 分层计数 / 149
桥段自匹配修复）。NCS-150（大纲自由规划）in-progress 于分支
codex/ncs-150-outline-free-planning：record+replay 确定性已达标，
compare-baseline 结构项差异呈报，待人工 --bless 后合并。
其余 backlog：144 定向修卡 / 147 偏好竞态 / 152 人物多样性 / 153 场景锚点。

18095 实例已换新包重启（去代理），《剑冢守夜人》推进至第 10 章检查点。

## Files

- main：8 个实现提交 + 8 个登记提交（ face356..当前）
- 分支：codex/ncs-150-outline-free-planning（90c7728）
- 语料：data/e2e-fixtures/mystery-20260930（32 fixture，确定性达标）

## Verification

- 每项全量 mvn test 绿（1398→1435，净增 37 项）；npm build 通过
- 实况冒烟：统一轮询端点 404 结构化返回；模型探测真实调用 qwen 返回"通"

## Blockers

- NCS-150 的 --bless 需人工确认（结构差异=屏障停点+record 期网络重试计数）

## Next Steps

- 人工 bless → 合并 NCS-150 → 144/147 →（可选）152/153 各自 e2e
