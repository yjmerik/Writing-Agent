# 18095 新包重载验收通过 — 2026-10-02

Last Updated: 2026-10-02

## Current

18095 已重载 main c404838 新包（原样参数重启，数据无损）。验收通过：
新特性端点（154/157/162）全过；第 10 章作者复核链路真实走通（版本化批准 +
自动豁免 + NCS-146 自动恢复）；第 11 章「问剑逼城」4588 字写出并暂停在新
检查点待作者审阅。报告：docs/reports/2026-10-02-18095-reload-acceptance.md

## Files

- docs/reports/2026-10-02-18095-reload-acceptance.md（验收报告）
- data/logs/18095-restart.log（新实例日志）

## Verification

- 重启前后数据一致（10 章/15 人物/检查点）；全量 1473 绿（main）
- 三特性端点 + 版本化批准 + 自动恢复全部真实实例验证

## Blockers

- **embedding key 401**（dashscope 拒绝）：向量检索已降级词法通道（设计行为），
  写作未断但后处理变慢——需用户提供有效 key 更新实例配置

## Next Steps

- 作者审阅第 11 章检查点；更新 embedding key
- 场景锚点遵从度观察项（ch11 中段 18 连对白）→ 下轮旅程正式对照
