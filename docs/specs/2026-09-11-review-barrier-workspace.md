# Spec: 审查屏障可信与工作区状态一体化（P0-A 切片）

> 依据：`/Users/erik/.codex/visualizations/2026/09/11/01a0908c-e2f5-7393-9dae-173a1870fc34/novel-collaboration-design.md`（一体化优化方案，下称"设计文档"）第 5.1/4.1/4.2/7 节，与《长夜灯下人》20 章体验记录问题 #18/#7/#5。

## 背景与动机

20 章真实体验（docs/reports/2026-09-09-novel20-experience/）证实："人机协作"的核心承诺不可靠——章节被标记 NEEDS_MANUAL_REVIEW 后，写作循环约半数情况不停止、继续写下一章；作者无法在写作期放行待审章（mutation 被拒），只能等偶发 idle 窗口。界面按钮状态由前端猜测（问题 #7：待审章定位失败；问题 #5：报告过期不提示）。设计文档 P0-A 要求"屏障、工作代次、阶段真实状态、统一行动权限"与界面"状态栏、准确待办定位、接手过程"同期交付。

## 目标

1. 人审屏障持久化为独立对象（review_barriers 表）：标记时立即落盘；调度下一章前检查项目**全部有效屏障**（不只查当前章状态字段）；屏障解决前写作循环不得启动后续章节——体验问题 #18 的根治。
2. 统一工作区状态 API：返回阶段、协作状态、待办清单（章节/稿号/原因）、允许动作及阻塞原因；前端按钮/状态由后端单一来源驱动，不再自行猜测。
3. 界面（按草图）：顶部状态栏（阶段+协作状态+主行动+待办直达）；正文与审查三栏布局（左章节目录/中正文/右审查面板）第一版。

## 非目标

- P0-B 及之后（统一版本提交/幂等、意见闭环、记忆证据、规划成书、文学质量）——本期只登记 backlog 不实现。
- 不改变既有写作链、检查链与版本语义；屏障仅叠加调度闸门，不迁移旧 NEEDS_MANUAL_REVIEW 语义。
- 不实现 SSE 增量同步（沿用轮询，契约预留 stateVersion）。

## 验收标准

- [ ] `mvn test -Dtest=ReviewBarrierGateTest`：标记屏障后（含并发与重启模拟），write-all/write-chapter 不启动新章；屏障解决后恢复。
- [ ] `mvn test -Dtest=WorkspaceStatusApiTest`：状态端点返回 allowedActions/blockedReason/todoList（含章节与稿号），各协作情形（写作中/待审/已解决）字段正确。
- [ ] `cd frontend && npm run build` 通过；状态栏按 4.2 矩阵显示协作状态与主行动；待审章一键定位（自动选中右侧目录与正文）。
- [ ] 全量 `mvn test` 绿 + canary-mystery e2e + compare-baseline PASS（调度改动影响回放路径时按新 DoD 重建语料）。

## 影响面

- **涉及模块**: persistence/sqlite（新表）、orchestrator（调度闸门）、service（WritingTaskExecutor blocker）、web（状态端点）、frontend（ProjectWorkspace/CollaborationBar/InteractiveReview 布局）
- **涉及 prompt 模板**: 无
- **数据库 schema 变更**: review_barriers 新表（id/project_id/chapter_index/version/reason/status/created_at/resolved_at）
- **质量基线影响**: 调度闸门可能改变回放流程——若 e2e 指纹漂移按 AGENTS.md 新 DoD 重建语料+bless

## Feature 拆分

| id | name | 依赖 | acceptance | verify_cmd |
|----|------|------|------------|------------|
| p33-01 | 持久化审查屏障与调度闸门 | [] | 标记屏障立即落盘；write-all/write-chapter 调度前检查全部有效屏障不启动新章；解决后恢复；NEEDS_MANUAL_REVIEW 章节自动带屏障 | `mvn test -Dtest=ReviewBarrierGateTest` |
| p33-02 | 统一工作区状态 API | [p33-01] | /workspace 端点返回 stage/collabState/todoList(章+稿+原因)/allowedActions+blockedReason；三种协作情形断言 | `mvn test -Dtest=WorkspaceStatusApiTest` |
| p33-03 | 前端状态栏与待办直达 | [p33-02] | CollaborationBar 消费新端点按状态矩阵渲染；待审章直达选中；旧 collaboration 端点兼容 | `cd frontend && npm run build` |
| p33-04 | 正文与审查三栏布局 | [] | 左目录（章+状态标识）/中正文（阅读·编辑·比较切换保留）/右审查面板（本章待办页签）第一版；窄屏侧栏抽屉 | `cd frontend && npm run build` |
| p33-05 | P0-B 统一版本提交与幂等（backlog） | [p33-01] | 设计 5.2：候选/正式分离、幂等键、批准绑定稿号 | `mvn test -Dtest=UnifiedRevisionCommitTest` |
| p33-06~08 | P1 意见闭环 / P2 记忆证据 / P3 规划成书（backlog） | | 设计 5.3~5.7 按期拆分 | |

## 风险与备选方案

- 写作循环闸门插入点：collaborationBlocker 已存在但只查 currentChapterIndex 单点；改为查全屏障列表。回放 e2e 的 3 章流程若因闸门行为改变（旧语料第 2 章后人审停——闸门不改变"停"的事实，只保证不绕过），预期指纹不变；若漂移按新 DoD 重建。
- 旧数据迁移：存量 NEEDS_MANUAL_REVIEW 章节在状态端点查询时按需补建屏障视图（不回写历史），避免迁移脚本。
- 回滚：p33-01 闸门为独立方法+表，可单独 revert。
