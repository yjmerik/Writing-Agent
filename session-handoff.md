# Session Handoff

## Current Objective

- **Goal**: Phase 15 人机设定扩容与作业可观测。
- **Current status**: spec 已确认，**0/6 not-started**。下一入口 `p15-01`。
- **Spec**: `docs/specs/2026-08-18-hitl-expansion.md`
- **Branch**: `phase-11-quality-gate-architecture`（未 push）

## Completed

| id | 证据 |
|---|---|
| p14-01 | SqliteNovelRepositoryDeleteTest；UI 删除 QA一次性项目成功 |
| p14-02 | GlobalExceptionHandlerTest + ConceivingCompletenessTest |
| p14-03 | EditPassageReturnsBeforeHarvestTest + InteractivePauseAfterPersistTest |
| p14-04 | writeAll 轮询 taskId；按钮「最多 50 章」 |
| p14-05 | ApproveSpecificCheckpointTest；审核条按条列出 |
| p14-06 | 人物全字段、真典添加、CULTURE=文化、侧栏新建打开创建框 |

## UI e2e（2026-08-18，http://127.0.0.1:8080，DB data/ui-e2e/novel.db）

PASS: 刷新、空创建校验、取消创建、删除 QA 项目、进入债册春秋、连续写作文案/取消、侧栏新建开 Modal、五 Tab、世界观/人物全字段、真典添加（`【HUMAN_PATCH】e2e铁律：债册不得记活人阳寿`）、大纲、章节管理（雨夜账+当铺索债+批准）、质量评价按钮、CULTURE 译为文化。

SKIP: 写完全书确认、推进构思、写一章、全书评价（贵/会改书）。

债册春秋仍为 PAUSED_AT_CHECKPOINT，2 章。

## Next Steps

1. 从 `p15-01` 起 TDD：`POST /worldbuilding` + `POST /characters` + UI 添加。
2. 增量 WorldBuilder 禁止 `deleteKnowledgeEntriesByProject`。
3. 不要先加检测器。
