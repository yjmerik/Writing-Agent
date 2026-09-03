# Session Handoff

> 状态日期：2026-09-03。历史过程保留在 `progress.md` 与 Git 历史；本文只描述下一会话需要读取的当前事实。

## Current Objective

- **Goal**: 以产品一致性审计的浏览器红灯套件驱动下一轮 P0 → P1 → P2 修复。
- **Current status**: Phase 26 正在按产品一致性审计逐项修复。p26-01 至 p26-12 已完成。章节新增草稿、直接编辑、历史查询和回退 API 已接入不可变历史底座；直接编辑/回退以 expectedVersion 防陈旧覆盖，SQLite 原子保存 current+pre-change snapshot，既有 rewrite/passage 也进入统一版本边界。所有章节 mutation 使用共享项目工作权；直接编辑与回退复用既有向量和后处理刷新路径。`manual-content-management` 的 world/character/outline 子项全绿，aggregate 仅因 p26-13 章节新增、正文编辑和版本 UI 尚未完成而预期 XFAIL。既有 checkpoint merge、服务端动作矩阵、自动任务持续运行和 mode switch/resume 语义均未改动。
- **Branch**: 当前 Phase 26 修复分支；仓库没有 Git remote/upstream。
- **Feature 台账**: `feature_list.json`，`current_phase=26`；p26-01 至 p26-12 completed，下一项为 p26-13。
- **Current spec**: `docs/specs/2026-09-02-product-audit-browser-e2e.md` 与 `.superpowers/sdd/2026-09-03-product-design-consistency-remediation/` 任务简报。
- **Product audit**: `docs/audits/2026-09-02-product-design-consistency-audit.md`（P0 3 项 / P1 8 项 / P2 5 项）。

## Current Architecture

1. **编排**：NovelOrchestrator 管项目状态与章节索引；ChapterProduction 管 Context→Write→Quality→Rewrite；ChapterPostProcessor 管正文落库后的 10 阶段处理。
2. **可靠性**：ProjectWorkCoordinator 以 owner-token 保证单项目单 worker；post_process_ledger 支持崩溃后只续跑未完成阶段。
3. **长程记忆**：ContextAssembler 汇总故事原旨、StoryCanon、全书快照/分层摘要、近期正文、向量检索、实体关系、叙事线索和伏笔。
4. **真典 seam**：业务层只依赖 StoryCanon；HUMAN_PATCH 5 章保护、ACTIVE 唯一性与 supersede 审计链由适配器实现。
5. **状态语义**：CONCEIVING/PLANNING/VOLUME_DONE 只允许 ADVANCE；WRITING+大纲+无 pending 只允许 WRITE_CHAPTER/WRITE_ALL；NEEDS_REVISION 只允许 FINAL_AUDIT；PAUSED_AT_CHECKPOINT/FINAL 无主操作。pending checkpoint 关闭所有写作动作；章节 NEEDS_MANUAL_REVIEW 会冻结后续写作，resolve-review 后恢复 DONE。

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
- p26-05 RED：`approve-keeps-chapter-visible` strict 为 0 PASS / 1 XFAIL / 0 ERROR；批准前正文可见，批准后消失且 `bodyLoading=true`。GREEN：刷新按 chapterIndex/version 保留有效缓存，选中章版本变化先淘汰再显式重读，批准强制重读当前章且旧正文保留到新响应；失败显示站内错误与重试，spinner 收敛。独立章节重写继续同步 body/meta。strict 为 1 PASS / 0 XFAIL / 0 ERROR，仅移除该项 KNOWN_FAILURES；frontend build、JDK21 `mvn compile`、`mvn test` 与 `./init.sh` 均 PASS（504 tests / 0 failures / 0 errors / 1 skipped）。
- p26-05 基线 follow-up：`chapter-lazy-loading` RED 为首章请求 b1=2，非 force 读取复用 in-flight 后 browser 8/8，提交 `e4b20bb`；SQLite metadata focused RED 为 full.version=3/meta.version=1，映射 version 后 focused 4/4、approve/standalone strict 各1/1、browser 8/8、full/init 通过，提交 `2b92249`。
- p26-06 RED：policy 类/枚举缺失导致 testCompile 失败；endpoint 回归初始 4 失败，覆盖 status 缺字段、WRITING `/run` 隐式写章、CONCEIVING write-all 创建任务、write-chapter 留 FAILED 任务。独立标准审查再发现 `/write-chapter` 门禁至启动的 TOCTOU，确定性 latch 回归 RED 为缺少原子 gate overload 的 testCompile 失败；GREEN 在取得 `ProjectWorkCoordinator` owner 后、保存 task 前按 repository 真相二次门禁，暂停先发生时不创建 task、不进入 orchestrator。最终 focused **35/35**，四个非法 endpoint 无 task/orchestrator 调用；approve/standalone strict 各 **1/1**，browser **8/8**，frontend build PASS，JDK21 full/init **522 tests / 0 failures / 0 errors / 1 skipped**，27 prompt 与前端构建 PASS。
- p26-07 RED：完整 INTERACTIVE 构思链路的 `InitialSetupCheckpointPayloadTest` 为 **1 failure / 0 errors**，AFTER_INITIAL_SETUP payload 实际为 null；`initial-setup-summary` strict 为 **0 PASS / 1 XFAIL / 0 ERROR**，三段真实摘要、两次跳转与页签选中断言均失败。初版 GREEN 后 review round 1 再以 **3 failures / 0 errors** 复现超长 concept 吞掉 outline、空 premise 生成空摘要、缺少真实来源却仍创建 checkpoint；异常旧 payload strict 为 **0 PASS / 1 XFAIL / 0 ERROR**，非字符串 React child 令卡片崩溃。最终 GREEN：checkpoint 以 Jackson 编码三段持久化内容摘要与 `world`/`outline` links；每段按 code point 限 300 且 surrogate 完整，双来源均保留、单来源可用完整预算。premise 缺失时回退已持久化卷标题/summary，仍空则在状态改变和 checkpoint 创建前 fail-closed 并保持 `CONCEIVING`。审核卡只渲染运行时确认为 string 的摘要，并用原生按钮切 tab；target 白名单防止异常 payload 写入无效 tab。focused checkpoint 回归 **17/17**；含异常 payload 的 `initial-setup-summary` 与 `checkpoint-card-targeting` strict 各 **1 PASS / 0 XFAIL / 0 ERROR**，未改弱 `KNOWN_FAILURES`；frontend build PASS。browser 产品断言分段 **8/8**：完整脚本前 7 项 PASS，第 8 项两阶段重启因旧服务占端口是假失败（base `13f745c` 同样复现，日志为 `Port was already in use`），独立确认 `harsh=true` 新服务后原断言 PASS。JDK21 full/init **525 tests / 0 failures / 0 errors / 1 skipped**，27 prompt 与前端构建 PASS。Agent/prompt/上下文/产出未改，无 replay 质量门禁。
- p26-08 RED：真实 HTTP/SQLite `WorldCharacterFullEditTest` 为 **4 tests / 4 failures / 0 errors**，复现世界观 type 丢失、人工 ITEM/INVALID 放行及副作用、跨项目路径可修改目标。GREEN：人工 world allowlist 精确为 9 类，更新持久化 type/name/content，world/character 目标均校验 projectId；ITEM 只保留系统/存量 enum 与中文展示。人物八字段改为受控整卡保存，世界观新增/编辑共用中文 Select，失败保留 draft。focused ownership/canon/create **14/14 PASS**；`manual-content-management --expect-known-failures` 为 **0 PASS / 1 XFAIL / 0 ERROR**，worldSave 与 characterSave 的 UI 保存、API 持久化、整页刷新子断言全部 true，aggregate 仅保留大纲/章节预期红灯；`initial-setup-summary`、`checkpoint-card-targeting` strict 各 **1 PASS / 0 XFAIL / 0 ERROR**。frontend build、JDK21 compile/full/init 全绿：**529 tests / 0 failures / 0 errors / 1 skipped**，27 prompt 通过；无 Agent/prompt/上下文改动，不需要 replay。
- p26-08 review round 1：扩展后的真实 HTTP/SQLite RED 为 **7 tests / 3 failures / 0 errors**，复现 world/character rename 的旧 subject 继续 ACTIVE、人物整卡状态只剩 growthArc。GREEN 在实体更新前捕获相关 ACTIVE，新断言以 `supersedeFact` 建立跨 subject old→new 审计链，清空转 RETIRED；人物由更新后完整实体一次组合六个 state 字段，relationship 独立写，重复/partial 后每类每主体恰好 1 ACTIVE。`canon.recall` 与 `RelevanceGateway` 均不再输出旧主体；focused **17/17**、frontend、manual aggregate 子断言、两个 strict 场景均通过，JDK21 full/init **532 tests / 0 failures / 0 errors / 1 skipped**，27 prompts 与前端构建 PASS。
- p26-09 RED：真实 HTTP/SQLite `OutlineStructureEditTest` 为 **8 tests / 8 failures / 0 errors**，复现旧 merge 无法删除/跨卷移动/完整替换，0/-1 边界残留旧章，duplicate/descending/nonpositive、已写前缀替换与零章节大纲全部被错误接受。GREEN：`OutlineStructureService` 在任何写入前验证归属、卷章顺序和完整已写前缀，构造 detached replacement 后只调用一次 `CanonPatchService.replaceOutline`；未写结构可增删、移动、重编号并保存空卷，总纲不变不写冗余剧情承诺。旧 `patchOutline` merge-only 路径不变。focused **25/25 PASS**，其中新测试 **10/10**；manual aggregate **0 PASS / 1 XFAIL / 0 ERROR** 且失败只来自后续 UI；JDK21 compile/full/init **542 tests / 0 failures / 0 errors / 1 skipped**，27 prompts 与前端生产构建 PASS。
- p26-09 review round 1：扩展真实服务/SQLite 回归先为 **14 tests / 4 failures / 0 errors**，分别命中 writer/第二 PUT 的 TOCTOU、已写卷 UUID 被重建、真典 INSERT 失败留下已提交大纲。GREEN 在结构服务内以项目 owner 包围 boundary read→commit→bump；已写卷章复制可信 UUID 与完整扩展字段且忽略 payload ID；SQLite 单事务替换 outline、supersede 旧 ACTIVE PLOT_COMMITMENT 并插入 HUMAN_PATCH，触发器在新 fact INSERT 中断时 outline/facts/revision 全不变。focused **32/32 PASS**；manual aggregate **0/1/0**；JDK21 compile/full/init **546 tests / 0 failures / 0 errors / 1 skipped**，27 prompts 与前端构建 PASS。
- p26-09 review round 2：`startConceiving` 面对 `outline-edit-*` owner 的服务/HTTP RED 为 **7 tests / 2 failures / 0 errors**，实际 null 返回并导致 `/run` 500。GREEN 改为先原子 acquire，失败方仅在 owner 是 repository 中同项目 CONCEIVE task 时幂等返回，否则明确 409；不创建 task、不调用 orchestrator且原 owner 保持。构思重复启动旧语义继续通过；工作权 focused **33/33**，JDK21 compile/full/init **548 tests / 0 failures / 0 errors / 1 skipped**，27 prompts 与前端构建 PASS。

- p26-10 RED：真实浏览器 seed 为卷1三章（ch1 已写，ch2/ch3 未写），`manual-content-management` 为 **0 PASS / 1 XFAIL / 0 ERROR**；新增卷/章、删除卷/章、保存与锁定 DOM 子项全 false。GREEN：完整 Outline 本地草稿提供未写卷章增删、上下移动与字段编辑，结构操作后保持已写章号并全局重新编号未写项；已写卷章有明确锁定语义且结构按钮 disabled。真实浏览器完成新增章→上移→删除原 ch2，首次保存被真实服务端 duplicate 校验拒绝并显示站内错误，原草稿随后成功重试；服务端生成 UUID 后的完整有序序列与整页刷新 API 完全一致，刷新 DOM 字段一致。manual aggregate 保持 **0 PASS / 1 XFAIL / 0 ERROR**，world/character/outline 全 true，仅 chapterActions false；两个邻近 strict 各 **1/0/0**。frontend build、JDK21 compile/full/init PASS：**548 tests / 0 failures / 0 errors / 1 skipped**，27 prompts 与前端构建 PASS。
- p26-10 review round 1：真实浏览器改用两卷 written boundary，并在删除前单独执行“新增→上移→立即保存→整页刷新精确比对”。强化 RED 命中 `addBoundary.earlyDisabled`、`pendingLocked.inputs/buttons/premiseSaveLocked`、`premiseDraftRetained` 五项 false；移动保存/刷新顺序两项已 true。GREEN 仅允许最后一个含已写章的卷及其后卷新增章；结构 PUT pending 时锁定全部结构字段/动作、刷新、重规划和两个保存按钮，总纲文本仍可继续编辑，响应不覆盖总纲草稿且 persisted premise 保持旧值。增强 manual aggregate 为 **0/1/0** 且 outline/world/character 全 true，仅 future chapterActions false；两个 strict 各 **1/0/0**；frontend build、JDK21 compile/full/init **548 tests / 0 failures / 0 errors / 1 skipped**。

- p26-11 RED：`ChapterVersionHistoryTest` testCompile 为 **29 个预期缺失符号**（领域类型和三个仓储契约不存在）。GREEN：不可变八字段 ChapterVersion，source 为 String，createdAt 为 Instant；SQLite 新表全部业务列 NOT NULL、项目+章号+版本 UNIQUE，普通 INSERT 拒绝重复且不覆盖，查询倒序/精确映射；fresh schema 与已有 legacy chapters 数据迁移均通过。新测试 **6/6**，focused 三套 **18/18**，JDK21 compile/full/init **554 tests / 0 failures / 0 errors / 1 skipped**，27 prompts 与前端构建 PASS。无产品入口，未跑浏览器/replay；未提前接入 p26-12 API 或章节变更路径。
- p26-11 review round 1：强化 RED 为 **7 tests / 2 failures / 0 errors**，证明 SQLite `id TEXT PRIMARY KEY` 的 `notnull=0` 且接受 null-id。改为 `id TEXT NOT NULL PRIMARY KEY` 后全部 8 列均为 NOT NULL，真实 null-id INSERT 失败且无残留；focused **19/19**，JDK21 compile/full/init **555 tests / 0 failures / 0 errors / 1 skipped**，27 prompts 与前端构建 PASS。
- p26-12 RED：真实 Spring MVC + SQLite `ChapterDirectEditTest` 为 **7 tests / 6 failures / 0 errors**，四个新端点缺失并返回 500；事务故障用例只因端点不存在而偶然零写入。GREEN：新增项目全局下一章草稿、直接编辑、历史倒序和精确回退 API；expectedVersion、跨项目、标题/正文/500 字、活动工作与并发唯一胜者均在写入和派生刷新前校验。SQLite 条件 UPDATE current + INSERT pre-change snapshot 同事务提交，故障注入时二者均回滚；空白旧草稿可精确恢复。rewrite/passage 也经统一边界记录 `REWRITE`/`PASSAGE_EDIT`，直接编辑/回退复用既有向量重建和摘要/实体/伏笔/事实刷新。新测试 **8/8**、四套 focused **13/13**；JDK21 compile/full/init **563 tests / 0 failures / 0 errors / 1 skipped**，匹配 mystery replay 为 3 章、10,191 字、54 calls、0 errors/failed chapters，baseline PASS。manual aggregate **0/1/0**，唯一 chapterActions UI 留给 p26-13。

## Non-blocking Observations

1. `baselines/golden.json` 与 `baselines/golden-wuxia.json` 的 `minEvaluationScore` 当前均为 null，release replay 也未带 `--evaluate`；现有 baseline 门禁守住章数/字数/错误，不自动守综合质量分。若要修复，必须按新需求协议另立 feature。
2. NovelOrchestrator（2329 行）和 ContextAssembler（1131 行）仍是复杂度热点；ChapterProduction 仍持有 Orchestrator 回引用。它们不是当前 blocker，但属于后续模块深化候选。
3. 前端主 bundle 约 1.19 MB（gzip 372 KB），构建通过但有分包警告。
4. 当前无 Git remote，`.github/workflows/ci.yml` 不会在远端实际触发；现有证据均为本地 release gate。
5. 默认 `e2e.sh` 会选字典序最新 fixture；当前会选 wuxia fixture，但默认 preset 为 mystery，造成请求指纹 miss。p26-02 按匹配 mystery fixture 完成质量回归；fixture 选择策略不在本 feature 范围。
6. `browser-e2e.sh` 的两阶段应用重启只按端口 readiness 判断；若旧服务未及时退出，第二个 harsh 服务会 bind 失败且 readiness 命中旧服务。p26-07 只记录并隔离验证，没有修改 harness；若后续单独修复，应新建 feature 并给 `kill_port/start_app` 增加端口空闲与新 PID 断言。

## Next Entry

- 下一步为 p26-13：消费 p26-12 API，实现章节新增、正文编辑、版本记录、比较与回退 UI；不扩展自动任务、mode switch/resume 或相邻架构。
- 剩余本期红灯聚焦人工内容管理、移动端与文案一致性；按用户指示，自动任务及其 mode switch/resume 生命周期需要架构和系统修复，不在本期计划范围。
- 新需求继续遵循：写 spec → 拆 1-3 文件 feature → 登记 `feature_list.json` → 实现与验证 → 更新三份状态文档。
- 本机代理可能劫持 localhost；脚本内 curl 保持 `--noproxy '*'`。ego-browser 内写文件使用绝对路径。
