# 第二轮武侠旅程回归 + 修复设计登记（NCS-087..095） — 2026-09-26

Last Updated: 2026-09-26

## Current

第二轮 UI 全流程旅程《沉钟记》完成（ego-browser、真实 LLM、12 章全批准 55,750 字、
宣告完结→门禁 FAIL→回收卷追加→重审 FAIL 死锁实证）；上轮 NCS-077..086 修复全部验证生效。
新一轮缺陷已按需求拆分协议完成设计登记，**尚未开始实现**（全部 not-started）。

| 特性 | 对应缺陷 | 优先级 |
|---|---|---|
| NCS-087 recheck 按检查器路由 | A-2 | P1 |
| NCS-088 未查清信封 AUTHOR_WAIVED 担责豁免 | A-8① | P0 |
| NCS-089 追加回收卷置回 WRITING | A-8② | P0 |
| NCS-090 框架审阅修订纳入人物层 | A-1 | P1 |
| NCS-091 recheck/checkpoint 遥测归属 | A-3 | P2 |
| NCS-092 压缩强制处置关键承诺 | A-5 | P2 |
| NCS-093 修订预览超时分层+截断守护 | A-4 一期 | P1 |
| NCS-094 preview/recheck 异步任务化 | A-4 二期（依赖 093） | P1 |
| NCS-095 章节字数趋势预警 | A-6 | P3 |

（A-7 SPA 深链已复验撤案——jar 热替换损坏 JVM 期误报。）

## Files

spec: docs/specs/2026-09-26-journey2-fixes.md（根因定位到行号 + 每项设计 + 验收 + 风险）
报告: docs/reports/2026-09-25-ui-wuxia-journey-2/体验报告.md
成书: 同目录 沉钟记-全书导出.md + evidence-*.json/png
backlog: feature_list.json（426 项，NCS-087..095 not-started）

## Verification

本轮为旅程 + 设计登记（纯文档）：feature_list/spec/progress/handoff 日期一致（09-26）；
check-harness 仅剩既有 ncs-070 FAIL（待用户裁决，与本轮无关）。
代码事实核对清单见 spec 各节（recheckChapter、runFinalAuditGate、ProjectActionPolicy、
PlanEditionService.analyze、ConsistencyGuard、AgentRevisionService）。

## Blockers

无（业务）。ncs-070 检查器 schema 冲突仍待用户裁决。

## Next Steps

1. 按 spec 顺序实现：NCS-088 → 087 → 089（先解 A-8 死锁）→ 093 → 094 → 090/091/092/095；
   每个 feature 独立分支 + bugfix gate（先写失败测试）。
2. 人工验收（保留现场）：18100 实例《沉钟记》——重检 3×consistency-guard、
   豁免 2×correction-verification、继续协作写回收卷 13-20、逐章回收 mustResolve ≤3、
   重审到 FINAL。重启命令：java -jar target/writing-agent-0.1.0-SNAPSHOT.jar
   --server.port=18100 --persistence.sqlite.db-path=./data/ui-wuxia-0925-journey/novel.db
   --persistence.vector-store.index-path=./data/ui-wuxia-0925-journey/vectors.idx（bash source scripts/load-env.sh）。
3. 运维注意：重建 target/*.jar 前先停 18100/18098 实例（运行中 fat jar 被替换会损坏 JVM）。
   旧现场 18098（听雪刀旧构建）保持不动。
