# 体验报告 P1 问题修复：作者信任闭环（Phase 32）

## Spec 设计（需求拆分协议第 1 步）

新建 `docs/specs/2026-09-08-author-trust-closed-loop.md`（基于 TEMPLATE.md），背景引用 `docs/reports/2026-09-05-ui-experience/体验报告.md`。目标：①作者保存结果与 AI 后续步骤可区分、作者稿不被静默覆盖；②卷计划章号区间可靠、写作记录所用计划；③审核问题/评语绑定版本，改稿后旧问题可判过期。非目标：布局导航（P2-8）、长任务状态 UI（P2-6）本轮不做，仅登记。

## Feature 拆分与登记（协议第 2-3 步）

追加到 `feature_list.json`（phase 32，含 acceptance/verify_cmd/dependencies；第二批记 not-started）：

| id | 内容 | 依赖 | 报告问题 |
|----|------|------|---------|
| p32-01 | 保存与派生刷新解耦：`doEdit:100`/`createDraft:51` 的 refresh 失败不再让端点报错——try/catch + `CHAPTER_REFRESH_FAILED` 事件；apply/edit 响应区分"正文已保存/派生步骤待处理"，前端 `AgentRevisionDialog.tsx:79`、`InteractiveReview.tsx:376` 文案分级 | [] | #2 P1 |
| p32-02 | 作者稿冲突保护：`ChapterPostProcessor.java:222` 等无版本条件的自动 `saveChapter` 改为经 `ChapterRevisionService.editInOwnedWork`（REWRITE）或 `DerivedWriteBoundary` 版本门；冲突保留作者稿 + 事件 | [] | #2 P1 |
| p32-03 | `parseAndAppendVolume`（NovelOrchestrator.java:2380）启用未用的 `startChapter` 形参做区间校验：区间外条目拒绝入库、`PLAN_VOLUME_RANGE_REJECTED` 事件、走既有占位卷兜底；`LayeredOutlineParsingTest` 反射补 41-48 failing test | [] | #1 P1 |
| p32-04 | chapters 表加 `plan_ref` 列（沿用 ADD COLUMN 迁移）：写章时记录卷号+synopsis id，GET chapter API 暴露 | p32-03 | #1 P1 |
| p32-05 | `continuity_issues`/`chapter_reviews` 绑定 `chapter_version`+`chapter_version_hash`；质量报告对非当前版本条目标记"已过期"；重写/复检后旧 unresolved 问题不混入当前结论 | [] | #3 P1 |
| p32-06~08（backlog，本轮不实现） | StoryFact `evidence_grade`+WORLD_RULE 降级门；`fact-harvest.st` 收紧（不动变量名）；伏笔 `resolve_evidence` 落库（detector 丢弃的 reason）+ `descriptionsMatch` 改证据引用制 | | #4/#5 |
| p32-09（backlog） | 伏笔超期提醒每章去重（`ChapterProduction.java:198-220`），当章阻断项优先排序 | | #5 |

## 每个 feature 的执行循环（AGENTS.md 合并协议）

`codex/p32-0X-<slug>` 分支 → 先写 failing test（Bugfix gate）→ 实现 → `mvn compile && mvn test` + 该 feature verify_cmd → 提交（含 feature ID）→ `--ff-only` 合 main → 干净检出复验 → 登记 completed+evidence → 下一个。p32-02/03/06 类可能改变 replay 指纹：`./scripts/e2e.sh` + `compare-baseline.py` FAIL 时展示新旧对比，经你确认后 `--bless`。

## 关键文件

- `src/main/java/com/writing/novel/service/ChapterRevisionService.java`（01/02）
- `src/main/java/com/writing/novel/orchestrator/ChapterPostProcessor.java`、`NovelOrchestrator.java`、`ChapterProduction.java`（02/03/04）
- `src/main/java/com/writing/novel/persistence/sqlite/SqliteSchemaInitializer.java`、`SqliteNovelRepository.java`（04/05）
- `src/main/java/com/writing/novel/domain/ContinuityIssue.java`、`ChapterReview.java`、`web/ContentController.java`（05）
- `frontend/src/components/AgentRevisionDialog.tsx`、`InteractiveReview.tsx`（01）
- 测试：`ChapterDirectEditTest`、`AgentRevisionApiTest`、`LayeredOutlineParsingTest` 为既有锚点
- 会话结束更新 `feature_list.json`/`progress.md`/`session-handoff.md`（当天日期）

## 验证

每 feature 独立 `mvn test -Dtest=<对应类>`；最终 `./init.sh` 全绿 + 受影响 feature 的 e2e/基线。风险：refresh 异步化与既有 `EditPassageReturnsBeforeHarvestTest` 的同步期望可能冲突——实现时以该测试语义为准调整隔离层级。