# NCS 方案实施进度 — 2026-09-19

Last Updated: 2026-09-19

## Current

NCS-028 已完成，分支 codex/ncs-028-edition-validation；实现 cf9b3f1 已快进合入 main。
NCS-001..028 完成（28/30），active_feature=null；下一项 NCS-029。
用户最新约束：仅完成 feature 列表规定的特性；其他发现只记录，不顺带修复或重构。

## Files

- EditionValidationService：固定整书校验、报告读取、事务批准。
- NovelRepository / SqliteNovelRepository / SqliteSchemaInitializer：SPI、检查证据读取、报告表与一致事务。
- CheckResultRepository / ContinuityCheckerAgent：绑定实际检查正文指纹，不改变 prompt。
- BookEditionController、EditionsPanel、client.ts、types.ts：校验与报告接口/界面。
- EditionValidationTest；既有 Audit2FixTest / AuditP14EndpointsTest 补齐新门禁前置。
- docs/reports/2026-09-19-ncs-028/README.md 与截图；额外发现仅记录于报告。

## Verification

- 合并后干净 main 导出 /tmp/writingagent-ncs028-main：./init.sh、专项8项、前端构建、replay+baseline 全 PASS；证据 docs/harness/evidence/2026-09-19-ncs-028.json。

- 基线 ./init.sh PASS：1074/0F/0E/1S（Java 21）。
- 新专项先 RED 8F/0E，再 GREEN 8/0F/0E。
- 最终限定范围 ./init.sh PASS：1082/0F/0E/1S，编译及前端构建通过。
- ./scripts/e2e.sh + python3 scripts/compare-baseline.py PASS：3 章、9730 字、0 章节失败/LLM 错误。
- 浏览器：新记录未检查时显示阻断；合法检查/批准样本的报告通过、确认定稿 UI 成功；章节前置由 API 准备，不冒充全 UI Journey。
- 本地日志 /tmp/ncs028-red.log、/tmp/ncs028-scoped-final.log、/tmp/ncs028-e2e.log。

## Blockers

- 本 feature 无已知阻塞。场景网关 `[]` 与检查协议不兼容、DONE 章稿批准/重新检查 UI 入口缺口仅记录，不修复。
- p40-05/06 人工实验保持 deferred，是本方案明示非目标。
- Java 21 与正常进程附加权限为验证环境；默认 Maven Java 26 受限运行会失败。

## Next Steps

1. NCS-028 闭环完成。后续仅按 feature 列表推进，不修复额外发现。
2. NCS-029：任务与版本中心 UI，严格按已登记范围。
3. NCS-030：T01–T11 浏览器联合验收；遇既有缺口如实记录，不扩大修改范围。
4. 浏览器 space 23 / p1；隔离服务 localhost:18097，数据库 /tmp/writingagent-ncs-browser/novel.db。
5. 原有未跟踪 ZCode 计划与 2026-09-05-ui-experience 报告原样保留。
