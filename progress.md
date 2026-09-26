# 第二轮武侠旅程回归与修复设计（NCS-087..095 登记） — 2026-09-26

Last Updated: 2026-09-26

## Current

2026-09-25/26 完成第二轮真实用户 UI 全流程旅程《沉钟记》（12 章 55,750 字全批准，
wuxia 预设，实例 18100 / data/ui-wuxia-0925-journey）。**上轮 NCS-077..086 修复全部验证生效**
（宣告完结、常驻 Alert、过期 HIGH 不阻断、回收卷确认制、弹窗自闭、预览遥测）。
旅程发现新一轮缺陷并完成修复设计，按需求拆分协议落 spec + backlog：

- **A-8（P0）终局死锁变体**：门禁 unknownCount>0 × recheck 只跑 continuity（A-2）×
  NEEDS_REVISION 禁写回收卷——12 章成书无法经任何 UI 路径到 FINAL。
  对应 NCS-087（recheck 按检查器路由）/ NCS-088（未查清信封 AUTHOR_WAIVED 担责豁免）/
  NCS-089（追加回收卷置回 WRITING）三连解锁。
- **A-1（P1）** 修改意见重写只改世界观、人物层被静默丢弃 → NCS-090 人物层同步。
- **A-4（P1）** 修订预览失控长输出（52k token/9.4min）vs 前端 300s 超时 → NCS-093 超时分层
  +截断守护、NCS-094 异步任务化（依赖 093）。
- **A-3/A-5/A-6（P2/P3）** → NCS-091 遥测归属、NCS-092 压缩强制处置关键承诺、
  NCS-095 字数趋势预警。
- **A-7 撤案**：SPA 深链 500 系 jar 热替换损坏 JVM 期的误报，健康实例复验 200。

## Files

spec: docs/specs/2026-09-26-journey2-fixes.md（根因+设计+验收+风险）
报告: docs/reports/2026-09-25-ui-wuxia-journey-2/体验报告.md（含修复对照表、A-7 撤案标注）
成书: 同目录 沉钟记-全书导出.md（56,026 字符）+ 证据文件
backlog: feature_list.json 追加 NCS-087..095（9 项，not-started，含 acceptance/verify_cmd）

## Verification

- 旅程全程真实 LLM、仅 UI 操作（诊断性只读 API/sqlite 已在报告标注）；
  249 次归属 LLM 调用 ≈6.2 元。
- 修复设计经代码级核对：recheckChapter:3171、runFinalAuditGate:2505、ProjectActionPolicy
  NEEDS_REVISION 分支、PlanEditionService.analyze:253、ConsistencyGuard 双检查器、
  AgentRevisionService NCS-086 模式。
- check-harness：仅剩既有 ncs-070 遗留（待用户裁决）。

## Blockers

无（业务）。ncs-070 检查器 schema 遗留待用户裁决（此前已记录）。

## Next Steps

按 spec 实施顺序逐个推进（同一时间一个 feature，bugfix gate 先写失败测试）：
NCS-088 → 087 → 089（A-8 解锁）→ 093 → 094 → 090/091/092/095。
人工验收用保留现场：18100《沉钟记》重检 3 个 consistency-guard、担责豁免 2 个
correction-verification、写回收卷章节、重审到 FINAL。
旧现场未动：18098（听雪刀旧构建）。
