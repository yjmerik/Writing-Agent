# NCS 方案实施进度 — 2026-09-20

Last Updated: 2026-09-20

## Current

NCS-001..029 completed（29/30）；NCS-030 in-progress，分支 codex/ncs-030-browser-journey。
NCS-029 实现9565132、完成回执6480704均在main，干净main复验1084/0F/0E/1S与build通过。
NCS-030 仅验收报告及状态记录，未改业务代码；联合场景未全部通过，尚未合入main、不登记完成。
用户约束：仅完成feature规定特性，其他发现只记录，不修复或重构。

## Files

- docs/reports/2026-09-19-ncs-journey/README.md：T01–T11逐项实测、限制和阻塞。
- 同目录截图及browser-events.jsonl：局部采纳、并发409、单次要求阻断、恢复v5、固定整书报告。
- feature_list.json、progress.md、session-handoff.md：当前状态。

## Verification

- NCS-030 串行 ./init.sh PASS：1084/0F/0E/1S，编译及前端构建通过。
- replay E2E+baseline PASS：3章9730字、0章节失败、0LLM错误。
- 初次并发运行Maven/E2E共享target导致36项类加载错误；串行复验恢复，未改代码。
- 浏览器局部路径：停止/继续及历史保留、未选区保护、旧候选409拒绝并保留作者输入、历史恢复为新稿且保留旧记录、报告不跨版本继承。
- 采集26条响应，唯一HTTP错误为预期409；采集窗口未见console异常。不冒充完整HAR。
- 日志 /tmp/ncs030-serial.log、/tmp/ncs030-e2e.log。

## Blockers

- NCS-030 未达到全部场景通过：T10缺组合候选UI；T04冲突后按钮仍可点击且无三方比较。
- T03/T06/T07样本缺完整依赖、知情来源和单次要求证据，不能据局部结果判通过。
- T05跨章恢复/丢响应重试、T08补查/零预算、T11真实下载等证据未完整。
- 以上仅记录，详见报告；NCS-028既有额外发现也未修复。
- p40-05/06继续deferred，属于本方案非目标。

## Next Steps

1. 保留NCS-030分支和未完成状态；不在无新范围授权时扩展修复其他feature。
2. 后续安排验收数据与相关缺口后，续做同一feature并完整重跑T01–T11；全通过才能合并和登记完成。
3. 浏览器space23仅p1，p2已关闭；验收数据库 /tmp/writingagent-ncs-browser/novel.db；本任务验收服务结束时停止。
4. 原有未跟踪ZCode计划与2026-09-05-ui-experience报告原样保留。
