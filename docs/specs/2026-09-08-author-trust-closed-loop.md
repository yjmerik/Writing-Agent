# Spec: 作者信任闭环——保存一致性、计划约束与评审版本绑定

## 背景与动机

触发事件：`docs/reports/2026-09-05-ui-experience/体验报告.md`（2026-09-07，23 章全流程真实体验）。报告收敛出 4 个 P1 与 4 个 P2 共性问题，核心判断是"计划执行、版本保护、检查证据和长期记忆之间缺少可让作者信任的闭环"。只读探查已把前三组问题定位到确切代码：

1. **保存结果与 AI 处理不对应（报告 #2，P1）**：`ChapterRevisionService.doEdit` 在 `saveChapterRevision` 事务提交后同步调用 `refresh()`（派生数据重算，内部走 LLM 摘要）且无异常隔离——刷新失败时正文已永久保存，端点却抛异常，前端统一显示"应用失败"（`AgentRevisionDialog.tsx:79`），诱导作者重复提交。作者稿被覆盖的旁路：`ChapterPostProcessor.java:222` 等自动重写点用无版本条件的 `repository.saveChapter()` upsert 整对象覆盖，绕过乐观锁。
2. **已确认计划不能稳定约束生成（报告 #1，P1）**：`NovelOrchestrator.parseAndAppendVolume` 的 `startChapter` 形参完全未用，解析 LLM 文本章号零区间校验（41-48 错误章号的直接根因）；章节产物不记录所用计划，作者无从核对生成时点采用的是哪份计划。
3. **审核与当前稿版本脱节（报告 #3，P1）**：`continuity_issues` 与 `chapter_reviews` 均无版本绑定列；删场景后旧评语/旧问题仍以当前结论呈现，重写不关闭旧问题，高分被误当问题解决。

## 目标

1. 作者经 UI 的保存/应用：正文落库成功即返回成功语义；派生刷新失败不伪装成保存失败，且有事件与响应字段可区分"正文已保存 / 派生步骤待处理"。
2. 自动重写/后处理链对已存在章节的覆盖必须经版本门：作者稿在处理期间被修改时，保留作者稿并发出冲突事件，不再静默覆盖。
3. 卷计划章号区间可校验：解析出的章号落在本次请求区间外时拒绝入库、发出事件、走占位兜底；章节写作记录所用计划出处并可在 API 中核对。
4. 审核问题与评语绑定章节版本：质量报告中来自旧版本的条目可标记"已过期"，不与当前稿结论混排。

## 非目标

- 报告 #4（记忆事实分级/伏笔回收证据）、#5（检查材料范围）拆为 p32-06~09，本轮只登记不实现。
- 报告 #6（长任务可行动状态）、#7（预览/全文评价语义）、#8（布局导航）为 P2 前端可用性专项，不在本 spec。
- 不改文学质量类 prompt 策略；不改 `.st` 模板变量名。
- 不引入跨进程并发保证（SQLite 单进程约束不变）。

## 验收标准

- [ ] `mvn test -Dtest=ChapterRevisionRefreshIsolationTest`：编辑/应用时派生刷新抛异常，HTTP 仍 2xx，响应含 `derivedRefreshPending=true`，正文回读逐字一致，事件 `CHAPTER_REFRESH_FAILED` 已发。
- [ ] `mvn test -Dtest=AuthorDraftConflictProtectionTest`：章节处理链执行期间作者以新版本保存正文后，自动重写落库被拒绝，DB 中正文为作者稿，事件 `CHAPTER_AUTHOR_CONFLICT` 已发。
- [ ] `mvn test -Dtest=LayeredOutlineParsingTest`（扩展）：喂入章号 41-48 而请求区间 1-8 的卷文本，该卷不产生区间内错误章号条目，事件 `PLAN_VOLUME_RANGE_REJECTED` 已发；正常 1-8 解析回归不变。
- [ ] `mvn test -Dtest=ChapterPlanProvenanceTest`：新写章节持久化 `plan_ref`（卷号+synopsis id），GET `/api/projects/{id}/chapters/{index}` 响应含该字段。
- [ ] `mvn test -Dtest=ReviewVersionBindingTest`：问题/评语持久化 `chapter_version` 与版本指纹；章节升版后旧条目在质量报告中标记过期，新条目为当前。
- [ ] 受影响前端文案分级：apply 失败仅在"保存本身失败"时显示"应用失败"，刷新失败显示"修改已保存，但后续处理未完成"（`cd frontend && npm run build` 通过）。
- [ ] 每 feature：`mvn compile && mvn test` 全绿、`./scripts/e2e.sh` + `python3 scripts/compare-baseline.py` PASS（若基线 FAIL，展示新旧对比经人工确认后方可 `--bless`）。

## 影响面

- **涉及模块**: service/（ChapterRevisionService, AgentRevisionService）、orchestrator/（NovelOrchestrator, ChapterProduction, ChapterPostProcessor）、persistence/sqlite/（SchemaInitializer, NovelRepository）、domain/（ContinuityIssue, ChapterReview）、web/（ContentController, RevisionController）、frontend/src/components/
- **涉及 prompt 模板**: 无（p32-06~08 backlog 项才涉及 fact-harvest.st / foreshadowing-resolve-check.st）
- **数据库 schema 变更**: chapters +`plan_ref`（p32-04）；continuity_issues +`chapter_version`,`content_hash`；chapter_reviews +`chapter_version`,`content_hash`（p32-05）。均沿用 `SqliteSchemaInitializer` 既有 ADD COLUMN 幂等迁移模式，旧行 NULL 视为未知版本。
- **质量基线影响**: p32-02 改变自动重写落库路径（版本门可能拒绝一次重写），p32-03 改变解析结果；均需重跑 e2e replay 对比基线，必要时经人工确认 `--bless`。

## Feature 拆分

| id | name | 依赖 | acceptance | verify_cmd |
|----|------|------|------------|------------|
| p32-01 | 保存与派生刷新解耦 | [] | 上验收 1、6：refresh 失败保存仍成功、事件+响应字段+前端文案分级 | `mvn test -Dtest=ChapterRevisionRefreshIsolationTest` |
| p32-02 | 作者稿冲突保护 | [] | 上验收 2：后处理自动重写经版本门，冲突保留作者稿 | `mvn test -Dtest=AuthorDraftConflictProtectionTest` |
| p32-03 | 卷计划章号区间校验 | [] | 上验收 3：41-48 复现场景拒绝入库并发事件 | `mvn test -Dtest=LayeredOutlineParsingTest` |
| p32-04 | 章节记录计划出处 | [p32-03] | 上验收 4：plan_ref 落库并经 API 暴露 | `mvn test -Dtest=ChapterPlanProvenanceTest` |
| p32-05 | 审核产物绑定版本 | [] | 上验收 5：版本列落库、旧版本条目标记过期 | `mvn test -Dtest=ReviewVersionBindingTest` |
| p32-06 | StoryFact 证据分级与 WORLD_RULE 门 | [] | backlog：人物台词/推测不能直升 WORLD_RULE（引用报告 #4 复验标准） | `mvn test -Dtest=FactEvidenceGradingTest` |
| p32-07 | fact-harvest.st 收割规则收紧 | [p32-06] | backlog：模板不改变量名，FactHarvesterTest 通过，e2e+基线对比 | `mvn test -Dtest=FactHarvesterTest && ./scripts/e2e.sh` |
| p32-08 | 伏笔回收证据落库 | [] | backlog：resolve 必须携带证据文本，仅再提及不置 resolved | `mvn test -Dtest=ForeshadowEvidenceTest` |
| p32-09 | 伏笔超期提醒去重与当章优先 | [p32-08] | backlog：同一伏笔跨章不重复追加 issue，报告按当章/长期分区 | `mvn test -Dtest=ForeshadowIssueDedupTest` |

## 风险与备选方案

- **p32-01 与 `EditPassageReturnsBeforeHarvestTest` 语义冲突**：该测试要求直改路径同步等到派生数据完成。方案：只做"失败隔离"（catch + 事件 + pending 标记），不做异步化，同步等待语义不变。
- **p32-02 版本门拒绝重写导致正文/派生不一致**：冲突时整条后处理链应以作者稿为准重放或终止并标记 NEEDS_MANUAL_REVIEW，不允许半新半旧；实现时以 `DerivedWriteBoundary` 现有语义为准。
- **p32-03 误伤合法输出**：LLM 偶发在区间边缘差一章。方案：区间外即拒绝（作者复验标准是"阻止"，不是"纠正"），事件携带原始文本片段供排查。
- **回滚方式**：每 feature 独立分支独立合并，可单独 revert 特性提交。
