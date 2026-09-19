# NCS 方案实施进度 — 2026-09-19

Last Updated: 2026-09-19

## Current

NCS-001..029 completed（29/30）；active_feature=null。
NCS-029 实现 9565132 已合入 main，干净 main 复验 PASS；完成证据 docs/harness/evidence/2026-09-19-ncs-029.json。
仅推进 feature 列表；额外发现只记录，不重构。

## Files

- TasksView.tsx、ProjectWorkspace.tsx、api/client.ts：任务列表、继续/停止、历史版本和整书入口。
- OrchestratorController.java、WritingTaskListTest.java：持久任务列表最小端点与隔离测试。
- docs/reports/2026-09-19-ncs-029/：浏览器报告和截图。

## Verification

- 基线 ./init.sh PASS：1082/0F/0E/1S。
- 专项先 RED 2F/0E，再 GREEN 2/0F/0E。
- 实现后 ./init.sh PASS：1084/0F/0E/1S，前端 build PASS。
- 浏览器：停止/刷新/继续保留历史；历史版本只读比较；整书聚合可见。
- 日志 /tmp/ncs029-red.log、/tmp/ncs029-green.log、/tmp/ncs029-full.log。

## Blockers

- NCS-029 无已知阻塞。费用无任务级持久记录，显示“未记录”。
- Scenario 检查协议和既有章稿批准 UI 缺口只记录，见 NCS-028 报告。
- p40-05/06 保持 deferred，属方案非目标。

## Next Steps

1. NCS-029 闭环完成；干净导出 /tmp/writingagent-ncs029-main 验证1084/0F/0E/1S及build通过。
2. 按计划进入 NCS-030 T01–T11 浏览器联合验收；发现问题记录，不扩大实现范围。
3. 浏览器 space 23 / p1；独立服务 localhost:18097。
4. 原有未跟踪 ZCode 计划与 2026-09-05-ui-experience 报告保留。
