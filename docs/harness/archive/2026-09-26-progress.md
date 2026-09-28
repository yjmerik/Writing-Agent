# 第二轮旅程修复九连（NCS-087..095）完成 — 2026-09-26

Last Updated: 2026-09-26

## Current

第二轮武侠旅程（《沉钟记》）发现的全部 9 项缺陷已修复完毕并合入 main。
A-8 终局死锁三因全部解除：NCS-088（未查清信封 AUTHOR_WAIVED 担责豁免：批准自动豁免
+ 手动豁免端点）、NCS-087（recheck 按检查器路由，consistency-guard 可真实重检，
correction-verification 409 明示豁免出路）、NCS-089（追加回收卷置回 WRITING 解禁写作）。

| 特性 | 内容 | 提交 |
|---|---|---|
| NCS-088 担责豁免 | 枚举+批准自动豁免+check-waiver+前端按钮+校验报告标签 | 27c32c3 |
| NCS-087 重检路由 | checkers 参数路由；correction-verification 409 指引 | d029b19 |
| NCS-089 回收卷解禁 | 追加回收卷置回 WRITING + RECOVERY_RESUMED 事件 | d60fd23 |
| NCS-093 截断守护 | 修订预览 finish_reason=length fail-fast + 900s 分层超时 | 1a97381 |
| NCS-091 遥测归属 | recheck/checkpoint 修订 EventContext 传播 | a15571d |
| NCS-092 压缩台账 | 无回收位关键承诺强制处置 + MIGRATE 回写回收位 | f76042f |
| NCS-095 字数预警 | 前 3 章均值 50% 阈值「篇幅骤降」提示（后端+审阅卡） | b621819 |
| NCS-090 人物层同步 | 修改意见触发补新配角卡 + 点名零出场卡（意图门控） | 2a6fcc7 |
| NCS-094 异步任务化 | LongTaskService + recheck-async/preview-async + 前端轮询 | d4aabe9 |

每个特性独立分支、失败测试先行（RED→GREEN）、ff-only 合并、干净 main 复验、verified 登记。

## Files

spec: docs/specs/2026-09-26-journey2-fixes.md
报告: docs/reports/2026-09-25-ui-wuxia-journey-2/体验报告.md
evidence: docs/harness/evidence/2026-09-26-ncs-09*.json（9 份）
后端: NovelOrchestrator（recheck 路由/人物层同步/回收卷状态）/ CheckWaiverService /
  LongTaskService / ReviewDecisionController / CheckpointController / RevisionController /
  CheckResultRepository / EditionValidationService / ContentRevisionAgent
前端: ReviewClassificationView（豁免+异步重检）/ AgentRevisionDialog（异步预览）/
  RunConsole / InteractiveReview（篇幅骤降 Tag）/ EditionsPanel / api client

## Verification

干净 main：mvn test 1257/0F/0E/1S（净增 26 项测试，1231→1257）；
npm run build + tsc --noEmit 通过；逐特性 verify_cmd + 合并后全量见 evidence JSON。
18100 现场已用新构建重启（pid 见 server.log），《沉钟记》保持 NEEDS_REVISION，
数据完好——可立即人工验收：重检 3×consistency-guard → 豁免 2×correction-verification
→ 继续协作写回收卷 13-20 → 回收 mustResolve ≤3 → 重审到 FINAL。

## Blockers

无（业务）。ncs-070 检查器 schema 遗留待用户裁决。

## Next Steps

- 人工 UI 验收（18100，约 3-4 小时含回收章写作）：按上条路径走 FINAL。
- NCS-087 首次点击静默失败的根因（前端乐观 loading vs 服务端未受理）已由 NCS-094
  异步化规避；如需彻底定位可另立小特性。
- ncs-070 检查器 schema 遗留仍待用户裁决。
