# Session Handoff

> 状态日期：2026-09-03。历史过程保留在 `progress.md` 与 Git 历史；本文只描述下一会话需要读取的当前事实。

## Current Objective

- **Goal**: 以产品一致性审计的浏览器红灯套件驱动下一轮 P0 → P1 → P2 修复。
- **Current status**: Phase 26 正在按产品一致性审计逐项修复。p26-01 至 p26-04 已完成：审核操作先校验目标归属/PENDING，初始设定与卷大纲的修改意见会生成 detached candidate，校验后事务替换真实持久化内容；失败保留原内容与 `PENDING`。世界观替换先索引独立候选 ID，再提交 DB，成功后清旧向量、bump memory revision；StoryCanon 保留 HUMAN_PATCH supersede 审计链并保护派生时代锚点。连续性 checkpoint 保持 status-only 语义。审核卡弹窗现显式绑定被点击的 checkpoint 与动作，成功结果持久展示该卡描述、目标、真实前后摘要和版本。章节卡重写则使用独立 chapter target，成功更新正文缓存并显示真实版本变化，失败保留弹窗及行内错误。产品浏览器既知失败集合已移除 `initial-setup-modify`、`volume-outline-modify`、`checkpoint-card-targeting`、`standalone-chapter-rewrite`。
- **Branch**: 当前 Phase 26 修复分支；仓库没有 Git remote/upstream。
- **Feature 台账**: `feature_list.json`，`current_phase=26`；p26-01 至 p26-04 completed，下一项为 p26-05。
- **Current spec**: `docs/specs/2026-09-02-product-audit-browser-e2e.md` 与 `.superpowers/sdd/2026-09-03-product-design-consistency-remediation/` 任务简报。
- **Product audit**: `docs/audits/2026-09-02-product-design-consistency-audit.md`（P0 3 项 / P1 8 项 / P2 5 项）。

## Current Architecture

1. **编排**：NovelOrchestrator 管项目状态与章节索引；ChapterProduction 管 Context→Write→Quality→Rewrite；ChapterPostProcessor 管正文落库后的 10 阶段处理。
2. **可靠性**：ProjectWorkCoordinator 以 owner-token 保证单项目单 worker；post_process_ledger 支持崩溃后只续跑未完成阶段。
3. **长程记忆**：ContextAssembler 汇总故事原旨、StoryCanon、全书快照/分层摘要、近期正文、向量检索、实体关系、叙事线索和伏笔。
4. **真典 seam**：业务层只依赖 StoryCanon；HUMAN_PATCH 5 章保护、ACTIVE 唯一性与 supersede 审计链由适配器实现。
5. **状态语义**：项目 FINAL 全写锁；NEEDS_REVISION 只允许修订后 retryFinalAudit；章节 NEEDS_MANUAL_REVIEW 会冻结后续写作，resolve-review 后恢复 DONE。

## Completed Baseline

- `feature_list.json`：Phase 1-23 及 legacy 条目共 177 个，全部 completed；Phase 23 的 29 个 production-readiness feature 全部完成。
- 2026-08-31 release gate：Maven 全量测试、前端构建、replay 基线、reliability E2E、400 章 soak、ego-browser E2E 六门禁全部 PASS，manifest 位于 `data/release/manifest.json`。
- 400 章 soak：400 章连续唯一、约 105 万字、零非终态章节、零重复 ACTIVE 真典键、零任务重叠、零 LLM 网络调用，章节导航 11ms。
- reliability E2E：crash-restart / stale-worker-409 / manual-review / canon-protection 四场景全部 PASS。
- 真实 LLM 金丝雀：mystery 3 章 10,191 字、WUXIA 3 章 11,308 字，均 0 LLM 错误、0 章节失败；WUXIA 规则断言无修仙规则污染。

## Verification（2026-09-03）

- p26-01 RED：`mvn test -Dtest=CheckpointTargetValidationTest` 为 2 failures / 0 errors（跨项目、已处理 checkpoint 实际均接受为 HTTP 200）。
- p26-01 focused GREEN：`mvn test -Dtest=CheckpointTargetValidationTest,ApproveSpecificCheckpointTest` 为 3 tests / 0 failures / 0 errors。
- p26-01 browser：`./scripts/product-audit-e2e.sh --case checkpoint-card-targeting --expect-known-failures` 为 0 PASS / 1 XFAIL / 0 ERROR（卡片绑定保留给 p26-03）。
- p26-02 RED：focused 首跑因 `reviseCheckpoint`/`replaceKnowledgeEntries` 缺失而 testCompile 失败；加入 Agent candidate 零写入断言后仍有 7 个 missing symbol。
- p26-02 RED 补充：KnowledgeBaseService 替换测试先缺 `replaceEntries`（2 errors）；canon/controller 闭环测试先缺 `resumeAfterCheckpointRevision`（2 errors）。
- p26-02 GREEN：初始实现 routing/controller/KB/Agent focused 为 20 tests / 0 failures / 0 errors；checkpoint/Scenario/StoryCanon 回归为 13 tests / 0 failures / 0 errors。Review fix 的四轮 RED→GREEN 覆盖候选 embedding/索引失败与 ID 冲突、同主体 SUPERSEDED 审计链、连续性 status-only 路由、KnowledgeEntry 同名时代锚点保护；最终 routing/controller/KB/Agent/双 StoryCanon contract focused 为 46 tests / 0 failures / 0 errors。SQLite rollback 覆盖世界观与大纲原内容保留；候选向量先建且失败清理，DB 失败保留旧 DB/向量，成功后清旧向量并 bump revision；同主体新事实为 HUMAN_PATCH ACTIVE 且旧事实带 supersededBy；候选或推进失败均保持 checkpoint PENDING，内容修订成功响应含 revision。
- p26-02 browser：`initial-setup-modify` 与 `volume-outline-modify` strict 各为 1 PASS / 0 XFAIL / 0 ERROR，并已从 `KNOWN_FAILURES` 精确移除。
- p26-02 质量：默认 `e2e.sh` 因选中 wuxia fixture 与 mystery 请求不匹配而 fail-fast（diagnose：1 LLM_ERROR / 0 calls）；指定匹配的 `canary-mystery-20260831` fixture 后 3 章、10,191 字、54 calls、0 errors、0 chapter failures，baseline PASS。
- 全量：`mvn test` 与 `./init.sh` 为 504 tests / 0 failures / 0 errors / 1 skipped；init 还通过 27 个 prompt 与前端生产构建检查。
- p26-03 RED：强化 `checkpoint-card-targeting` strict 为 0 PASS / 1 XFAIL / 0 ERROR：操作较早卡实际处理最新卡（`firstHandled=false`、`latestUntouched=false`），且页面没有成功摘要。GREEN：strict 为 1 PASS / 0 XFAIL / 0 ERROR；较早卡唯一被处理、最新卡仍 PENDING，卡片 type/payload 与成功 Alert 的所点描述、真实 target/before/after summaries 均可见。仅移除该场景 KNOWN_FAILURES；frontend build PASS。JDK21 下 `mvn test` 与 `./init.sh` 均为 504 tests / 0 failures / 0 errors / 1 skipped。
- p26-04 RED：`standalone-chapter-rewrite` strict 为 0 PASS / 1 XFAIL / 0 ERROR；弹窗打开但无法提交（`submitted=false`、`modalClosed=false`、版本 v3→v3、正文未变）。GREEN：chapter target 保存 `chapterIndex` 与 `sourceVersion`，独立提交 `rewriteChapter`，成功更新元数据/正文缓存、关闭并 reset；失败保留弹窗且行内错误。strict 为 1 PASS / 0 XFAIL / 0 ERROR，断言提交、关闭、v3→v4、正文变化与页面新正文可见；仅移除该项 KNOWN_FAILURES。frontend build 与 JDK21 `mvn compile`、`mvn test`、`./init.sh` 均 PASS（504 tests / 0 failures / 0 errors / 1 skipped）。
- p26-04 review round 1：仅强化 E2E。重写前明确读取 checkpoints 并要求 `pendingBefore=0`；页面验证改为重写正文可见且旧 `【重写前来源标记】` 不存在，避免新旧正文共享前缀导致的假阳性。严格场景为 1 PASS / 0 XFAIL / 0 ERROR；前端 build、JDK21 `mvn test` 和 `./init.sh` 均 PASS（504 tests / 0 failures / 0 errors / 1 skipped）。

## Non-blocking Observations

1. `baselines/golden.json` 与 `baselines/golden-wuxia.json` 的 `minEvaluationScore` 当前均为 null，release replay 也未带 `--evaluate`；现有 baseline 门禁守住章数/字数/错误，不自动守综合质量分。若要修复，必须按新需求协议另立 feature。
2. NovelOrchestrator（2329 行）和 ContextAssembler（1131 行）仍是复杂度热点；ChapterProduction 仍持有 Orchestrator 回引用。它们不是当前 blocker，但属于后续模块深化候选。
3. 前端主 bundle 约 1.19 MB（gzip 372 KB），构建通过但有分包警告。
4. 当前无 Git remote，`.github/workflows/ci.yml` 不会在远端实际触发；现有证据均为本地 release gate。
5. 默认 `e2e.sh` 会选字典序最新 fixture；当前会选 wuxia fixture，但默认 preset 为 mystery，造成请求指纹 miss。p26-02 按匹配 mystery fixture 完成质量回归；fixture 选择策略不在本 feature 范围。

## Next Entry

- 下一步为 p26-05：审批后章节正文加载收敛；它仍是独立浏览器既知失败，勿与已完成的独立章节重写混改。
- 后续红灯还有独立章节重写、交互切自动、审核后正文稳定、人工管理、移动端；自动零干预需单独设计持续运行生命周期。
- 新需求继续遵循：写 spec → 拆 1-3 文件 feature → 登记 `feature_list.json` → 实现与验证 → 更新三份状态文档。
- 本机代理可能劫持 localhost；脚本内 curl 保持 `--noproxy '*'`。ego-browser 内写文件使用绝对路径。
