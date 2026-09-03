# Spec: 文档状态一致性修复

> 文件名约定：`docs/specs/YYYY-MM-DD-<slug>.md`。新需求必须先落 spec，再拆 feature。

## 背景与动机

Phase 23 已于 2026-08-31 全部完成并合并到 `main`，但 `AGENTS.md` 仍保留早期的 Agent、Prompt、预设、Phase 和测试数量，`session-handoff.md` 同时包含 22/29、26/29、29/29 三种互相矛盾的状态，`progress.md` 也缺少当天的统一状态入口。过期状态会误导后续 Agent 选择错误的工作入口。

## 目标

1. 让根说明、会话交接、进度日志与 `feature_list.json` 对当前代码和验证结果给出同一结论。
2. 保留历史记录，但为后续会话提供单一、当天、无矛盾的当前状态入口。
3. 明确区分已完成能力、非阻塞观察项和下一步候选工作。

## 非目标

- 不修改业务代码、Prompt、数据库 schema 或质量基线。
- 不重新执行真实 LLM 金丝雀、400 章 soak 或浏览器 E2E。
- 不把观察项擅自登记为新的功能需求。

## 验收标准

- [x] `AGENTS.md` 的 Agent、Prompt、预设、Phase、测试和目录数字与仓库现状一致。
- [x] `session-handoff.md` 只保留一份 2026-09-02 当前状态，不再把已完成的 p22/p23 项列为待办。
- [x] `progress.md` 顶部增加 2026-09-02 文档同步记录，`feature_list.json` 登记 p24-01 且 JSON 校验通过。
- [x] `mvn compile -q && mvn test` 通过（481 tests / 0 failures / 0 errors / 1 skipped）。

## 影响面

- **涉及模块**: 项目治理文档与 feature 台账
- **涉及 prompt 模板**: 无
- **数据库 schema 变更**: 无
- **质量基线影响**: 无；不改变生成路径，无需重跑 E2E

## Feature 拆分

| id | name | 依赖 | acceptance | verify_cmd |
|----|------|------|------------|------------|
| p24-01 | 当前状态文档统一 | [] | 三份状态文档仅陈述当前仓库事实；台账 JSON 有效；完整 Maven 回归通过 | `jq empty feature_list.json && mvn compile -q && mvn test` |

## 风险与备选方案

主要风险是删除有价值的历史信息。处理方式是仅重写面向下一会话的 `session-handoff.md`，历史过程继续保留在 `progress.md` 和 Git 历史中；`progress.md` 采用顶部追加，不覆盖旧记录。
