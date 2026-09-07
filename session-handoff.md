# 2026-09-07 LLM provider fallback 合并交接

本轮用户已授权合入main。提交范围为Phase30的12个fallback feature及对应实现、测试和文档；此前分支的44个已提交变更随快进合入。Phase27–29的14项工作区功能与模型配置尚未提交，继续保留在原工作区，不计入此提交。提交中的feature_list共212项completed，原工作区完整backlog共226项。

已实现命名provider、任务/默认有序路由、不可用分类、每候选一次预算、60秒冷却恢复、流式防拼接、实际模型计费与回放兼容。配置入口docs/llm-provider-fallback.md；设计docs/specs/2026-09-07-llm-provider-fallback.md。

开发验证：包含原工作区未提交功能的671项测试全绿（0失败/0错误/1跳过），init编译与前端构建PASS；独立本地双provider模拟通过；悬疑fixture回放3章10191字/54调用/0错误，compare-baseline PASS。此计数属于开发工作区，不代表去除其他未提交功能后的独立提交计数。合并过程还会在干净主分支检出中重新验证，结果记录在data/verification/llm-provider-fallback/merge-verification.json。

本次没有启用真实备用或重启运行中服务；main保留其原有默认模型配置（工作区另有尚未提交的Token Plan/Qwen配置）。所有原小说数据和其他未提交内容保持。下一步可按配置文档接入实际provider。本合并仅更新本地main，不推送远端。

以下是此前已提交的历史记录。

---

# Session Handoff

> 状态日期：2026-09-04。历史过程保留在 `progress.md` 与 Git 历史；本文只描述下一会话需要读取的当前事实。

## Current Objective

- **Goal**: 以产品一致性审计的浏览器红灯套件驱动下一轮 P0 → P1 → P2 修复。
- **Current status**: Phase 26 产品一致性审计修复已完成。p26-01 至 p26-21 全部 completed；14 项本期问题已修复并通过完整产品套件、文学回放、基线与 init。自动任务持续运行和 mode switch/resume 语义仍按用户决定延期。
- **Branch**: `codex/product-design-consistency-fixes`；仓库没有 Git remote/upstream。
- **Feature 台账**: `feature_list.json`，`current_phase=26`；Phase 26 共 21 项全部 completed，无 in-progress 项。
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

## Verification（2026-09-04）

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
- p26-12 RED：真实 Spring MVC + SQLite `ChapterDirectEditTest` 为 **7 tests / 6 failures / 0 errors**，四个新端点缺失并返回 500；事务故障用例只因端点不存在而偶然零写入。GREEN：新增项目全局下一章草稿、直接编辑、历史倒序和精确回退 API；expectedVersion、跨项目、标题/正文/500 字、活动工作与并发唯一胜者均在写入和派生刷新前校验。SQLite 条件 UPDATE current + INSERT pre-change snapshot 同事务提交，故障注入时二者均回滚；空白旧草稿可精确恢复。rewrite/passage 也经统一边界记录 `REWRITE`/`PASSAGE_EDIT`，直接编辑/回退复用既有向量重建和摘要/实体/伏笔/事实刷新。Review round 1 修复 checkpoint 章节重写绕过 owner 及派生数据旧版本逆序回写：checkpoint revision 现在统一取得项目工作权；修订/刷新经同一分片锁串行，直接编辑/回退响应前刷新当前版本，passage 后台任务先 dirty 摘要再以 chapter version 守卫。Review round 2 复现前一次慢收割使第二次 passage 保存阻塞约 5 秒；修复后 passage 持久化不等待旧派生任务，后台队列仍经分片锁与 chapter version 守卫按最新版本收敛。相关 focused **19/19**；JDK21 compile/full/init **567 tests / 0 failures / 0 errors / 1 skipped**，匹配 mystery replay 为 3 章、10,191 字、54 calls、0 errors/failed chapters，baseline PASS。manual aggregate **0/1/0**，唯一 chapterActions UI 留给 p26-13。
- p26-13 RED：真实浏览器 `manual-content-management` 为 **0 PASS / 1 XFAIL / 0 ERROR**，章节新增、编辑、历史、比较、回退及冲突处理均缺失。GREEN：新增与编辑使用本地草稿，提交目标绑定 chapterIndex/expectedVersion；历史可比较指定旧版本与当前正文并确认回退。模拟外部更新后陈旧保存收到 409，弹窗保持打开并重载服务端最新标题/正文，显示“章节已被更新，请重新确认”。strict 与 expect-known-failures 均为 **1 PASS / 0 XFAIL / 0 ERROR**，并已从 KNOWN_FAILURES 移除；frontend build、JDK21 compile/full/init PASS，仍为 **567 tests / 0 failures / 0 errors / 1 skipped**，27 prompts 与前端构建通过。
- p26-14 RED：`ContentRevisionAgentTest` testCompile 为 **23 个预期缺失符号**。GREEN：五类 TargetType 均渲染目标/当前 JSON/指令并返回严格 RevisionCandidate；输入 JSON、目标绑定、必填字段、单 JSON/无 fence/无尾随值及 structuredJson 对象均 fail loud，Agent 无仓储依赖。专属 **10/10**、含 Spring 接线 **11/11**；匹配 mystery replay 为 **3 章 / 10,191 字 / 54 calls / 0 errors / 0 chapter failures**，baseline PASS；manual browser **1/0/0**。JDK21 compile/full/init **577 tests / 0 failures / 0 errors / 1 skipped**，28 prompts 与前端构建通过。
- p26-15 RED：`AgentRevisionApiTest` 的 5 组真实 Spring MVC/SQLite 场景因预览路由不存在返回 500。GREEN：五类目标返回服务端 UUID preview、before/after 与 SHA-256 sourceVersion；应用重新读取并校验项目、目标身份、索引、活动写作和来源指纹，一次性消费。跨项目、陈旧、篡改身份、活动任务及重复应用均无部分写入；卷级仅修改所选未写卷标题/摘要且保持所有卷 UUID，章节/段落进入既有版本与派生刷新链。API **5/5**、联合 Agent **15/15**；matching mystery replay **3 章 / 10,191 字 / 54 calls / 0 errors / 0 chapter failures**，baseline PASS；manual browser **1/0/0**。JDK21 compile/full/init **582 tests / 0 failures / 0 errors / 1 skipped**，28 prompts 与前端构建通过。
- p26-16 RED：扩展后的 `manual-content-management` 为 **0 PASS / 1 XFAIL / 0 ERROR**，既有人工管理断言全绿而五类 Agent 入口缺失；Scenario 严格候选单测先为 **1 failure**。GREEN：统一对话框覆盖指令、生成、前后预览、确认和站内错误；世界观/人物/卷/章节/段落均有目标绑定入口，未保存本地草稿和含已写章节卷会禁用以避免覆盖，段落使用真实可访问 Button。真实浏览器执行一次预览取消和五类确认，逐项证明 preview/cancel 零写入、确认只改目标，strict **1 PASS / 0 XFAIL / 0 ERROR**。focused Scenario/Agent/API **23/23**；frontend build、JDK21 compile/full/init PASS：**583 tests / 0 failures / 0 errors / 1 skipped**，28 prompts 与前端构建通过。Scenario 适配仅供确定性测试，生产 Prompt/Agent/上下文未改，无 replay。
- p26-17 RED：真实 Spring MVC/SQLite `ProjectProgressViewTest` 为 **4 tests / 4 failures / 0 errors**，status/list 均缺暂停阶段、原因、目标章数与真实百分比。GREEN：服务端共享计算将初始设定/卷大纲审核映射到 PLANNING、章节审核映射到 WRITING；写作进度按 `floor(current*100/target)` 且封顶 100，3/12=25，缺偏好从领域默认读取 24。运行步骤和列表只消费服务端事实，列表展示 current/target 与百分比；自动模式文案不再承诺无人值守，执行行为未改。专属 **4/4**、相邻控制器 **12/12**、frontend build、browser-e2e **8/8**、JDK21 full/init 全部 PASS：**587 tests / 0 failures / 0 errors / 1 skipped**，28 prompts 与前端构建通过；无 replay。
- p26-18 RED：真实 ego-browser strict 为 **0 PASS / 1 XFAIL / 0 ERROR**，未评价提示缺失、运行态不可识别、失败使用原生 alert 且无站内错误/重试。GREEN：`QualityView` 明确四态，未评价和失败不显示零分/无问题，失败保留原因并可重试，成功零分显示完成标记。最终 strict **1 PASS / 0 XFAIL / 0 ERROR**，相邻两场景独立 strict 各 **1/0/0**，frontend build PASS；JDK21 full/init 为 **587 tests / 0 failures / 0 errors / 1 skipped**，28 prompts 与前端构建通过；无 replay。
- p26-19 RED：强化后的 `mobile-critical-path` strict 为 **0 PASS / 1 XFAIL / 0 ERROR**，复现 375x812 标题/操作挤压、侧栏无可访问触发器、段落编辑入口不可聚焦且键盘无效。GREEN：标题与操作纵向排列，页面横向溢出为 0；侧栏使用具名真实 Button 开合，正文段落使用显式编辑按钮，全部关键入口至少 44px。真实 CDP Enter/Space 验证 strict 与 expect-known-failures 各 **1 PASS / 0 XFAIL / 0 ERROR**，相邻桌面端 manual strict **1/0/0**；frontend build、JDK21 compile/full/init 全绿：**587 tests / 0 failures / 0 errors / 1 skipped**，28 prompts 与前端构建通过；无 replay。
- p26-20 RED：新 `user-copy-check` 非零退出并精确定位 4 处可见字符串：“人種伏笔”“人種”“人收”“章节阅读”。GREEN：统一为“种下伏笔”“回收”“章节管理”，P26-17 的诚实自动模式描述保持不变；静态门禁和 frontend build PASS，ego-browser manual strict **1 PASS / 0 XFAIL / 0 ERROR**。JDK21 compile/full/init 全绿：**587 tests / 0 failures / 0 errors / 1 skipped**，28 prompts 与前端构建通过；无 replay。
- p26-21：9 个已修复产品场景逐项 ego-browser strict 均 **1 PASS / 0 XFAIL / 0 ERROR**。首次聚合发现固定弹窗等待与 Scenario 序号硬编码造成额外假 XFAIL，仅修复测试夹具后完整套件为 **9 PASS / 2 XFAIL / 0 ERROR**，XFAIL 精确为 `auto-zero-intervention`、`interactive-to-auto`。匹配 mystery replay 为 **3 章 / 10,191 字 / 54 calls / 0 LLM errors / 0 chapter failures**，baseline PASS；JDK21 init 为 **587 tests / 0 failures / 0 errors / 1 skipped**，28 prompts 与前端构建通过。自动化范围关键词审计无新增实现，Standards/Spec 本地双轴审查无 in-scope finding。

## Non-blocking Observations

1. `baselines/golden.json` 与 `baselines/golden-wuxia.json` 的 `minEvaluationScore` 当前均为 null，release replay 也未带 `--evaluate`；现有 baseline 门禁守住章数/字数/错误，不自动守综合质量分。若要修复，必须按新需求协议另立 feature。
2. NovelOrchestrator（2329 行）和 ContextAssembler（1131 行）仍是复杂度热点；ChapterProduction 仍持有 Orchestrator 回引用。它们不是当前 blocker，但属于后续模块深化候选。
3. 前端主 bundle 约 1.19 MB（gzip 372 KB），构建通过但有分包警告。
4. 当前无 Git remote，`.github/workflows/ci.yml` 不会在远端实际触发；现有证据均为本地 release gate。
5. 默认 `e2e.sh` 会选字典序最新 fixture；当前会选 wuxia fixture，但默认 preset 为 mystery，造成请求指纹 miss。p26-02 按匹配 mystery fixture 完成质量回归；fixture 选择策略不在本 feature 范围。
6. `browser-e2e.sh` 的两阶段应用重启只按端口 readiness 判断；若旧服务未及时退出，第二个 harsh 服务会 bind 失败且 readiness 命中旧服务。p26-07 只记录并隔离验证，没有修改 harness；若后续单独修复，应新建 feature 并给 `kill_port/start_app` 增加端口空闲与新 PID 断言。

## Next Entry

- 本期没有剩余 feature；继续工作前应从新的用户需求按协议建立 spec 和独立 feature。
- 自动任务及其 mode switch/resume 生命周期需要架构和系统修复，明确不属于本期；只有用户另行批准架构专项后再规划，现有两个产品场景继续作为显式 XFAIL 保存。
- 新需求继续遵循：写 spec → 拆 1-3 文件 feature → 登记 `feature_list.json` → 实现与验证 → 更新三份状态文档。
- 本机代理可能劫持 localhost；脚本内 curl 保持 `--noproxy '*'`。ego-browser 内写文件使用绝对路径。
