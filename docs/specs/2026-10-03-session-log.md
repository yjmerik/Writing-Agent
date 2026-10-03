# Spec: 全链路 Session 日志（模型调用/返回值/操作）

## 背景与动机

18095 验收轮暴露的可观测性缺口：任务生命周期日志未落 app.log（NCS-171）；
LLM 调用在 events.jsonl 只有统计行（tokens/耗时），**无请求与返回值全文**——
排障时无法还原"模型当时被问了什么、答了什么"。需要一份以 session（任务/操作）
为单位的完整日志：调用模型、返回值、操作串联，文件有大小上限（默认 150MB）并滚动。

## 目标

1. 每次模型调用在 session 日志留全量记录：taskType、model、耗时、tokens、
   **prompt 全文**、**response 全文**（或错误），并以 sessionId 串联到所属任务/操作。
2. 关键操作（任务启动/终态、批准、检查点操作、长任务）在 session 日志留操作条目。
3. session.log 单文件默认 150MB，超过后序号滚动（session.log.1、.2…），保留数可配。

## 非目标

- 不替代 events.jsonl（诊断脚本数据源不变，统计口径不动）。
- 不做 UI 呈现；不做跨进程 trace 传播（单进程内 ThreadLocal 足够）。
- 不改注入上下文/产出内容（无 e2e 基线影响）。

## 验收标准

- [ ] 单测：llm 记录含 sessionId/model/prompt/response 全文字段；op 记录落盘
- [ ] 单测：max-bytes 调小后写满触发滚动（session.log.1 生成、主文件翻新、最旧被删）
- [ ] 单测：ObservedLlmGateway complete 后 session.log 行携带 EventContext 的 sessionId
- [ ] `mvn test` 全绿（本特性不影响产出内容，无 e2e 基线要求）

## 影响面

- **涉及模块**: telemetry（SessionLog 新文件、EventContext 扩展 session 字段）、
  llm/gateway（ObservedLlmGateway 记录全文）、service（任务入口设置 session）、
  web（审批/检查点入口设置 session）
- **涉及 prompt 模板**: 无
- **数据库 schema 变更**: 无（新文件 data/logs/session.log）
- **质量基线影响**: 无（纯观测，不改注入内容）

## Feature 拆分

单 feature（NCS-172）内聚交付：SessionLog writer + 滚动 + 上下文串联 + 网关/入口接线。

## 风险与备选方案

- prompt/response 全文使单行达数百 KB：150MB 约存数千条调用，够排障窗口；
  max-files 默认 3（总上限 ~600MB），可配。
- 写入失败只 warn，绝不影响业务（与 EventLog 同约定）。
- 被否决方案：走 logback appender（滚动可配但单测难覆盖滚动行为）；
  异步队列（大行易撑爆内存）——选择同步写 + 全局锁（低频大行场景可接受）。
