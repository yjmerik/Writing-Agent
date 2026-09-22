# Spec: UI 交互修复三连——人工复核入口 / 收尾窗口批准 / 同步占坑可观测可取消

> 来源：2026-09-22 第二轮 UI 全动线回归验证（`docs/reports/2026-09-22-ui-walkthrough/`）。
> 用户授权：针对发现的问题设计方案进行修复。

## 背景与动机

第二轮全动线回归在真实 UI 上确认三项 P2 交互缺陷（无 P1）：

- **F3（二次实证）**：守卫未完成屏障（NEEDS_MANUAL_REVIEW + ACTIVE ReviewBarrier）下，
  后端能力齐备——`GET /chapters/{n}/review-context` 返回身份与待审集合指纹，
  `POST /chapters/{n}/review-decisions` 版本化批准会解除同章同版本屏障（SqliteNovelRepository
  approveChapter 第 6/7 步）、顺带批准同章章节类 checkpoint——但作者在界面上没有被引导到
  「批准当前稿」就是解除屏障的路径：屏障 Alert 只说"正文头部的按钮核验后放行"，检查点卡片
  在正文未加载时按钮禁用且只给 title 提示，按钮文案「批准当前稿」不传达"确认已复核"语义。
  现场：《铜壶漏断》第 2 章屏障挂起，验证实例保留于 18099。
- **F4（NCS-074）**：补查/推进等同步操作以匿名 workId（`recheck-<uuid>` 等）占用项目工作权，
  期间 workspace 只显示 busy、CollaborationBar 显示"等待内容处理完成"且停止按钮禁用；
  LLM 挂起时占坑持续到超时（实测 20 分钟），作者只能等或重启。
- **F5（NCS-075）**：每章审阅下检查点出现时 worker 仍在收尾质量检查，此时批准命中
  `beginWork` 冲突，409 文案「已有进行中的写作任务，请先暂停或取消」误导（无事可暂停），
  前端还把它当版本冲突处理（"请确认新稿后再批准"）。实测等待后重试即成功。

## 目标

1. NEEDS_MANUAL_REVIEW 章节的作者能从界面被明确引导完成「确认已复核并批准」，屏障解除、
   项目恢复可写作（《铜壶漏断》第 2 章现场作为真实验收）。
2. worker 收尾窗口的批准请求获得可理解的等待语义：后端返回结构化冲突码，前端提示并
   以同一幂等键自动重试，不再误报"请确认新稿"。
3. 匿名同步占坑在界面上可见（是什么操作、已进行多久）且可取消（释放项目工作权，
   作者不再被挂起操作锁死）。

## 非目标

- 不中断进行中的 LLM 调用：取消 = 释放工作权 + 不再等待；原操作若最终完成，其产物仍按
  稿件身份落库（recheck 产物绑定 identity，身份变化时已被丢弃逻辑覆盖；批准/编辑走事务
  与版本 CAS）。此语义在 UI 文案中如实说明。
- 不改动任务型占坑（WritingTask）的既有终止语义（NCS-072/073）。
- 不做服务端排队（批准不进队列），重试由前端以幂等键安全重发。
- 不改任何 `.st` 模板与上下文组装（无 e2e 基线影响）。

## 验收标准

- [ ] NCS-076：`cd frontend && npm run build` 通过；真实 UI（18099 重建前端后）走通
      《铜壶漏断》第 2 章：屏障 Alert → 「确认已复核并批准」→ 批准成功 → 章节转 DONE、
      屏障 RESOLVED、待审 checkpoint 清空。
- [ ] NCS-075：先失败测试复现收尾窗口批准 409 无区分；修复后
      `mvn test -Dtest=WritingTaskExecutorBeginWorkConflictTest` 通过（断言
      WORKER_FINALIZING / SYNC_WORK_BUSY / 通用三级冲突码与文案）；`npm run build` 通过。
- [ ] NCS-074：先失败测试复现匿名占坑不可见不可取消；修复后
      `mvn test -Dtest=WritingTaskExecutorSyncWorkTest` 通过（登记/可见/取消释放/任务型不误伤）；
      workspace 返回 activeWork；`npm run build` 通过。
- [ ] 每个特性 `mvn compile && mvn test` 全量通过；干净 main 合并后复验通过。

## 影响面

- **涉及模块**: service/WritingTaskExecutor、web/ContentController（workspace + 新取消端点）、
  web/GlobalExceptionHandler（冲突码透传）、frontend InteractiveReview / CollaborationBar / api client
- **涉及 prompt 模板**: 无
- **数据库 schema 变更**: 无（同步占坑登记为内存态，与 ProjectWorkCoordinator 同生命周期）
- **质量基线影响**: 无 prompt/上下文改动，不触发 fixture 重建

## Feature 拆分

| id | name | 依赖 | acceptance | verify_cmd |
|----|------|------|------------|------------|
| NCS-076 | 人工复核确认前端入口 | [] | NEEDS_MANUAL_REVIEW 章节显示屏障引导 Alert；按钮文案「确认已复核并批准」；检查点卡片提供打开正文的直达按钮；真实 UI 解除《铜壶漏断》第 2 章屏障 | `cd frontend && npm run build` + 真实 UI 走查 |
| NCS-075 | worker 收尾窗口批准提示与排队 | [] | beginWork 冲突按持有者分类返回结构化冲突码与可理解文案；前端批准对 WORKER_FINALIZING 以同幂等键自动重试（≤3 次），REVIEW_CONFLICT 行为不变 | `mvn test -Dtest=WritingTaskExecutorBeginWorkConflictTest` + `npm run build` |
| NCS-074 | 同步占坑可观测可取消 | [] | 匿名同步占坑登记（kind/startedAt）；workspace 暴露 activeWork；新端点取消匿名占坑并释放工作权（任务型占坑不受影响）；CollaborationBar 显示进行中操作与取消按钮；补查按钮旁显示已用时与取消 | `mvn test -Dtest=WritingTaskExecutorSyncWorkTest` + `npm run build` |

## 风险与备选方案

- 取消后原操作迟到落库：接受（见非目标），UI 文案明示"原操作若完成仍按稿件版本落库"。
- beginWork 冲突分类需查 repo（findWritingTask）：仅冲突路径触发，热路径零开销。
- cancelSyncWork 误伤协作任务：以"workId 是否对应持久化 WritingTask"判定，单测覆盖。
