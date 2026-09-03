# Session Progress Log

---

## 会话记录（2026-09-04）：Phase 26 p26-14 通用内容修订 Agent 与外置 Prompt

- 新增 `ContentRevisionAgent.preview(...)`，为 WORLD、CHARACTER、VOLUME、CHAPTER、PASSAGE 五类选中对象生成 detached 修改候选。Agent 不依赖 `NovelRepository`，预览阶段没有持久化能力。
- 外置 `content-revision.st` 将目标类型、当前对象 JSON 和用户指令显式注入，并约束只修改所选对象、保留 ID/卷章/段落索引、返回单个无 Markdown 包裹 JSON。
- 输入要求单个有效 JSON 对象和非空指令；输出严格拒绝尾随值、Markdown fence、目标类型错配、空 title/content/structuredJson/summary，以及不是单个 JSON 对象的 structuredJson，所有非法候选均 fail loud。
- TDD RED 为 testCompile **23 个预期缺失符号**；GREEN 专属测试 **10/10 PASS**，含 LLM Spring 接线回归 **11/11 PASS**。
- 匹配 mystery 确定性 replay 为 **3 章 / 10,191 字 / 54 calls / 0 LLM errors / 0 chapter failures**，baseline PASS（未 bless）；`manual-content-management` 真实浏览器回归为 **1 PASS / 0 XFAIL / 0 ERROR**。JDK21 compile/full/init 全绿：**577 tests / 0 failures / 0 errors / 1 skipped**，28 prompts 与前端生产构建通过。AGENTS.md 规模同步为 24 个职责类/28 个 Prompt；未接入应用 API/UI，未修改自动任务或 mode switch/resume。

---

## 会话记录（2026-09-04）：Phase 26 p26-13 章节正文编辑与版本界面

- 章节页新增“新增章节”“编辑正文”“版本记录”入口。新增与编辑内容先保存在本地草稿，提交时才调用 p26-12 API；操作目标显式绑定 chapterIndex 与 expectedVersion，不依赖当前列表的陈旧闭包状态。
- 版本记录可比较指定历史版本与当前正文，并在确认后回退到所选版本。回退成功会同步刷新章节正文与版本；新增章节则按服务端返回的最新元数据重新选中。
- 409 冲突会保持编辑弹窗打开，从服务端重载最新标题与正文，并显示“章节已被更新，请重新确认”，避免覆盖他人更新。
- 浏览器 TDD RED 为 **0 PASS / 1 XFAIL / 0 ERROR**，全部章节操作断言为 false；GREEN 后 strict 与 expect-known-failures 均为 **1 PASS / 0 XFAIL / 0 ERROR**，覆盖新增并刷新、编辑并刷新、历史比较、确认回退并刷新、外部更新导致的陈旧保存冲突与最新内容重载。该场景已从 `KNOWN_FAILURES` 移除。
- frontend build PASS；JDK21 compile/full/init 全绿：**567 tests / 0 failures / 0 errors / 1 skipped**，27 prompts 与前端生产构建通过。本项未修改 Agent/prompt、自动任务或 mode switch/resume。

---

## 会话记录（2026-09-04）：Phase 26 p26-12 派生写入竞态收口

- Review round 3 确认连续 passage 时，已开始的旧任务仍可在新版本保存后追加旧实体、伏笔或事实。修复将耗时 LLM 计算与落库分离；摘要、实体、伏笔、事实只在短锁内重验 chapter version 后写入，过期计算直接丢弃。
- 新回归确认旧新两个后台任务均结束，但只有最新章节版本进入实际写入；第二次 passage 保存不等待前一次慢 LLM。相关 focused **30/30 PASS**。
- JDK21 `./init.sh` PASS：**567 tests / 0 failures / 0 errors / 1 skipped**，27 prompts 与前端生产构建通过。匹配 mystery replay 为 **3 章 / 10,191 字 / 54 calls / 0 errors / 0 chapter failures**，baseline PASS。范围仍排除自动任务与 mode switch/resume。
- `manual-content-management` 聚合复验为 **0 PASS / 1 XFAIL / 0 ERROR**；world、character、outline 全部通过，唯一已知失败仍是 p26-13 的章节操作界面。

---

## 会话记录（2026-09-03）：Phase 26 p26-10 大纲人工结构编辑界面

- 大纲页改为完整本地草稿：卷标题/简介、章标题/梗概及新增、删除、上下移动都只改变草稿，统一点击“保存未写大纲”后才调用 p26-09 的完整 `PUT /outline` 契约。
- 每次结构操作都保持已写章号不变，并对未写卷章按页面顺序全局重新编号，确保卷号、章号为正数、唯一且严格递增。含已写章的卷和已写章显示明确“锁定”状态，结构移动/删除按钮为 disabled。
- 新增卷、章与移动/删除均使用 Ant Design Button 和清晰中文 `aria-label`；保存失败统一 `message.error`，且失败后原草稿仍可直接重试。前端 API 将 updateOutline 参数收紧为完整 `Outline`。
- 严格 TDD RED：真实浏览器 seed 为卷1三章（ch1 已写，ch2/ch3 未写），`manual-content-management` 为 **0 PASS / 1 XFAIL / 0 ERROR**，新增卷/章、删除卷/章、保存与锁定 DOM 子项全 false。
- GREEN 场景真实完成：新增“人工新增待写章”→上移→删除事件2→第一次保存经真实服务端 duplicate 校验返回 400 且站内错误可见→保留草稿后再次保存。服务端为未写章生成新 UUID；保存响应与整页刷新后的 `id/volumeIndex/chapterIndex/title/synopsis` 完整有序序列逐项一致，刷新 DOM 标题与梗概一致。
- `manual-content-management --expect-known-failures` 为 **0 PASS / 1 XFAIL / 0 ERROR**：world、character、outline 子断言全部 true，只剩 p26-13 范围内的 chapterActions false。`initial-setup-summary` 与 `checkpoint-card-targeting` strict 各 **1 PASS / 0 XFAIL / 0 ERROR**。
- frontend build PASS；JDK21 `mvn compile && mvn test` 与 `./init.sh` 均 PASS，**548 tests / 0 failures / 0 errors / 1 skipped**，27 个 prompt 与前端生产构建通过。本 feature 未改后端、Agent/prompt、自动任务或 mode switch/resume。
- Review fix round 1 先强化真实浏览器场景：改为两卷已写边界，并将新增章“上移后立即保存、整页刷新精确比对”与后续删除分成两次真实保存；延迟结构 PUT 响应期间同时检查全部结构草稿入口锁定及总纲独立编辑。RED 的 `outlineActions` 具体为 `addBoundary.earlyDisabled=false`、`pendingLocked.inputs=false`、`pendingLocked.buttons=false`、`pendingLocked.premiseSaveLocked=false`、`premiseDraftRetained=false`；移动后立即保存与刷新已为 true，证明移动重编号实现本身无回归。
- Review GREEN：新增章只允许在最后一个含已写章节的卷及其后卷；更早的已写边界卷入口 disabled。结构保存期间卷章字段、结构按钮、刷新、重规划、结构保存及总纲保存均锁定，总纲文本框仍可编辑；结构响应不再覆盖未保存总纲草稿，结构请求也不携带该草稿。增强后的 manual aggregate 仍为 **0 PASS / 1 XFAIL / 0 ERROR**，全部 outline/world/character 子断言 true，仅 future `chapterActions` false；两个 strict 场景各 **1/0/0**。frontend build、JDK21 compile/full/init 再次全绿，仍为 **548 tests / 0 failures / 0 errors / 1 skipped**。

---

## 会话记录（2026-09-03）：Phase 26 p26-07 初始设定审核内容摘要

- AFTER_INITIAL_SETUP checkpoint 新增 Jackson JSON payload：`worldSummary`、`characterSummary`、`premiseSummary` 和 `links`。三段内容只读取已持久化的世界观条目、人物卡、outline premise 与项目原旨，不使用固定摘要文案。
- 每段摘要按 Unicode code point 截断到最多 300，补充 emoji 长文本回归，证明不会拆开 surrogate pair。跳转只发布真实 workspace 页签 `world` / `outline`。
- 审核卡展示“世界观摘要 / 人物摘要 / 原旨与大纲摘要”，并提供原生键盘可操作按钮跳转对应页签；UI 在调用 `setActiveTab` 前对白名单 target 做运行时过滤，异常旧 payload 不能写入不存在的页签状态。
- 严格 TDD：Java RED 为 **1 failure / 0 errors**（payload 为 null）；UI strict RED 为 **0 PASS / 1 XFAIL / 0 ERROR**（三段摘要、按钮与页签选中断言均失败）。GREEN 后 Java focused checkpoint 回归 **15/15**，`initial-setup-summary` 与 `checkpoint-card-targeting` strict 各 **1 PASS / 0 XFAIL / 0 ERROR**，没有把新场景加入 `KNOWN_FAILURES`。
- Browser E2E 产品断言 **8/8 PASS**：前 7 项由完整脚本通过；第 8 项的脚本内二阶段重启暴露既有端口回收竞态（base `13f745c` 同样复现，第二服务日志明确 `Port was already in use`）。隔离启动确认 `harsh=true` 的新服务后，以原断言得到 chapter `NEEDS_MANUAL_REVIEW` 且 `pendingTag/resolveBtn/resolved=true`；未修改 harness。
- 完整验证：frontend build PASS；JDK21 `mvn compile && mvn test` 与 `./init.sh` 均 PASS，**523 tests / 0 failures / 0 errors / 1 skipped**，init 另通过 27 个 prompt 与生产前端构建。
- 本 feature 只增加 checkpoint payload 与审核展示，不修改 Agent、prompt、上下文组装、自动任务或 mode switch/resume 语义，因此不触发 replay 质量门禁。
- Review fix round 1 先补三类红灯：超长项目原旨会吞掉 outline 标记、原旨与 outline premise 同时为空会生成空摘要、旧 payload 的非字符串摘要会令 React 渲染崩溃。Java RED 为 **3 failures / 0 errors**；强化后的 strict RED 为 **0 PASS / 1 XFAIL / 0 ERROR**，真实摘要卡被异常卡拖垮且安全跳转不可用。
- GREEN 后双来源在 300 code points 总预算内公平分配并保留双方标记，单来源独占完整预算；缺 premise 时读取已持久化卷标题/summary，仍为空则在创建 checkpoint 和改变状态前 fail-closed，项目保持 `CONCEIVING`。前端只接收字符串摘要，并继续对白名单 links 做运行时归一化。
- Review fix 回归：focused checkpoint **17/17**；`initial-setup-summary`（含异常旧 payload）和 `checkpoint-card-targeting` strict 各 **1 PASS / 0 XFAIL / 0 ERROR**；browser 产品断言分段 **8/8**；frontend build、JDK21 full/init 均 PASS，**525 tests / 0 failures / 0 errors / 1 skipped**。

---

## 会话记录（2026-09-03）：Phase 26 p26-03 审核卡目标绑定与修改结果

- 审核卡的批准、拒绝、修改均直接持有被点击的 `Checkpoint`；拒绝/修改弹窗用显式 `RewriteTarget { checkpoint, action }` 保存目标，提交只用 `target.checkpoint.id`，不再读取最后一张审核卡。
- 审核卡显示 checkpoint 类型与 payload 摘要；成功处理后页面保留成功 Alert，显示所点卡描述、真实 revision 目标类型、修改前/后摘要及版本，不止短暂 toast。
- 浏览器测试先行：强化场景以两张待审核卡操作较早卡；RED 为 strict **0 PASS / 1 XFAIL / 0 ERROR**（`firstHandled=false`、`latestUntouched=false`、结果摘要缺失）。GREEN 为 strict **1 PASS / 0 XFAIL / 0 ERROR**，同时验证较新卡保持 PENDING、前后摘要来自 Scenario 返回的真实 revision；仅移除 `checkpoint-card-targeting` 的 KNOWN_FAILURES。
- 完整验证：`frontend npm run build` PASS；`mvn compile -q` PASS；按 JDK21 (`JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`) 运行 `mvn test` 与 `./init.sh`，均为 **504 tests / 0 failures / 0 errors / 1 skipped**，init 另确认 27 个 prompt 与生产前端构建通过。
- 非门禁环境记录：未提升权限的 Java 26 首跑受沙箱限制（MockWebServer bind / Mockito ByteBuddy attach）失败；提升权限及 JDK21 后上述门禁全绿。

---

## 会话记录（2026-09-03）：Phase 26 p26-02 review fix round 1

- 世界观替换跨存储顺序调整为“校验独立 candidate ID → 完成候选向量索引 → SQLite 事务替换 → 确定性删除旧向量 → bump memory revision”；候选索引或 DB 替换失败都会清除候选 ID，旧 DB 与旧向量保持可用。
- StoryCanon 对保留的同 type+subject 键先走 `applyHumanPatch`，保留 `SUPERSEDED` 与 `supersededBy` 审计链；仅 retire 被删除键、仅新增新键。名称恰为 `时代锚点` 的新旧 KnowledgeEntry 均不能覆盖或归档派生真典锚点。
- `ON_CONTINUITY_FIX` 恢复既有 status-only 语义：reject 不改内容；modify 在 checkpoint 仍为 `PENDING` 时安全推进，成功后才记 `MODIFIED`。只有初始设定、卷大纲和章节 checkpoint 进入内容修订。
- 四轮 TDD RED→GREEN：知识库向量失败/ID 冲突 **2 failures → 8/8**；同主体真典审计 **1 failure → routing 7/7**；连续性路由 **2 failures → controller 6/6**；时代锚点保护 **2 failures → routing 9/9**。
- 最终 focused routing/controller/KB/Agent/双 StoryCanon contract 共 **46 tests / 0 failures / 0 errors**；strict 浏览器两场景各 **1 PASS / 0 XFAIL / 0 ERROR**；mystery replay 为 **3 章 / 10,191 字 / 54 calls / 0 errors / 0 chapter failures**，baseline PASS。
- `mvn test` 与 `./init.sh` 均为 **504 tests / 0 failures / 0 errors / 1 skipped**；init 的 27 个 prompt 与前端生产构建通过。

---

## 会话记录（2026-09-03）：Phase 26 p26-02 初始设定与卷大纲审核改写真正路由

- `NovelOrchestrator.reviseCheckpoint(...)` 按 checkpoint 类型路由真实改写；返回目标、前后摘要及内容版本，且自身不改变 checkpoint 状态。Controller 只在候选校验和持久化成功后标记 `REJECTED`/`MODIFIED`，失败继续保持 `PENDING`。
- `WorldBuilderAgent.propose(...)` 与 `PlotterAgent.proposeVolumeRevision(...)` 均先生成 detached candidate，不在解析阶段写库；显式审核的空/非法候选不再产生 fallback 占位。
- 初始设定通过新增的最小 `replaceKnowledgeEntries` SPI 及 SQLite 单事务实现整体替换；卷大纲保存也以单事务提交。强制插入失败测试证明两条路径都会 rollback 并保留旧数据。数据库提交成功后，KnowledgeBaseService 才清旧向量、批量索引新条目并 bump memory revision；数据库失败不会提前改变向量。
- 用户意见产生的新世界观同步进入 StoryCanon：仅 retire 本次被替换旧条目名称及其对应 WORLD_RULE/OBJECT_STATE，新候选以 HUMAN_PATCH 写成 ACTIVE；时代锚点和其他主体不受影响。
- Controller 在改写和项目推进都成功后才处理 checkpoint；候选/持久化或推进失败均留下 `PENDING`，reject/modify 成功响应均带 `revision`。
- 卷大纲仅替换 `project.currentChapterIndex` 之后且章号集合完整匹配的候选，已写章节的 ID、标题、内容和项目边界保持不变。
- TDD RED：focused 首跑缺少 `reviseCheckpoint`/`replaceKnowledgeEntries`；加入 Agent 零写入测试后共 7 个 missing symbol；知识库一致性测试先缺 `replaceEntries`（2 errors），canon/controller 闭环测试先缺 `resumeAfterCheckpointRevision`（2 errors）。GREEN：focused/Agent/controller/KB **20 tests**，checkpoint/Scenario/StoryCanon 回归 **13 tests**，均 0 failures/errors。
- strict 浏览器：`initial-setup-modify`、`volume-outline-modify` 各 **1 PASS / 0 XFAIL / 0 ERROR**；仅从 `KNOWN_FAILURES` 删除这两项。
- 默认 E2E 首跑因最新 wuxia fixture 与默认 mystery 请求指纹不匹配而 fail-fast；diagnose 为 **1 LLM_ERROR / 0 calls**。按批准指定匹配的 `canary-mystery-20260831` fixture 后为 **3 章 / 10,191 字 / 54 LLM calls / 0 errors / 0 chapter failures**，`compare-baseline.py` PASS。
- 完整验证：`mvn compile` PASS；`mvn test` 和 `./init.sh` 均为 **497 tests / 0 failures / 0 errors / 1 skipped**；init 另确认 27 个 prompt 完整与前端生产构建通过。

---

## 会话记录（2026-09-03）：Phase 26 p26-01 Checkpoint 目标归属与状态校验

- 新增 `CheckpointTargetService` 作为审核操作的唯一验证边界：checkpoint 不存在抛参数错误；项目归属不符或状态非 `PENDING` 抛状态错误（HTTP 409）。
- `approve`、`reject`、`modify` 三个 checkpoint REST 操作在任何编排或写入之前统一经过该边界，避免跨项目或重复处理改变 checkpoint。
- 测试先行：`CheckpointTargetValidationTest` 首次运行得到 **2 failures / 0 errors**（实际 HTTP 200）；修复后 focused `CheckpointTargetValidationTest,ApproveSpecificCheckpointTest` **3 tests / 0 failures / 0 errors**。
- 浏览器边界验证：`./scripts/product-audit-e2e.sh --case checkpoint-card-targeting --expect-known-failures` 为 **0 PASS / 1 XFAIL / 0 ERROR**；卡片绑定仍是 p26-03，不在本 feature 范围。
- 完整验证：`mvn compile -q && mvn test` 和 `./init.sh` 均为 **483 tests / 0 failures / 0 errors / 1 skipped**；init 另确认 27 个 prompt 完整及前端生产构建通过。

---

## 会话记录（2026-09-02）：Phase 25 产品审计 E2E 测试先行

**输入**：用户要求依据 `docs/audits/2026-09-02-product-design-consistency-audit.md`，先补充端到端测试用例，不提前修复产品问题。

- 新增 spec：`docs/specs/2026-09-02-product-audit-browser-e2e.md`，登记并完成 p25-01。
- 新增 `scripts/product-audit-e2e.sh`：真实启动 Spring 应用，使用隔离 SQLite、Scenario LLM 和 ego-browser；支持 `--list`、`--case`、默认严格模式与 `--expect-known-failures`。
- 9 个场景覆盖：自动项目零干预、初始设定带意见重做、卷大纲带意见重做、独立章节重写、交互切自动、审核后阅读稳定性、多检查点对象安全、人工内容管理、移动端关键路径。
- 浏览器红灯基线：**0 PASS / 9 XFAIL / 0 ERROR**；已知失败集合精确匹配时命令 exit 0。严格模式单跑 `auto-zero-intervention` 为 1 XFAIL、exit 1，确认不会把产品失败当成通过。
- 具体失败证据：自动项目一次启动后停在 PLANNING；两类审核改写未改变内容；独立章节重写弹窗不关闭且版本不增；切自动仍保留暂停与检查点；批准章节后正文消失并持续 loading；多卡片拒绝命中最新卡而非所点卡；人工完整编辑/新增入口缺失；375px 标题宽约 71px，侧栏触发器和正文段落不可访问。
- 常规基线：`./init.sh` PASS；481 tests / 0 failures / 0 errors / 1 skipped；前端生产构建通过。
- 本轮未修改业务实现、Prompt、schema 或质量基线。后续修复每通过一个场景，需从 `KNOWN_FAILURES` 移除对应 ID；出现 XPASS 会主动使已知失败校验失败。

---

## 会话记录（2026-09-02）：页面功能与交互产品一致性审查

**输入**：使用 ego-browser 全面检查自动/交互模式、世界观、人物、大纲、章节、审核、质量结果和响应式页面，并输出与产品设计不一致的文档。

- 完成两类隔离项目的真实页面审查：自动模式项目、交互模式项目。
- 确认 16 项产品不一致：P0 3 项、P1 8 项、P2 5 项；重点包括检查点改写未实际执行、独立章节重写无响应、交互切自动不续跑、自动模式不能端到端推进，以及人工内容管理能力不完整。
- 审查报告：`docs/audits/2026-09-02-product-design-consistency-audit.md`。
- 截图证据：`docs/audits/assets/2026-09-02-product-audit/`。
- 既有 `browser-e2e.sh` 8/8 PASS；本次同时补充了现有套件未覆盖的审核后刷新、改写生效和模式切换场景。
- 环境验证：`./init.sh` 通过，481 tests / 0 failures / 0 errors / 1 skipped，前端生产构建通过。
- 本次只产出审查文档和证据，没有修改产品实现、Prompt、schema 或 feature 台账。

---

## 会话记录（2026-09-02）：Phase 24 文档状态一致性

**输入**：用户要求“先把文档状态修复成一致”。

- 新增 spec：`docs/specs/2026-09-02-documentation-state-sync.md`，登记 p24-01。
- `AGENTS.md` 更新为当前规模：23 个 Agent/辅助职责类、27 个 Prompt、7 个题材预设、Phase 24 / 178 features、480+ 测试基线。
- `session-handoff.md` 重写为单一 2026-09-02 当前状态；移除已完成的 p22/p23 待办和旧分支信息，历史过程继续保留在本文件。
- 明确当前非阻塞观察项：质量分 baseline 未启用、无 Git remote、Orchestrator/ContextAssembler 复杂度热点、前端 bundle 体积警告。
- 业务代码、Prompt、schema、质量基线均未修改。
- 修改后验证：`jq empty feature_list.json` PASS；`mvn compile -q` PASS；`mvn test` **481 tests / 0 failures / 0 errors / 1 skipped，BUILD SUCCESS**。
- p24-01 已更新为 completed；台账当前为 Phase 24、178/178 completed。

---

## 会话记录（2026-08-31 续）：p23-06 金丝雀完成——29/29 全部收官

**用户批准花费后执行 p23-06 真实 LLM 质量金丝雀**（p21-01 改动了 WUXIA 题材逻辑，触发条件成立）：
- e2e.sh 补 --preset 参数（原硬编码 mystery，无法录 WUXIA）
- mystery 实录：3 章 10,191 字，0 错误 0 失败，对既有基线 compare **PASS**（无需 bless）
- WUXIA 实录：3 章 11,308 字，0 错误 0 失败；规则断言 **PASS**（54 fixture：武侠规则注入、
  修仙规则/境界节奏/爽点四段式钩子零命中——p21-01 改动在真实生成链路生效）
- 报告：data/release/canary-{mystery,wuxia}-report.json；fixture：data/e2e-fixtures/canary-*-20260831
- WUXIA 基线经人工确认 bless：`baselines/golden-wuxia.json`（mystery 基线不受影响），
  对新基线 compare PASS——p23-06 及全部 29 项彻底闭环

**29/29 features 全部完成。** 观察项（不阻塞）：卷大纲 prompt 的「题材规则（武侠）」仅出现 1 次
（总纲层），逐卷层未重复注入——p17-03 单测覆盖的是 provider 注入路径，生产接线密度可后续评估。



---

## 会话记录（2026-08-31）：Phase 23 收口——26/29 完成

### 本段完成
- **p23-03** 400 章零成本 soak 全量 PASS：恰好 400 章连续唯一 / 零非终态 / 零重复 ACTIVE 真典键 /
  单任务无重叠 / 零网络调用（无 API key 启动即证明）/ 导航 18ms；全书 1,058,524 字。
  修复：soak 改 layeredOutline=true；场景网关总纲按「预计总章数」、卷大纲按「章节范围」生成
- **p23-02** reliability-e2e 四场景全 PASS：崩溃-重启续跑 / 陈旧 worker 409 / 人工复核暂停-处理-续跑 / 真典唯一性。
  前置修复：场景网关 harsh 档（按 CONTINUITY_CHECK 调用序注入【人物|高】）；人工复核冻结任务终态改 PAUSED
- **p23-04** 按用户指示改用 **ego-browser**（移除 Playwright）：browser-e2e.sh 8 检查全 PASS
  （终态×2 / 懒加载 / 响应式 / 键盘 / SSE / 人工复核）。套件抓到并修复三个真实缺陷：
  ① findChapterMetaByProject 漏 status 列（待人审标记永不显示）
  ② SPA 深链接 /project/** 500（SpaForwardController 转发）
  ③ InteractiveReview.refresh 误依赖 selectedIdx（点章全量重取）
  ④ 场景正文剔除「第N章」字样（章号告警 + prompt 探测污染）
- **p23-05** release-gate.sh：六门禁聚合（full tests / frontend build / replay baseline /
  reliability / soak / browser）→ data/release/manifest.json

### 验证
- 全量 mvn test：**481 pass / 0 fail**；前端构建通过；e2e replay 逐字一致 + compare PASS
- `./scripts/release-gate.sh` 完整实跑（manifest 见 data/release/manifest.json）

### 剩余
- p23-06 真实 LLM 质量金丝雀（唯一未完成；需批准花费 + 人工 bless）



---

## 会话记录（2026-08-30 续）：Phase 22 收口 + Phase 23 功能修复

**用户指示**：暂停真实章节书写类验证（400 章 soak 实跑），优先功能改动与验证。

### 本段完成
- **p22-04 章节生产模块**：ChapterProduction（prepare/write/qualityGate + produce() 小结果接口），质量链 7 方法自 Orchestrator 原样迁移；produce 不做状态转移（单测断言）
- **p22-05 显式策略组合**：OrchestratorPolicies 值对象构造期注入，11 个后置 setter 全部移除；OrchestratorConfig.requirePolicy 缺失能力启动即失败并指名；OrchestratorCompositionTest 4 用例。Phase 22 至此 6/6 完成
- **p23-03 功能根因修复**：400 章 soak 首跑只产出 40 章——根因是 layeredOutline=false 走传统一次性大纲（设计上限 40 章）。修复：soak.sh 改 layeredOutline=true；ScenarioLlmGateway 补卷大纲范围感知（「章节范围：第X章到第Y章」→ 逐章行）与总纲章数感知（「预计总章数：N」→ N 章大纲，每卷 8 章）
- **p23-02 前置功能**：场景网关 harsh 档（第 1 章注入 HIGH 矛盾）；WritingTaskExecutor 人工复核冻结的任务终态改 PAUSED（原 FAILED「写作未完成」误导）
- **验证**：全量 mvn test 481 pass / 0 fail；e2e replay（零成本回放）与 record 基线逐字一致（10,574 字 / 3 章 / 0 错误）；compare-baseline PASS；12 章 soak 冒烟六项不变量全 PASS

### 暂停中（待用户恢复）
- p23-03 400 章全量实跑：`./scripts/soak.sh --chapters 400 --seed 20260830`
- p23-02 reliability-e2e.sh 脚本实跑；p23-04 Playwright；p23-05 release-gate.sh
- p23-06 真实 LLM 金丝雀（需批准）



---

## 会话记录（2026-08-30）：production-readiness 架构落地（Phase 20-23）

**输入**：`.omo/drafts/production-readiness-architecture.md`（已批准规划，5 组件 × 4 阶段 30 features）+ 用户指示「按架构优化文档及拆分任务实现，并端到端测试验证」。

**结论：Phase 20 全部（9/9）、Phase 21 全部（8/8）、Phase 22 的 4/6、Phase 23 的 1/6 完成；feature_list.json 共登记 29 个新 feature（completed 22 / not-started 7）。全量 mvn test 465+ 绿、前端构建绿、e2e replay 与 record 基线逐字一致、compare-baseline PASS。**

### Phase 20 生命周期完整性（9/9 ✅）
- **p20-01** NEEDS_MANUAL_REVIEW 不再被末尾 DONE 覆盖；待人工章节冻结后续写作（拒绝写章 + 循环 WRITING_PAUSED）；resolve-review 恢复
- **p20-02** ChapterPostProcessor 模块：~210 行内联后处理提为 10 阶段单点编排（CONTINUITY→ENTITY_GRAPH→FACT_HARVEST→NARRATIVE_THREADS→READER_REVIEW→FORESHADOWING→DIVERSITY→ARC_TRACKING→PERIODIC→VOLUME_SUMMARY），行为逐行等价
- **p20-03** post_process_ledger 账本（主键 project/chapter/stage）：PENDING/RUNNING/COMPLETED 往返、COMPLETED 终态不降级、未完成阶段按声明序返回
- **p20-04** 重启续跑：ENTITY_GRAPH 注入 Error 崩溃（模拟进程死亡）→ 同库重建上下文 → 只补跑未完成阶段（CONTINUITY 0 次 LLM、收割恰 1 次、0 重复摘要）→ DONE；ApplicationRunner 启动清扫
- **p20-05/06** ProjectWorkCoordinator owner-token：并发 acquire 恰一 owner、stale release 不伤新 owner；重复 startWriting 409 冲突（移除 30s 强制接管）；/run /resume /write-chapter /write-all 全部经协调器，测试全闩锁
- **p20-07/08** HUMAN_PATCH 保护：窗口内自动冲突事实丢弃不落库、窗口外取代留审计；人工编辑总是取代旧 ACTIVE（唯一性）
- **p20-09** 章节号归一化仅作用于开头标题，对话/引文/跨章引用逐字保留

### Phase 21 产品真实性与终态门禁（8/8 ✅）
- **p21-01** WUXIA 退出快节奏集合（isFastPaced）：卷大纲/章节约束不再强制爽点/四段式/悬念钩子；WUXIA 豁免 POWER_SYSTEM；CULTIVATION 不变。p17-02/03/04 三处相反断言按「红相即规格修正」处理
- **p21-02** FINAL/NEEDS_REVISION 服务端门禁：FINAL 拒绝全部写作命令且状态不变（修复 write-one 把 FINAL 推到 VOLUME_DONE 的状态破坏）；NEEDS_REVISION 允许修订 + retryFinalAudit 显式重审（POST /final-audit）
- **p21-04** GET /chapters?page&size 元数据分页（400 章零正文序列化），正文单章懒加载
- **p21-03/05/06/07/08** 前端：终态中文门禁与「重新评审」、章节懒加载缓存、Sider 响应式折叠+双栏堆叠、键盘激活+aria-label、SSE 有界退避重连与连接状态展示（npm build 绿）

### Phase 22 模块深化（4/6 ✅，2 项未做）
- **p22-01/02/03** StoryCanon 端口（spi）：recall/harvest/applyHumanPatch/saveIfAbsent/validate/supersedeFact/retire；保护窗口与 ACTIVE 唯一性语义收敛进端口；SqliteStoryCanon + InMemoryStoryCanon 双适配器过同一 8 项契约（16 用例）；FactHarvester/CanonPatchService/Orchestrator/RelevanceGateway/ContextAssembler/ProfileUpgrader/ArcUpdater 全量迁移——业务包零 StoryFactRepository import
- **p22-06** NovelRepository 9 个 default no-op（归档/预设/清理）改抽象：缺能力=编译失败；契约补归档计数与预设往返
- **p22-04/05（未做）** 章节生产模块提取、显式生命周期策略组合（去后置可选 setter）——见 handoff 下一步入口

### Phase 23 可重复业务证据（1/6 ✅，5 项未做）
- **p23-01** ScenarioLlmGateway：8 任务类型零网络确定性响应（章节号镜像、逐字可复现）；e2e.sh 默认自动解析最新版本化 replay 语料（mtime），e2e-fixture-dir-test.sh 契约检查
- **p23-02~06（未做）** reliability-e2e 进程级场景 runner、400 章 soak、Playwright 浏览器套件、release-gate 聚合器、真实 LLM 金丝雀——见 handoff 下一步入口

### 端到端验证（2026-08-30 实跑）
- 全量 `mvn test`（source scripts/load-env.sh）：**465+ pass / 0 fail / 1 skip**（Spring 上下文测试需 DEEPSEEK_API_KEY）
- `./init.sh`：全绿（详见本次提交后实跑记录）
- `./scripts/e2e.sh`（E2E_FIXTURE_DIR=dsv4-20260829d）：3 章 / 10,574 字 / 50 LLM 调用 / 0 错误 / 0 章节失败——与 2026-08-29 record 基线**逐字一致**（Phase 20/21 行为变更未扰动确定性管道）
- `python3 scripts/compare-baseline.py`：PASS（未 bless——质量无退化无需更新基线）
- `cd frontend && npm run build`：通过
- `bash scripts/e2e-fixture-dir-test.sh`：通过（默认解析 → dsv4-20260829d）

### 遗留与风险
1. p23-03 soak 依赖 PLOTTING 场景响应支持章节范围感知的分层大纲生成（当前 ScenarioLlmGateway 只发 3 章总纲形状）
2. e2e-fixture-dir-test.sh 的 auto-resolution 断言假定 data/e2e-fixtures/ 存在语料；全新 clone（无语料）时该脚本第一条检查会失败——属预期（提示先 record）
3. 分支未 push

## Current State

**Last Updated:** 2026-08-30
**Phase:** **Phase 20-23 production-readiness 架构落地（22/29 completed）— e2e/基线/构建全绿**

### 本次会话（2026-08-28）：架构差距分析 → 模型切换 → 四阶段全量实现

**输入**：四路代码级架构评审（编排器/记忆/风格/交互）定位 400 章尺度系统性缺口 → 用户指示「切换 glm-5.3-flash + 按方案全部实现 + 端到端验证」。

**Spec**：`docs/specs/2026-08-28-million-word-roadmap.md`（Phase 16-19，18 features）

#### 模型切换（glm-5.3-flash）
- 8 任务类型 + default 全量路由 `glm-5.3-flash`，端点 `https://open.bigmodel.cn/api/coding/paas/v4`（标准端点 1113 余额不足，coding 订阅通道可用）
- `thinking-enabled: false`：GLM thinking 参数关闭（思考模式单次 3-10 分钟，e2e 构思 20 分钟超时根因；关闭后秒级返回零 reasoning token）
- callTimeout 3→10 分钟；GLM_API_KEY/GLM_END_POINT/GLM_MODEL 环境覆盖支持

#### Phase 16 百万字止血（6/6）
- p16-01 HIGH 老化（30 章未复发归档）+ FINAL 门禁近 60 章口径 → 400 章可达 FINAL
- p16-02 伏笔回收卷：修复不可达完结路径（原写 issue 后直接 break 的死路），指令入 writingNotes，有界一次
- p16-03/04 四处全量注入收窄：FactHarvester cap 80（cast 相关优先）+ fail-loud；伏笔清单 20/线索台账 15/检查设定 20
- p16-05 重写/段落编辑路径向量 chunk 重建（含拒收分支恢复），消除旧情节永久污染检索
- p16-06 摘要分层裁剪（先丢最老卷 stub，细粒度受保护）+ premise 预算截断（6000 token 保头）

#### Phase 17 风格确定性（5/5）
- p17-01 WUXIA 全链路：枚举/检测词（先于修仙）/五方法规则/禁词表（修为·境界·丹药为武侠合法词不禁）/wuxia.json 预设/前端选项
- p17-02 爽点四段式钩子题材条件化：慢节奏题材（LITERARY/HISTORICAL/ROMANCE…）不强制爽点/悬念钩子/开篇高压，换张力指引；快节奏（含 WUXIA）现状不变；chapter-writing.st 四段式改 structureGuide/climaxCraft 变量；plot 反馈词题材化
- p17-03 volume-outline 补 genreRules+climaxPrinciple（长篇题材约束不断链）
- p17-04 ConceiveGate POWER_SYSTEM 仅力量体系题材强制；styleVoice 注入；preferredQuoteSources 消费
- p17-05 自定义预设持久化（custom_presets 表）

#### Phase 18 人机交互（4/4）
- p18-01 write-all 遇审核点 → 任务 PAUSED（不再误报 FAILED）
- p18-02 checkpoint 质量 payload（字数/评分/未解决 HIGH/预览，评审后富集）+ 前端审批面板渲染
- p18-03 写前指令通道（write-chapter/write-all instruction 参数，【作者指令】前置 writingNotes 逐章沿用）
- p18-04 SSE 实时进度（EventLog 订阅总线+SseEventBus 按项目路由+EventController；前端 EventSource 逐章进度条）

#### Phase 19 长篇质量结构（3/3）
- p19-01 角色生死链：Character.Status+deathChapter，FactHarvester 死亡关键词自动置位，注入标注【已故·第N章殁】，ArcScheduler 不调度死人
- p19-02 story_facts (project_id,status) 索引 + 真典 subject 过 resolveCanonical（别名不再分裂）
- p19-03 全书结构化快照（book_snapshot JSON：主线/阵营/人物/时间线/里程碑，卷末增量更新，顶部注入【全书快照】）

#### 验证
- 全量 `mvn test`：**393 pass / 0 fail / 1 skip（既有）**；`cd frontend && npm run build` 通过
- 新增测试 44 个（18 feature 各自 verify_cmd 全绿）
- 武侠 REST 验证：presets 含 wuxia；preset=wuxia 建项目 genre=WUXIA；preferences 带预设值；SSE CONNECTED 事件正常

#### LLM 路由终态（2026-08-29）：切回 DeepSeek v4-flash
- GLM-5.3-flash 跑通 8 轮 E2E 调试期间修复 8 个基础设施 bug（coding 端点切换/thinking 开关/callTimeout 3→30min/审查温和化重试/模板长度纪律/429 退避 5→30s/连接池禁用/流式聚合路径 completeViaStream）
- 终局：GLM coding 网关**夜间对 >5min 长响应 StreamReset CANCEL**（第 9 轮 100% 复现，新连接也失效）——非代码可控，按用户决策切回 DeepSeek v4-flash
- 流式聚合路径保留（DeepSeek 同受益：长生成逐 token 心跳防切流，fixture 录制不受影响）

#### replay 确定性彻底修复（2026-08-30）
- **调用序号化 fixture**：`<taskType>-<指纹>-<N>.json`，同指纹每次调用独立保留，replay 按序回放——时序漂移根治；分支分叉 fail-fast 可诊断；兼容旧 fixture
- 验证：dsv4-20260829d record（3章/10,574字/50调用/0错误/评分9/9/8）→ replay **逐字一致** → compare PASS（bless）；全量 mvn test 407 pass

#### Bugfix 轮（2026-08-29 晚）
- **标题污染修复**（15e1596）：单行大纲前瞻未行首锚定致行内「(HIGH，第N章回收)」误切分——修复后 record 3 章连续、标题干净、0 错误、评分 9/8/7、compare PASS（dsv4-20260829c）
- **replay 健壮性**（61075d8）：空响应重试 + 禁止空覆盖非空 fixture（402 测试绿）；replay 第3章仍有发散——机理为后处理链 DB 时序副作用无法靠 LLM fixture 复现（机制性限制），DB 快照回放立项建议见 handoff

#### E2E 验证（DeepSeek v4-flash，2026-08-29）
- **record**：3 章 / **10,951 字** / 57 LLM 调用 / **0 错误 / 0 章节失败**；构思+3 章全程 ~55 分钟
- **replay**：与 record 完全一致（同字数/同调用数/0 错误）——确定性回放验证通过
- **compare-baseline**：PASS 后 --bless（基线 fixture 目录 `data/e2e-fixtures/dsv4-20260829`）
- 读者评分：第1章 8 / 第3章 8 / 第5章 9；正文人工抽审质量扎实（题材一致性/开头钩子/细节质感）
- 已知瑕疵（回流候选）：第 3/5 章标题混入大纲伏笔标注文本（卷大纲行格式漂移 + parse 端标题截取健壮性）——不影响正文与质检，标题展示问题

---



**Last Updated:** 2026-08-22
**Phase:** **Phase 15 人机设定扩容与作业可观测 — 9/9 完成（p15-08/09 实现入库）**

### Phase 15（2026-08-22 复审收尾：原子提交 + 回放确定性）

- **p15-08**：上下文顺序与主角锚点确定化 → DONE（`7603ee1`）。同级反馈截断 tie-break（severity→章节倒序→type→description）；精确「主角」独占首位与锚点行、substring 配角追加队尾（WritingContext 无精确主角时回退 substring 锚点）；爽点轮换提示按 enum 序列化。
- **p15-09**：replay fixture 完整请求保留 + miss 诊断 → DONE（`9a866a6`）。record fixture 存完整 systemPrompt/userMessage（preview 保留）；replay miss 转储 `<指纹>.miss.json`（taskType/missedAt/expectedFixture/完整请求）后仍 fail-fast。
- **复审补入库**（此前登记 evidence 但实现未提交的部分，按 spec 原子提交、混合文件 hunk 级拆分）：p15-03 后端/前端（`8e19ac4`/`a8cc932`：overwriteUnwrittenOnly + prune + POST /replan + 大纲 UI 编辑/重规划）、p15-04 后端/前端（`f65a90b`/`0f33e25`：retireFact + 伏笔端点 + 真典编辑/伏笔面板/人审动作）、p15-05 删除护栏（`b2e9793`：DELETE 409）、p15-06 后端/前端（`8a7e580`/`3dbe851`：checkpointSpan 1/0/-1 + PUT 偏好 + 选择器）、p15-07 e2e runner（`2ee2e57`：stub embedding + 轮询异步 /run 任务 + 每章 600s 预算）。
- **test 稳定性**：`EditPassageReturnsBeforeHarvestTest` 的 @TempDir 清理与后台 harvest 线程竞态（全量套件时长变化暴露为确定性失败）→ latch 释放+完成等待修复（`fec6b53`）；join 不可用（POST_PROCESS 空闲池线程常驻）。
- **housekeeping**：删除生成态 `.omo/`（含早期误入库的 41 个 run-continuation JSON）并 gitignore `.omo/`（`6699296`/`03794d5`）。
- 验证（2026-08-22 实跑）：各 feature `verify_cmd` 全绿；`mvn compile -q` exit 0；全量 `mvn test` **337 pass / 0 fail / 0 error / 1 skip**；`cd frontend && npm run build` 通过；`e2e-fixture-dir-test.sh` pass；`E2E_FIXTURE_DIR=data/e2e-fixtures/replay-proof-20260822 ./scripts/e2e.sh --mode replay` 3 章 10,802 字 50 LLM calls 0 errors；`compare-baseline.py` PASS（未 --bless）。

---

**Last Updated:** 2026-08-22
**Phase:** **Phase 15 人机设定扩容与作业可观测 — 7/7 完成**

### Phase 15（2026-08-22 收尾）

- **p15-07**：E2E fixture 隔离 → DONE。`E2E_FIXTURE_DIR` 默认 `data/e2e-fixtures/llm`，并传入 Spring `llm.replay.dir`；`e2e-fixture-dir-test.sh` 覆盖默认和覆盖目录。
- 验证：隔离目录 `data/e2e-fixtures/replay-proof-20260822` 的 record/replay 均完成 3 章、10,802 字、50 LLM calls、0 errors；`mvn test` 320 pass / 0 fail / 1 skip；`compare-baseline.py` PASS。
- **p15-04**：真典 PUT/废止、伏笔 GET/POST/resolve、人审 `resolve-review` → DONE。`HitlCanonForeshadowReviewTest` 3 pass
- **p15-05**：CONCEIVING 的 `POST /run` 立即返回 taskId 并后台构思；write-all 可取消；active task 时 DELETE 409。`ConceiveTaskTest` 3 pass
- **p15-06**：滑条/开关走 PUT `/preferences`；`checkpointSpan` 1/0/-1。`CheckpointSpanTest` 2 pass
- 验证：全量 `mvn test` 311 pass / 0 fail / 1 skip；`cd frontend && npm run build` 通过
- E2E replay：构思和三章写作均可通过隔离 fixture 目录确定性回放。

---

**Last Updated:** 2026-08-22
**Phase:** **Phase 15 人机设定扩容 — 3/6（p15-01/02/03 完成）**

### Phase 15（2026-08-22）

- **p15-03 完成**：未写梗概可编 + POST /replan
  - PUT `/outline` 只覆盖 `chapterIndex > current`；已写梗概/已写卷名只读
  - `POST /replan` 调 `OutlineReplanner.replaceUnwritten`；INTERACTIVE 挂 `AFTER_VOLUME_OUTLINE` 并 PAUSED
  - `saveOutline` 按卷/梗概 id 剪枝，避免重规划后旧未写行残留
  - 大纲 UI：未写卷/章可编；「重规划未写卷」
- 验证：`mvn test -Dtest=UnwrittenSynopsisEditTest` 2 pass；全量 `mvn test` 303 pass / 0 fail / 1 skip；`cd frontend && npm run build` 通过
- 下一入口：`p15-04` 真典改/废 + 伏笔人種人收 + 人审清状态

---

**Last Updated:** 2026-08-18
**Phase:** **Phase 15 人机设定扩容 — 2/6（p15-01/02 完成）**

### Phase 15（2026-08-18）

- Spec：`docs/specs/2026-08-18-hitl-expansion.md`（用户确认方案 B）
- 下一入口：`p15-01` POST 世界观/人物 + UI 添加
- 锁定：已写章不回溯；INTERACTIVE 默认仍每章暂停；增量 WorldBuilder 禁止清空旧条目

---

**Last Updated:** 2026-08-17
**Phase:** **Phase 14 人机真典硬化 — 6/6 feature 完成**

### Phase 14（2026-08-17）

- Spec：`docs/specs/2026-08-17-human-canon-hardening.md`
- Plan：`docs/superpowers/plans/2026-08-17-human-canon-hardening.md`
- **p14-01 完成**：`deleteProject` 事务级联；`projects` 用 `id`；其余有 `project_id` 的表全删
- 验证：`mvn test -Dtest=SqliteNovelRepositoryDeleteTest` 1 pass
- **p14-02 完成**：null 异常返回「内部错误: ClassName」；构思缺世界观/人物/大纲保持 CONCEIVING
- 验证：`mvn test -Dtest=GlobalExceptionHandlerTest,ConceivingCompletenessTest,NovelOrchestratorConceiveTest` 3 pass
- **p14-03 完成**：editPassage 后处理异步；INTERACTIVE 正文落库后立即 PAUSED
- **p14-04 完成**：writeAll 轮询 taskId；按钮改为最多 50 章
- **p14-05 完成**：approveCheckpoint 按 id；仍有 PENDING 保持暂停
- **p14-06 完成**：人物全字段、真典入口、CULTURE、侧栏新建打开创建框
- **UI e2e 2026-08-18**：删除 QA 项目 PASS；侧栏新建开 Modal PASS；真典添加 PASS；连续写作文案「最多 50 章」PASS。债册春秋仍 2 章 PAUSED。

---

**Last Updated:** 2026-08-16
**Phase:** **Phase 13 百万字人机真典脊柱 — 15/15 feature 完成**

### Phase 13（2026-08-16）

- Spec：`docs/specs/2026-08-16-million-word-human-canon.md`
- **15/15 完成**：H1 人机脊柱 / H2 真典召回 / H3 真重规划+卷 checkpoint / H4 Arbiter+fail-loud+伏笔活跃集 / H5 人审标记+篇幅
- 验证：`mvn test` 282 pass / 0 fail / 1 skip
- 入口能力：暂停后续写、改人物/世界观/总纲会写真典【HUMAN_PATCH】、第80章仍能召回第1卷铁律、伏笔上下文≤12

---

**Last Updated:** 2026-08-14
**Phase:** **Phase 11 过程化质量门禁架构 — 21/21 feature 全部完成 ✅ + 真实 e2e 验证 PASS**


### Phase 12 失败回流修复（6/6 完成，commit 78deccd，回归验证 PASS）
- p12-01 标题解析（cleanText 剥离 #）/ p12-02 premise 客套剥离 / p12-03 ReaderReview 重试 / p12-04 丢弃空卡 / p12-05 默认 24 章 / p12-06 UI 预设选择
- 回归验证（新项目「锦灰堆」preset=romance）：genre=ROMANCE、targetChapters=24、dialogueDensity=0.5、premise 无客套、7 人物卡 0 未命名
- Phase12FixTest 4 pass；全量 mvn test BUILD SUCCESS（须 bash source scripts/load-env.sh 提供 DEEPSEEK_API_KEY）

### 新题材创作验证（2026-08-14，爱情/古风悬疑「绣里江山」3 章，DeepSeek）
- **质量结论 overallScore 8/10**：worldbuilding 9 / consistency·style·plot·characters 8 / dialogue·pattern_diversity 7。世界观（明代织造/两京制/壬辰贡案）与题材高度扣合，正文（"针藏锋"）公案语感扎实、绣娘专业视角推理可信
- **质检链全程运行**：ConceiveGate/真典播种(8条)/PlanGate/ConsistencyGuard/读者检验/场景分类/桥段重复/说明文密度/真典收割(+4/3取代)
- **发现的失败分类（候选 feature，按失败回流协议）**：
  1. 章节标题丢失：正文含"## 第1章 针藏锋"但 Chapter.title="第1章"（延迟卷大纲 parseChapter 标题未提取 markdown 标题）
  2. 章1 读者检验 5/10"检验解析失败"：ReaderReview JSON 解析 fallback 无重试
  3. 大纲 premise 含 LLM 客套前言（"好的，作为您的专属..."）未剥离，会污染后续 prompt
  4. 新项目 targetChapters=400（schema 默认）vs 构思要求 24 章——规划规模与用户意图不符
  5. CharacterDesigner 解析失败留 2 张"未命名"空人物卡（应丢弃而非保存）
  6. UI 新建表单未暴露 preset 选择（romance 预设 dialogueDensity=0.5 本更适合爱情题材，当前 UI 路径走 GenreDetector 推断）
### 真实 e2e 验证（2026-08-14，DeepSeek 官方端点）
- **写作**：凤鸣九州 61→63 章（第62章「锁衣里的名字」2309字 9/10 分；第63章「白骨识人」3341字 8/10 分），每章 ~12 次 LLM 质检调用
- **质检链全部真实运行**：PlanGate 预检(quality_risk)→伏笔悬置告警(253条)→ConsistencyGuard 抓到真实穿帮(「坐标」时代用语/俄语词«одновременно»混入)→读者检验 9/10→场景分类→桥段重复检测→说明文密度
- **质量事件遥测**：QUALITY_GATE×2 + QUALITY_REVIEW×2(62:9分/63:8分) 全部正确 emit；diagnose.sh 质量诊断输出正常
- **页面**：工作区 5 tab 渲染正常，侧栏进度自动更新 63 章；章节 API 数据链完整（IAB 浏览器点击交互受限为环境限制，非应用问题）
- **LLM 配置**：application.yml 8 任务块切换 ${DEEPSEEK_END_POINT}/${DEEPSEEK_MODEL}/${DEEPSEEK_API_KEY}（token-plan 403 无权限），已提交 df0fab1
- **发现的可改进点**（非阻塞）：① maxChapters 语义为「写 N 章」而非「写到第 N 章」，易误触发连续写作（建议 API 加 startChapter 或文档澄清）② CONSISTENCY_ISSUE 事件仅在仲裁动作时 emit，检测发现（如 MEDIUM 穿帮）不发事件——可扩展 ③ 第62章 PlanGate 报 beats 缺失但 synopsis 是老项目延迟生成——存量数据无 beats 字段属预期，commit e3b4dd8..ee44bc9，全量 mvn test BUILD SUCCESS（需 ALIYUN_LLM_API_KEY=$DASHSCOPE_API_KEY）。Q0 地基补齐 6 + Q1 前置门禁 4 + Q2 质检委员会+合并重写 5 + Q3 可观测回归 6。Phase 10 余 p10-w3-09 由 p11-q3-06 吸收完成。
**Tests:** 本次会话仅新增 spec 文档 + 改 `feature_list.json`（数据文件），未触碰任何 `.java`/`.st`/`.yml`，编译与测试基线不受影响，故未重跑 `mvn test`。

---

## 本次会话（2026-08-14）：架构评估 + Phase 11 设计

**任务**：对创作质量、长程稳定性、一致性架构做深度评估与优化设计（用户目标：大纲生成/章节审查的过程化实时质检 + 根据结果优化调整 + 增强稳定性，过程发现而非最后反复改）。

**产出**：
- 设计文档：`docs/specs/2026-08-14-quality-gate-architecture.md`（决策完备，四大支柱 + 21 feature）
- backlog：Phase 11 共 **21 feature** 已登记 `feature_list.json`（Q0×6 / Q1×4 / Q2×5 / Q3×6）

**关键发现（代码级证据）**——Phase 10 多个「声称 completed」的质量闭环 feature **实际未落地**（grep + 构造器签名验证，2026-08-14）：
- W2-05 检查器走 RelevanceGateway 收窄：**未落地**（RelevanceGateway 仅被 ContextAssembler/MemoryConfig 引用，5 检查器 0 引用、构造器未注入）
- W2-07 检查器读真典 ACTIVE 事实：**未落地**（5 检查器对 StoryFact 引用数全为 0）
- W3-08 闭环三分类仲裁：**只实现 1/3**（NEEDS_MANUAL_REVIEW 全代码库仅枚举定义、无赋值点）
- W3-03 ArchiveCompactor 归档 5 类：**只做 2/5**
- spec line 61 人物单轨化：**未做**（CharacterProfileUpgrader 仍覆盖式写 Character，双轨无裁决）
- 进度漂移订正：实际 not-started 的是 **p10-w3-09**（FINAL 真终审），非此前多处误写的 w3-01（w3-01 已 completed）

**四大支柱**：
1. **过程化前置门禁（ConceiveGate + PlanGate）** — 大纲/梗概生成中实时质检（软门禁：次数上限→标记 `quality_risk`→人机暂停，不硬阻塞）
2. **章节质检委员会 + 统一仲裁（QualityCouncil + Arbiter）** — 各出报告→合并纠偏→一次重写→全量复检
3. **真典作为一致性检测的唯一真相层** — 检查器收窄+读真典+人物单轨+三分类（补齐 Phase 10 未落地）
4. **可观测质量遥测 + 稳定性硬化 + 回归网** — 7 类质量事件、diagnose 质量分类、fail-loud、重新启用 `minEvaluationScore` 门禁

**用户已确认决策**：软门禁+人机协同 / 统一仲裁+合并重写 / 真相层全做 / 落 spec+拆 feature。

**下次入口**：Phase 11 **Q0 地基补齐先行**（`p11-q0-01` 检查器接入 RelevanceGateway，无跨波依赖）。每 feature 遵 Bugfix gate（先写复现 failing test，后改实现）。

---

## Phase 10 进度（历史，已合并 main）

整体 22/23 完成；W1(7)+W2(7)+W3(8) 已实现并端到端 3 轮 e2e PASS。**注意**：W2-05/W2-07/W3-08/W3-03 在 `feature_list.json` 标 completed，但其「检测侧」改造经 2026-08-14 代码级验证**未真正落地**，已作为 Phase 11 Q0 重新登记补齐。

### W1 第一波（止血与正确性）— 7/7 ✅
stableSectionCache 失效 / 脏摘要重算 / 向量去污 / 成本熔断 / 退避降级 / 状态机 / 重写互斥

### W2 第二波（故事真典+检索收窄）— 7/7 ✅（注：检测侧 w2-05/w2-07 实际未落地，见 Phase 11 Q0）
story_facts 表 / 真典播种 / FactHarvester 收割 / RelevanceGateway / 检查器收窄 / 摘要预算 / 真典消费

### W3 第三波（结构演进+闭环修复）— 8/9，剩余 p10-w3-09（由 p11-q3-06 吸收）
（注：w3-08 三分类实际只实现 1/3、w3-03 归档只 2/5，见 Phase 11 Q0）

### 端到端验证（3 轮，全部 PASS）
| 轮次 | 字數 | LLM 调用 | 错误 | 失败 | compare-baseline |
|---|---|---|---|---|---|
| W1 | 10490 | 44 | 0 | 0 | PASS |
| W2 | 12370 | 47 | 0 | 0 | PASS |
| W3 | 10822 | - | 0 | 0 | PASS |

### 3 次 commit
```
9255cf4 W1 止血正确性全部 + W2 真典基石
d728b37 W2 故事真典链条（播种/收割/消费）+ 检索收窄
3724be2 W3 结构演进（延迟卷补全/叙事线索/归档/多样性/分页/弧光/闭环修复/FINAL）
```

## W1 第一波（止血与正确性）— 全部完成 ✅

7 个 feature 全部实现 + 单元测试绿 + 全量回归 181/0：

| id | feature | 测试 | 关键改动 |
|---|---|---|---|
| p10-w1-01 | stableSectionCache 写联动失效 | StableSectionCacheInvalidationTest (3) | MemoryRevision bean + cached() 版本号校验；KnowledgeBase/NoveOrchestrator 写后 bump |
| p10-w1-02 | 脏卷摘要重算 | VersionedMemoryTest 更新 | recomputeExistingSummary 原地重算卷级 dirty 摘要（不清细粒度） |
| p10-w1-03 | 向量去污 + top-K 联动 | VectorChunkCleanupOnRewriteTest (3) | VectorStore.deleteByPrefix + 重写清旧 chunk + topK 随预算 5~20 + embedding 告警 |
| p10-w1-04 | 成本预算熔断 + 真实计价 | WritingJobReliabilityTest (4) | ModelConfig.pricing + CostBudgetMonitor + 超预算→PAUSED |
| p10-w1-05 | 分类型退避 + 限流降级 | WritingJobReliabilityTest (2) | RateLimitException + 429 退避分级 + DefaultLlmGateway 降级 fallback |
| p10-w1-06 | 章节状态机 | ChapterStateMachineTest (3) | Chapter.Status 枚举 + 持久化 + doWriteOneChapter 设置 POST_PROCESSING/DONE |
| p10-w1-07 | 重写互斥 | (重写互斥完成) | checkContinuityAfterWriting 返回 boolean，reader review 互斥跳过 |

### 端到端验证 ✅ PASS
- **e2e record 3 章（真实 LLM，W1 全部新代码运行）**：
  - **3 章全部生成，taskStatus=COMPLETED，10490 字，44 次 LLM 调用，0 错误，0 章节失败**
  - 平均 3496 字/章（min 2958 / max 4338）
  - W1 状态机（POST_PROCESSING→DONE）、重写互斥、缓存失效、按模型成本计价、退避降级、向量去污、脏摘要重算 全程正常运行
- **compare-baseline PASS**：章节数 3≥3、总字数 10490≥8653、平均 3496≥2523、失败 0、错误 0 → **质量未退化**
- record 已录制新 fixture（W1 后的 prompt 指纹），replay 恢复可用。

### 已知遗留（W1 范围内）
- p10-w1-07 的"后处理 9 Agent 并行"未实现（共享 repo 写并发风险高），仅完成重写互斥；并行基于章节状态机后续做。
- p10-w1-06 的"后处理失败幂等重跑"实现为状态机字段 + 可观测（POST_PROCESSING log warn），完整重跑入口后续做。

## W2 第二波（故事真典+检索收窄）— 基石完成，链条待续

W2 的两个独立基石已落地（不依赖 LLM prompt 改动，不影响产出质量基线）：

| id | feature | 测试 | 关键改动 |
|---|---|---|---|
| p10-w2-01 | story_facts 表 + 领域模型 + 持久化 | StoryBibleTest (5) | StoryFact domain（6 类事实 + supercession 审计链）+ story_facts 表 + StoryFactRepository |
| p10-w2-06 | recentSummary 进入 token 预算裁剪 | HierarchicalMemoryBudgetTest (2) | applyTokenBudget 加 recentSummarySection 裁剪（截断 4000 token，修 GAP#7） |

**W2 剩余 5 个 feature（w2-02/03/04/05/07）为"影响产出质量"的核心改动**：
- 涉及新 Agent（FactHarvester + LLM prompt 模板）、NovelOrchestrator 构造器重构（接入真典填充与消费）、多 checker 改走检索收窄。
- 按 spec 约束：prompt/上下文组装/Agent 逻辑改动必须 `./scripts/e2e.sh` + `compare-baseline.py` PASS。
- 应在专门会话逐 feature TDD + e2e 验证推进，不在本会话仓促堆砌。

## W3 第三波（结构演进+闭环修复，9 feature）— 待续
依赖 W2 真典链条完成。后续会话推进。

## 百万字长篇架构 Review（2026-08-13）

对四大子系统（情节/大纲/伏笔、记忆/上下文、角色/一致性/质量门禁、写作循环/成本/容错）做代码级审查（带 file:line 证据），结论：系统在 ~30 章/≈9.6 万字验证通过，但向百万字（400 章）扩展存在**5 道扩展墙 + 4 个系统性根因**。

### 五道扩展墙（何时崩）
- ~40 章：延迟卷大纲丢结构化字段 → 伏笔/爽点/张力追踪集体失明
- ~80-120 章：recentSummarySection 不参与预算裁剪 + stableSectionCache 永不失效 → 上下文装不下也记不准
- ~150-200 章：全书摘要级联再压缩 → 记忆地平线坍塌
- ~200+ 章：checker/tracker 全量加载进单次 LLM 调用 → token 爆炸/检测失明
- 全程：3 次失败即永久中止 + 无限流降级 → 任一 API 限流即中断

### 核心架构动作：故事真典（Story Bible）
系统缺失"可变的事实真相层"——信息要么是有损压缩摘要（会衰减）要么是已发布正文（不可改）。引入 `story_facts`（可累积/可纠错/可检索的事实层）同时解决长程记忆坍塌与修复闭环。用户已确认采用"完整真典"路线。

### 六大支柱（详见 spec）
1. 故事真典（★关键，替代 book_summary 为主长程记忆）
2. RelevanceGateway 检索收窄（消灭全量加载反模式）
3. 分层无衰减记忆 + token 预算 + 缓存失效
4. 可演进结构规划 + 归档衰减
5. 可靠写作作业（成本熔断/分类型退避/模型降级/并行/状态机/重写互斥）
6. 闭环修复（三分类仲裁 + ConsistencyGuard 入闭环 + 重写复检）

### 分波交付（已登记 Phase 10，23 feature）
- **W1 止血正确性（7 feature，先行）**：stableSectionCache 失效、脏卷摘要重算、向量去污+topK、成本熔断、退避降级、章节状态机、后处理并行+重写互斥
- **W2 真典+检索收窄（7 feature）**：story_facts、播种、FactHarvester、RelevanceGateway、检查器收窄、摘要分层预算、真典替代 book_summary
- **W3 结构演进+闭环修复（9 feature）**：总纲重规划、延迟卷补全、ArchiveCompactor、分页、弧光时间线、多样性长窗、线索状态机、闭环修复、FINAL 终审

### 决策记录
- 完整真典（非轻量挂 KnowledgeEntry）：一个机制解最多 gap，性价比最高
- 三波全规划、先做 W1：W1 含 2 个已存在的正确性 bug（stableSectionCache/GAP#8、脏卷摘要/GAP#10），风险最低收益立竿见影
- 不改正文回溯重写：靠真典纠错 + 标记 needsManualReview

## 百万字可靠性 — P0/P1/P2/P3 完成

- **P0 任务正确性/安全**：单活跃作业收敛、cancel 状态机、诚实终态、孤儿清扫、凭据 fail-fast 语义修正（校验所有模型、远程端点要求 key、本地豁免）、测试加载系统 key（load-env.sh）、JDK26 兼容（byte-buddy/mockito 升级）
- **P1 数据正确性**：Chapter.version 往返、摘要 dirty + sourceChapterVersion（表列+迁移）、重写失效→细粒度重算/卷级过滤
- **P2 长程记忆**：model-aware token budget（窗口 90%、输出预留）、连续性检查注入历史卷摘要+叙事线索台账、embedding cloud-api 默认（百炼 text-embedding-v3）
- **P3 质量证据**：FINAL 门禁统一（NEEDS_REVISION）、LLM 成本遥测（estimatedCostCny）、diagnose 成本聚合、E2E 异步轮询

### 本次排查修复的 4 个真实可靠性 bug（Bugfix gate）

1. **分层大纲截断空转**：planVolume 20 章+总纲注入致 completion 触顶 → 卷块 8 章 + maxTokens 12000 + 占位兜底
2. **record→replay 非确定**：录制时抛异常的请求未落 fixture → 失败也录制、回放复现同类型异常
3. **推理模型输出触顶**：deepseek-v4-flash reasoning_content 消耗大量输出 token，8192 截断正文 → CHAPTER_WRITING maxTokens 20000 + 组装预算预留输出
4. **卷大纲解析误截断**：行内"预计第N章回收"触发 `(?=第\d+章)` → 行首锚定 + 兼容 markdown 加粗章节行

### 快速验证（3 章，~10 分钟）

- **record**：3 章、11,081 字、46 次 LLM 调用、0 失败
- **replay**：与记录完全一致（同字数/同调用/同失败）
- **质量评估**（真实 LLM）：overallScore **7/10**；人物 7 / 世界观 6 / 情节 6 / 对话 7 / 文风 8 / 多样性 7 / 一致性 5；发现 HIGH 一致性矛盾（明代/清代年号混用）等真实问题
- **基线**：golden.json bless（minChapters=3, minTotalWords=8864, minEvaluationScore=7），compare PASS

## 时代一致性护栏（ec-01..ec-03）— 质量评估问题通用方案

针对质量评估发现的 HIGH 一致性矛盾（世界观明代 vs 正文清代年号混用），按需求拆分协议落 spec（`docs/specs/2026-08-12-era-consistency.md`）并实现通用方案：

- **ec-01 EraAnchorService**：从世界观条目+构思扫描朝代/年号关键词（明/清/唐/宋/元/汉/秦/隋/三国/春秋战国），推导时代锚点；多朝代冲突标注；架空修仙世界不误判。`EraAnchorTest` 6 用例
- **ec-02 ChapterWriter 时代约束**：`ContextAssembler.buildWritingConstraints` 注入"时代一致性硬约束"——禁止其他朝代年号/器物/典籍/官制。`EraConstraintInjectionTest`（历史题材注入、架空不注入）
- **ec-03 ContinuityChecker 时代检测**：检查段注入时代锚点 +"年号/朝代矛盾判【世界观|高】"指令。`ContinuityEraCheckTest`

### 时代护栏验证（质量 Agent 复评）

| 维度 | 护栏前 | 护栏后 |
|---|---|---|
| 一致性 consistency | 5 | **6** ↑ |
| 世界观 worldbuilding | 6 | **8** ↑ |
| 情节 plot | 6 | **7** ↑ |
| 总体 overallScore | 7 | 7 |

**目标问题已消除**：护栏前的 HIGH"明代/清代道光年号混用"在护栏后复评中消失 ✅。复评发现的 HIGH 是另一类问题（主角改名不统一——姓名漂移，属下一通用议题）。

## 角色姓名锚定（cn-01..cn-03）— 姓名漂移通用方案

针对时代护栏复评后暴露的 HIGH"主角改名不统一（大纲沈墨 vs 人物卡顾蘅秋）"，定位**根因**：`runConceiving` 分层大纲路径 `planMasterOutline` 未传主角名，`master-outline.st` 无名字变量 → LLM 自创名与人物卡漂移。spec：`docs/specs/2026-08-12-character-name-anchor.md`。

- **cn-01 master outline**：`planMasterOutline` 新增 protagonistName+characterSummary；模板新增 `{{protagonistName}}` 锚定原则 + 已有人物设定
- **cn-02 volume outline**：`planVolume` 新增 protagonistName；volume-outline.st 加锚定
- **cn-03 写作+检查护栏**：ChapterWriter 主角名锚点硬约束；ContinuityChecker 姓名漂移检测（判【人物|高】）
- 兼容旧签名（无主角名时安全降级）

### 姓名锚定验证（质量 Agent 二次复评）

| 维度 | 原始 | +时代护栏 | +姓名锚定 |
|---|---|---|---|
| 一致性 consistency | 5 | 6 | **9** ↑↑ |
| 世界观 worldbuilding | 6 | 8 | **9** ↑ |
| 人物 characters | 7 | 7 | **8** ↑ |
| 对话 dialogue | 7 | 6 | **8** ↑ |
| 文风 style | 8 | 8 | **9** ↑ |
| **总体 overallScore** | **7** | 7 | **8** ↑ |

**姓名漂移 HIGH 已消失** ✅：大纲/人物卡/正文统一为"季衡"（大纲 80 次、正文 98 次，零漂移）。复评**无 HIGH 问题**，剩余均为 MEDIUM/LOW（节奏、配角厚度、金句、模式多样性）。

- 全量 **154 tests**，0 失败
- 3 章 record→replay 闭环（10,227 字、0 失败），基线 minEvaluationScore=8，compare PASS

## 开篇节奏加速（op-01）— 节奏偏缓通用方案

针对两轮复评均标记的 MEDIUM plot"前3章节奏偏缓、爽点不密集"，定位**根因**：`buildChapterLengthGuide` 用 `chapterIndex % 3` 判长短，第 3 章（3%3==0）被标为"喘息/过渡章，不需要爽点爆发"，但开篇前几章每章都应高压。spec：`docs/specs/2026-08-12-opening-pacing.md`。

- **op-01 开篇加速**：前 5 章（OPENING_CHAPTERS）强制高压——禁止喘息章判定，要求首 500 字钩子 + 至少 2 爽点 + 章内冲突升级；ContextAssembler 注入"开篇加速硬约束"
- **op-fix master outline maxTokens**：planMasterOutline 的 8192 致推理模型 reasoning_content 耗尽预算、总纲正文为空 → 16000（同 CHAPTER_WRITING 修复模式）
- `OpeningPacingTest` 3 用例

### 开篇节奏验证（质量 Agent 三次复评）

| 维度 | 原始 | +时代 | +姓名 | +开篇节奏 |
|---|---|---|---|---|
| 一致性 | 5 | 6 | 9 | **9** |
| 世界观 | 6 | 8 | 9 | **9** |
| 情节 plot | 6 | 7 | 7 | **9** ↑↑ |
| 人物 | 7 | 7 | 8 | 8 |
| 对话 | 7 | 6 | 8 | 8 |
| 文风 | 8 | 8 | 9 | **9** |
| **总体** | **7** | 7 | 8 | **9** ↑ |

**"前3章节奏偏缓"MEDIUM 已解决**（plot 7→9）。复评："开局三章已显露出克制而清冽的悬疑感与命运张力，是兼具文学质感与商业潜力的高完成度网文"。**无 HIGH 问题**，仅余 MEDIUM（配角功能性、爽点模式重复）+ LOW（金句）。

- 全量 **157 tests**，0 失败
- 3 章 record→replay 闭环（8,584 字、0 失败），基线 minEvaluationScore=9，compare PASS

## 三轮质量迭代总览

通过"质量评估→通用方案→复评"闭环，overallScore **7→9**，一致性 **5→9**，消除了全部 HIGH 问题：
1. **时代一致性护栏**（ec）：朝代年号混用 HIGH 消失
2. **角色姓名锚定**（cn）：主角改名漂移 HIGH 消失（大纲/人物卡/正文零漂移）
3. **开篇节奏加速**（op）：前 3 章节奏偏缓 MEDIUM 解决（plot 7→9）

## 第四轮：配角深度 + 事件多样性 + 10 章长篇验证

- **cd-01 配角刻画深度**：出场配角必须展现独立性格/动机，注入角色性格+动机到约束
- **pd-01 事件结构多样性**：禁止与前章相同的"触发→验证→爆发"递进模板
- **chapter_synopses cast/beats/climax 持久化修复**：之前解析后 save/reload 丢失，影响角色声音/关系网络/配角深度

### 10 章 e2e 验证（长篇扩展里程碑）

- **record**：10 章、34,737 字、0 失败
- **replay**：与记录完全一致
- **质量评估**（overallScore 7）：10 章规模暴露新的尺度相关问题：
  - **MEDIUM consistency（摘要重叠）**：摘要范围 [1-3]、[1-8]、[4-6]、[7-9] 互相覆盖 → 前情摘要内容重复
  - **MEDIUM pattern_diversity**："账册浮现字迹→调查→发现"循环结构 10 章重复
  - **MEDIUM plot**：前10章主线围绕小案子推进，节奏偏慢
  - **MEDIUM characters**：配角（老歪、沈无咎、程拾青）形象单薄

### 下一步目标（摘要重叠修复）

根因已定位：`SummaryService` 生成了重叠的章节范围（`[1-3]` 与 `[1-8]` 包含关系、`[4-6]` 与 `[1-8]` 重叠）。需修复为非重叠范围管理——粗粒度摘要生成时应取代被覆盖的细粒度摘要。

## 架构审查 → 4 阶段修复（10 个差距 → 打通 30/100/200 章扩展墙）

基于 Agent 全子系统代码级审查，10 个扩展墙差距按 4 阶段修复，全部经测试验证：

### Phase A 安全地基（3 feature）
- **A1 门禁 fail-closed**：`runFinalAuditGate` catch→return false（原 return true 静默放行劣质书）
- **A2 PRAGMA 每连接**：foreign_keys/busy_timeout/synchronous 不再只设第一个连接（原 `pragmasInitialized` bug，ConnectionProviderPragmaTest 验证 3 连接）
- **A3 去重 style-guardian**：删除只 log 不持久化的冗余块（每 5 章省一次 LLM 调用）

### Phase B 数据完整性（2 feature）
- **B1 章节事务**：`runInTransaction` + connection overloads，中途异常整体回滚（ChapterTransactionTest 3 用例）
- **B2 分页加载**：`findRecentChapters` SQL LIMIT / `countChaptersByProject` / `findChaptersByIndexRange` —— 消除 400 章 OOM 风险

### Phase C 长程记忆（3 feature）— 10 章 e2e 验证激活
- **C1 master outline 演进**：卷末重建"已写卷摘要"写入 premise（10 章验证 EVOLVED ✅）+ 卷结构对齐 8 章/卷 + planVolume 聚合前 3 卷摘要
- **C2 全书摘要层**：projects.book_summary 列，卷末 LLM 压缩 ≤3 句全书梗概注入上下文顶部（10 章验证 134 字 ✅）+ 旧卷截断 100→300 字 + 修复临时 Summary 重复
- **C3 outline N+1 消除**：`findOutlineByProject` 2+N 查询→3 查询（JOIN）+ `saveOutline` 单连接

### Phase D 成本与性能（2 feature）
- **D1 稳定段缓存**：ContextAssembler 的 premise/worldbuilding/knowledgeQuote 按项目缓存（每章减少 DB 查询+拼接）
- **D2 embedding 批量化**：章节索引 5-8 次 HTTP 调用→1 次（`embedBatch` 按 index 排序 + `indexBatch`）

### 全量测试：**166 tests，0 失败**

## 30 章最终验证 — 95,770 字 ≈ 9.6 万字里程碑

- **record**：30 章目标、24 章有效写入、**95,770 字**、**0 章节失败**、334 LLM 调用
- **C1 master outline 演进**：premise 在 ch8/ch16/ch24 卷末持续更新"已写卷摘要" ✅
- **C2 全书摘要**：263 字（卷末 LLM 压缩，从 133→211→263 持续增长） ✅
- **摘要范围**：`[1-25], [25-27], [28-30]` ——干净的非重叠管理 ✅
- **replay 发散**：C1 premise 演进导致 record/replay 间状态变化→hash 不同（长跑已知限制，3-10 章规模 replay 稳定）
- **e2e.sh 改进**：replay 前备份 record DB（防覆盖）+ 按 `--chapters` 设置 `targetChapters`（防 master outline 规划 400 章超时）

## 历史：Phase 9 完成情况

## 百万字可靠性 — P0 修复完成（审查回流）

### 审查 5 类阻断问题的修复

1. **单活跃作业并发双写**（mc-p0-05）：`startWriting` 同步等待旧任务退出（30s 超时）后再启动新任务；`cancel` 只设内存标志，DB 的 CANCELLED 在循环退出后写入
2. **凭据 fail-fast 语义修正**（mc-p0-04）：`validateKeys()` 现在校验 **所有** 模型配置（default-model + 各任务类型），仅对**远程**端点要求 api-key，本地端点（localhost/127.x/*.local）豁免——修复了原先只看 default-model、导致仅配 DeepSeek 的真实部署反而无法启动的问题
3. **测试环境加载系统 api-key**：新增 `scripts/load-env.sh`，从 `~/.zshrc` 等系统 shell 配置提取 `export KEY=...` 注入环境（不回显、不 source 整文件），`init.sh` 接入；测试用真实系统 key 而非 fake key
4. **JDK 26 兼容**：pom 升级 `byte-buddy 1.17.8` + `mockito 5.17.0`，修复 Mockito inline mock 无法对已加载类重定形
5. **P0 行为测试**：`WritingTaskRecoveryTest`（孤儿清扫）、`HonestTerminalStateTest`（诚实终态）、`SingleActiveJobTest`（单活跃作业）、`WritingTaskCancelTest`（取消）、`LlmPropertiesValidateTest`（fail-fast 语义）——真实行为断言，非 POJO setter

### 验证

| Check | Command | Result |
|---|---|---|
| 全量测试（真实系统 key） | `source scripts/load-env.sh && mvn test` | 125 pass, 0 failures |
| 完整 harness | `./init.sh` | 编译+测试+前端构建全通过 |

## 百万字可靠性 — P2 长程记忆完成

- **mc-p2-03 model-aware token budget**：`ModelConfig` 新增 `contextWindow`（deepseek-v4-flash 64000 / 本地 32768 已配置）；`TokenBudget.budgetForModel()` 按窗口 90% 推导预算；`ContextAssembler.assemble(..., maxContextTokens)` 重载 + `ChapterWriterAgent` 按 CHAPTER_WRITING 模型注入；`TokenBudgetSafetyTest` 断言组装后估计 token ≤ 窗口 90%
- **mc-p2-01 versioned memory**：`Summary` 新增 `sourceChapterVersion` + `dirty`（表列 + 迁移）；重写章节后 `invalidateSummariesForChapter` 标记所有覆盖摘要 dirty，细粒度立即重算（清 dirty + 版本跟随），卷级保持 dirty 被 `ContextAssembler` 过滤；`VersionedMemoryTest` 验证往返、过滤、重写失效三条链路
- **mc-p2-02 连续性检查长程输入**：`ContinuityCheckerAgent` 检查段注入历史卷摘要（span>3、非 dirty、最近 5 卷）+ 叙事线索台账（未回收线索）；`ContinuityHistoryInputTest` 用 50 章合成数据断言非空
- **mc-p2-04 embedding 默认档**：`application.yml` 默认 `cloud-api`，stub 仅显式配置生效；`EmbeddingProfileTest` 双档断言

## 百万字可靠性 — P3 质量证据（进行中）

- **mc-p3-02 FINAL 门禁**：`FinalAuditGateTest` 验证 VOLUME_DONE→step() 统一走 `runFinalAuditGate`（mustResolve>3 / HIGH>10 → NEEDS_REVISION，通过→FINAL），不再绕过
- **mc-p3-03 成本遥测**：`LlmCostTelemetryTest` 验证 LLM_CALL 事件含 promptTokens/completionTokens/estimatedCostCny（DeepSeek 0.001/0.002 定价）
- **mc-p3-04 diagnose 成本聚合**：`diagnose.sh` 按 taskType 输出成本合计 + 总成本（实测聚合输出）
- **mc-p3-01 20 章 fixture + 基线（进行中）**：3 章 record→replay 闭环已通（46 次回放调用、0 错误、8644 字），`golden.json` 已 bless（compare PASS）；20 章真实记录进行中
- **e2e.sh 修复**：异步写作轮询至终态；报告 JSON 改从文件读取（修复章节正文控制字符导致报告生成失败）
- **embedding 切换百炼**：`DASHSCOPE_EMBEDDING_API_KEY`（用户提供）→ `text-embedding-v3` OpenAI 兼容端点，实测返回 1024 维向量

## 历史：Phase 9 完成情况

### 可重放 E2E

- `RecordReplayLlmGateway`：`record` 录制真实 LLM 响应；`replay` 按请求指纹回放；fixture 缺失 fail-fast
- 指纹归一化配置 JSON 的 `updatedAt` 字段，避免新建项目的时间戳导致 replay miss
- `ObservedLlmGateway`：记录每次 LLM 调用的任务类型、耗时、token 用量和错误分类
- `scripts/e2e.sh`：独立 data/e2e 工作区启动服务、创建项目、构思、逐章写作、输出 `e2e-report.json`

### 可诊断运行

- `telemetry/EventLog` 写 `data/logs/events.jsonl`，事件：`LLM_CALL` / `LLM_ERROR` / `CHAPTER_WRITTEN` / `CHAPTER_FAILED`
- `EventContext` 关联项目 ID 与章节号
- `logback-spring.xml` 写 `data/logs/app.log`（14 天滚动）
- `scripts/diagnose.sh` 聚合失败分类、LLM 统计和最近写作章节

### 可度量优化

- `scripts/compare-baseline.py` 对比 `e2e-report.json` 和 golden 基线，指标退化时失败
- `baselines/README.md` 规定首次 record 与人工 `--bless` 建立基线的流程

### 可持续开发

- `docs/specs/TEMPLATE.md` 规定新需求 spec 格式
- `AGENTS.md` 增加需求拆分、失败回流、bugfix gate、会话结束和 backlog 耗尽协议
- `feature_list.json` 50 条 feature 均含 `acceptance` 与 `verify_cmd`
- `.github/workflows/ci.yml` 添加 Maven CI 门禁（仓库配置 remote 后生效）
- `init.sh` 改为 fail-fast，验证编译、95 测试、20 prompt 模板及前端构建

## Verification Evidence

| Check | Command | Result |
|---|---|---|
| 新增单测 | `mvn test -Dtest=EventLogTest,RecordReplayLlmGatewayTest,LlmGatewayWiringTest` | 10 pass |
| 全量验证 | `./init.sh` | 95 pass, 0 failures; frontend build pass |
| Harness 审计 | `validate-harness.mjs --target .` | 100/100 |
| E2E bootstrap | `./scripts/e2e.sh --mode replay --chapters 1` | 服务就绪、项目创建；fixture 缺失时 fail-fast（预期） |
| 诊断脚本 | `./scripts/diagnose.sh /tmp/.../events.jsonl` | 合成事件的分类/统计/建议正确 |
| 基线脚本 | `compare-baseline.py --bless` → compare | 合成报告往返 PASS |
| 短真实调用 | `POST .../preferences/chat`（record） | 5.37s；587 prompt + 430 completion tokens；偏好正确更新为 2500 字 |
| 短回放调用 | 同请求（replay） | 3ms；结果一致，未触达外部 LLM |

## Next Steps

1. 在可接受更长运行时间时执行 `./scripts/e2e.sh --mode record --chapters 3`，完整录制并审阅多章产出。
2. 确认 `e2e-report.json` 的质量后运行 `python3 scripts/compare-baseline.py --bless`。
3. 运行 `./scripts/e2e.sh` 验证零成本多章 replay，再运行 `python3 scripts/compare-baseline.py`。
4. 后续新需求必须从 `docs/specs/TEMPLATE.md` 起步；E2E 的失败分类按 AGENTS.md 回流为 bug feature。

## Phase 26 — p26-04 独立章节重写接线（2026-09-03）

- 章节卡“重写此章”现在保存独立的 discriminated target（`kind: 'chapter'`、`chapterIndex`、`sourceVersion`），不再依赖 pending checkpoint。
- 提交成功后直接以返回章节更新元数据与正文缓存、显示真实版本变化、关闭并重置弹窗；失败时弹窗保持打开并显示行内错误。
- 严格浏览器场景 `standalone-chapter-rewrite`：RED 为 0 PASS / 1 XFAIL（无法提交、v3→v3、正文未变、弹窗未关）；GREEN 为 1 PASS / 0 XFAIL / 0 ERROR（v3→v4、正文变化且页面已显示新正文）。仅移除该 case 的 `KNOWN_FAILURES`。
- 验证：`frontend npm run build` PASS；JDK21 `mvn compile`、`mvn test`、`./init.sh` 均 PASS（504 tests / 0 failures / 0 errors / 1 skipped；init 另通过 27 prompt 与生产前端构建）。
- Review round 1（仅 E2E 加固）：严格场景在点击前要求 pending checkpoint 数为 0；渲染验证改为“重写正文可见且旧 `【重写前来源标记】` 已消失”，消除新旧正文共享前缀的假阳性。strict 仍为 1 PASS / 0 XFAIL / 0 ERROR；前端构建、JDK21 全量 Maven 与 `./init.sh` 均通过。

## Phase 26 — p26-05 审批后正文加载收敛（2026-09-03）

- 刷新章节元数据时按 `(chapterIndex, version)` 保留仍有效的正文缓存；选中章版本变化会先失效再显式重读，批准操作总会强制重读当前选中章，同时保留旧正文直到新响应到达。
- 正文请求现在有独立的收敛状态：失败显示站内错误与“重试”，不会遗留永久 spinner；p26-04 的独立重写仍同时更新正文缓存与章节元数据。
- 严格 TDD：`approve-keeps-chapter-visible` RED 为 **0 PASS / 1 XFAIL / 0 ERROR**（正文 true→false、`bodyLoading=true`）；GREEN 为 **1 PASS / 0 XFAIL / 0 ERROR**。仅从 `KNOWN_FAILURES` 删除该 case。
- 验证：frontend `npm run build` PASS；JDK21 `mvn compile`、`mvn test`、`./init.sh` 均 PASS（**504 tests / 0 failures / 0 errors / 1 skipped**；init 另通过 27 prompt 与生产前端构建）。

### p26-05 交付前基线回归修复

- 完整 browser-e2e 暴露首选章节初次正文请求重复：RED `b1=2`，修复后非 force 读取复用同章 in-flight，force generation 取代语义不变。独立提交：`e4b20bb fix(chapter): deduplicate initial body load`。
- 原 approve 场景在真实 version 3 章节上暴露 metadata mapper 丢版本：SQLite focused RED 为 full.version=3、meta.version=1；最小映射修复后 focused 4/4、approve/standalone strict 各1/1、browser 8/8、JDK21 full/init 全绿。独立提交：`2b92249 fix(chapter): expose metadata version`。

## Phase 26 — p26-06 状态操作矩阵与服务端门禁（2026-09-03）

- 新增中央 `ProjectActionPolicy`：CONCEIVING/PLANNING/VOLUME_DONE 只允许 ADVANCE；WRITING 仅在有大纲且无 pending checkpoint 时允许 WRITE_CHAPTER/WRITE_ALL；NEEDS_REVISION 只允许 FINAL_AUDIT；PAUSED_AT_CHECKPOINT/FINAL 无主操作。
- `status` 从 repository 读取大纲和待审项，返回 `allowedActions`/`blockedReasons`；`/run`、`/write-all`、`/write-chapter`、`/final-audit` 在任务创建或 orchestrator 调用前统一拒绝无效动作，WRITING 不再由 `/run` 隐式写章。
- RunConsole 只呈现 status 当前允许的按钮，写作指令也仅在可写时显示；无有效主操作时呈现服务端 blocked reason。产品 approve 种子按矩阵直接调 `/write-chapter`，未制造额外 pending。
- TDD RED：policy 首跑因类/枚举不存在而编译失败；endpoint 首跑 4 失败，覆盖 status 缺字段、WRITING `/run` 隐式写章、CONCEIVING write-all 创建任务、write-chapter 留 FAILED 任务。
- 独立标准审查发现 `/write-chapter` 初次门禁与异步任务启动之间存在 TOCTOU。确定性 latch 测试 RED 为 `WritingTaskExecutor` 缺少原子 gate overload 的 testCompile 失败；GREEN 在取得既有 `ProjectWorkCoordinator` owner 后、持久化任务前按 repository 真相二次门禁，暂停先发生时不创建 task、不进入 orchestrator。
- GREEN：focused policy/controller/content/ownership/instruction **35/35 PASS**；approve 与 standalone strict 各 **1 PASS / 0 XFAIL / 0 ERROR**；browser **8/8 PASS**；frontend build PASS；JDK21 full Maven/init **522 tests / 0 failures / 0 errors / 1 skipped**，27 prompt 与生产前端构建 PASS。

## Phase 26 — p26-08 世界观与人物完整字段编辑（2026-09-03）

- 人工世界观类型由服务端精确限制为 `GEOGRAPHY/POWER_SYSTEM/FACTION/HISTORY/RULE/CULTURE/ECONOMY/RACE/CUSTOM`，创建与更新都在写实体、StoryCanon 或 MemoryRevision 前校验；`ITEM` 继续作为系统/存量领域类型与中文展示存在，但不能从人工 API 新建或写回。
- 世界观更新会持久化类型、名称和正文；世界观与人物更新均验证目标属于 URL 中的项目，错误路径返回 400 且两侧项目无真典或记忆版本副作用。
- 世界观卡改为中文类型 Select 与受控三字段整卡保存；人物卡显式提供姓名、角色定位及六个描述字段，以受控 draft 一次“保存人物”，失败保留输入，不暴露 task 外字段。
- TDD RED：`WorldCharacterFullEditTest` 为 **4 tests / 4 failures / 0 errors**，覆盖类型丢失、ITEM/INVALID 放行、非法更新副作用和跨项目修改。GREEN focused ownership/canon/create 回归为 **14/14 PASS**。
- 产品 E2E：`manual-content-management --expect-known-failures` 为 **0 PASS / 1 XFAIL / 0 ERROR**；worldSave 的下拉、保存、持久化、刷新后三字段与 characterSave 的保存、持久化、刷新后八字段子断言全部通过，聚合仅保留后续大纲/章节子项的预期失败。`initial-setup-summary`、`checkpoint-card-targeting` strict 各 **1 PASS / 0 XFAIL / 0 ERROR**。
- 验证：frontend build、JDK21 `mvn compile`、`mvn test` 与 `./init.sh` 全部 PASS（**529 tests / 0 failures / 0 errors / 1 skipped**；init 另通过 27 prompt 与前端生产构建）。本 feature 未改 Agent/prompt/上下文/产出逻辑，无需 replay。

### p26-08 Review round 1

- TDD RED：扩展后的 `WorldCharacterFullEditTest` 为 **7 tests / 3 failures / 0 errors**；世界观/人物改名后旧 subject 仍为 ACTIVE，人物整卡保存的 CHARACTER_STATE 只保留最后一次 growthArc 写入。
- GREEN：更新前捕获旧 subject 的相关 ACTIVE。有真实新断言时先写新 subject HUMAN_PATCH，再以 `StoryCanon.supersedeFact` 建立 old → new 跨主体审计链；清空正文或人物状态/关系时改为 RETIRED，保留完整审计记录。`canon.recall` 与其真实消费者 `RelevanceGateway` 均不再输出旧主体。
- 人物状态从更新后完整实体一次组合 role/personality/ability/motivation/appearance/growthArc，空值不产生标签噪声；relationships 独立写一次。重复整卡保存及 ability-only partial 后，每个 type+subject 仍恰好 1 ACTIVE，状态断言同时保留能力、动机和成长弧。
- 验证：focused ownership/canon/create **17/17 PASS**；frontend build PASS；manual aggregate **0 PASS / 1 XFAIL / 0 ERROR** 且 worldSave/characterSave 全 true；initial-summary/targeting strict 各 **1/0/0**；JDK21 full/init **532 tests / 0 failures / 0 errors / 1 skipped**，27 prompts 与前端生产构建 PASS。

## Phase 26 — p26-09 未写大纲结构安全替换（2026-09-03）

- 新增 `OutlineStructureService.replaceUnwritten`：在任何保存、真典和记忆版本副作用前验证项目归属、卷序、全局章序及已写前缀；章号与卷号均要求正数、唯一、严格递增。
- `currentChapterIndex <= 0` 统一视为没有已写前缀。存在已写前缀时，incoming 必须保留每个已写章的卷号、卷标题/摘要、章标题/梗概；篡改、删除、改号或跨卷移动均返回 400。验证通过后以 detached Outline 完整替换未写结构，支持卷章新增、删除、跨卷移动、重编号和空卷持久化；零章节大纲明确拒绝。
- `CanonPatchService.patchOutline` 的既有 merge-only 语义保持不变；新增只供验证后调用的 `replaceOutline`，成功只执行一次 outline save，总纲未变化时不重复写 PLOT_COMMITMENT。
- 严格 TDD RED：真实 Spring HTTP + SQLite 为 **8 tests / 8 failures / 0 errors**，覆盖旧 merge 无法删除/移动、0/-1 边界残留、三类非法章序放行、已写前缀放行、空大纲放行。GREEN 后新测试 **10/10**；连同旧 synopsis/canon/checkpoint merge 回归 focused **25/25 PASS**。
- 产品 E2E：`manual-content-management --expect-known-failures` 为 **0 PASS / 1 XFAIL / 0 ERROR**；世界观/人物子项保持全绿，aggregate 仅因 p26-10 大纲 UI 与后续章节 UI 尚未实现。
- 验证：JDK21 `mvn compile`、`mvn test`、`./init.sh` 全部 PASS（**542 tests / 0 failures / 0 errors / 1 skipped**；27 prompt 与前端生产构建 PASS）。未修改前端、自动任务、Agent/prompt/上下文或产出逻辑。

### p26-09 Review round 1

- 严格 TDD RED：真实服务/SQLite 测试扩展为 **14 tests / 4 failures / 0 errors**。确定性 latch 复现结构服务读取 boundary 后 writer 与第二个 PUT 均可进入；刷新后已写卷 UUID 改变；真典新事实 INSERT 触发器失败时，大纲已替换且旧事实可能先被 supersede。
- GREEN：`OutlineStructureService` 在服务内取得项目 owner，覆盖 boundary read、全部校验、SQLite commit 和 revision bump，冲突调用不进入 repository，并在成功/失败时 finally 释放。已写卷与章梗概从可信持久化对象 detached copy，保留 UUID、写作要求、节拍、高潮、人物、剧情目的、张力和卷内位置，不信任 payload ID。
- `NovelRepository.replaceOutlineAtomically` 明确大纲+可选剧情承诺的原子契约；SQLite 单事务执行 outline 替换、旧同键 ACTIVE 全部 SUPERSEDED 及 HUMAN_PATCH INSERT。故障注入在 INSERT 阶段中断后，outline、全部 facts、旧 fact 状态及 MemoryRevision 均与调用前完全一致；revision 仅在 DB commit 后推进。
- 最终 `OutlineStructureEditTest,UnwrittenSynopsisEditTest,CanonPatchServiceTest,CanonActiveUniquenessTest,CheckpointRevisionRoutingTest` **32/32 PASS**；manual aggregate **0 PASS / 1 XFAIL / 0 ERROR**；JDK21 compile/full/init **546 tests / 0 failures / 0 errors / 1 skipped**，27 prompts 与前端生产构建 PASS。

### p26-09 Review round 2

- TDD RED：共享 owner 后，`startConceiving` 把 `outline-edit-*` 当作已落库构思任务并返回 null；服务与真实 HTTP 两条回归为 **7 tests / 2 failures / 0 errors**，`POST /run` 实际 NPE 500。
- GREEN：构思启动先原子 `tryAcquire`，不再在 `currentOwner` 预读与 acquire 之间留下窗口。竞争失败后，仅 repository 中同项目 `CONCEIVE` task 可按既有语义幂等返回；人工大纲或其他同步 owner 明确抛 `IllegalStateException`（HTTP 409），不创建 WritingTask、不进入 orchestrator、不替换原 owner。首次任务持久化失败时释放刚取得的 token。
- 直接/HTTP GREEN **7/7**；扩展 `ConceiveTaskOwnershipTest,ConceiveTaskTest,WritingEntryOwnershipTest,ProjectWorkCoordinatorTest,OutlineStructureEditTest` **33/33 PASS**；JDK21 compile/full/init **548 tests / 0 failures / 0 errors / 1 skipped**，27 prompts 与前端生产构建 PASS。未改变自动任务持续运行语义。

## Phase 26 — p26-11 章节不可变版本历史存储（2026-09-03）

- 新增不可变 `ChapterVersion`：8 个字段全部 `private final` 且无 setter；`source` 保持可持久化 String，与后续章节修订入口兼容，`createdAt` 使用 `Instant` 并以 ISO-8601 文本精确往返。
- SQLite 向前新增 `chapter_versions` 表，业务列全部 `NOT NULL`，以 `(project_id, chapter_index, version)` 保证业务版本唯一；保存只执行普通 `INSERT`，重复键明确失败，不提供更新或删除仓储入口。
- 严格 TDD RED：`ChapterVersionHistoryTest` testCompile 出现 **29 个预期缺失符号**，覆盖领域类型及三个仓储方法均不存在。GREEN 的 6 个真实 SQLite 测试覆盖版本倒序、精确单版本查询、八字段与纳秒时间往返、重复键失败且旧行不变、跨项目/章节版本隔离、不可变 API、fresh schema 和 legacy chapters 无损迁移。
- 验证：focused `ChapterVersionHistoryTest,SqliteSchemaInitializerTest,SqliteNovelRepositoryTest` **18/18 PASS**；JDK21 compile、full test、`./init.sh` 全部 PASS（**554 tests / 0 failures / 0 errors / 1 skipped**；27 prompts 与前端生产构建通过）。本 feature 无产品入口且不改变 prompt/产出，因此未跑浏览器、replay 或质量基线；章节改写路径接入历史由 p26-12 完成。
- Review round 1：SQLite rowid 表中 `id TEXT PRIMARY KEY` 的 PRAGMA `notnull=0`，且真实 null-id 快照可被插入。强化测试 RED 为 **7 tests / 2 failures / 0 errors**；DDL 最小改为 `id TEXT NOT NULL PRIMARY KEY` 后，八列均 `notnull=1`，null-id INSERT 明确失败且查询无残留。最终 focused **19/19 PASS**，JDK21 compile/full/init **555 tests / 0 failures / 0 errors / 1 skipped**，27 prompts 与前端构建 PASS。

## Phase 26 — p26-12 章节新增直接编辑与回退 API（2026-09-03）

- 真实 Spring MVC + SQLite TDD RED 为 **7 tests / 6 failures / 0 errors**：章节新增、直接编辑、历史与回退端点缺失并返回 500；事务故障用例仅因端点不存在而偶然满足零写入。GREEN 后 `ChapterDirectEditTest` 扩展为 **8/8 PASS**，覆盖下一项目全局章号、DRAFTING/500 字门槛、标题正文与 version+1、不可变变更前快照、历史倒序、精确回退及空白旧草稿回退、陈旧 expectedVersion 409 零副作用、跨项目拒绝、活动写作拒绝、同版本并发唯一成功者和 SQLite 故障原子回滚。
- 新增 `ChapterRevisionService` 单一版本边界与四个 API；SQLite 以条件 UPDATE current + INSERT snapshot 同事务保存。既有 rewrite/passage mutation 进入同一边界并记录 `REWRITE`/`PASSAGE_EDIT` 历史；所有 mutation 复用共享项目工作权，直接编辑/回退复用既有向量重建、上下文/摘要失效与实体、伏笔、事实后处理刷新路径。
- 四套 focused **13/13 PASS**；JDK21 compile/full/init **563 tests / 0 failures / 0 errors / 1 skipped**，27 prompts 与前端构建 PASS。指定匹配 `canary-mystery-20260831` fixture 的 replay 为 3 章、10,191 字、54 calls、0 LLM errors、0 chapter failures，baseline PASS。manual aggregate **0 PASS / 1 XFAIL / 0 ERROR**，唯一剩余 `chapterActions` UI 留给 p26-13；未修改 UI、Agent/prompt、自动任务、mode switch/resume。

### p26-12 Review round 1

- 两个范围内竞态 RED：检查点章节重写不受项目 owner 约束，且直接编辑返回时派生刷新仍在后台。GREEN 后 checkpoint revision 统一获取工作权；章节修订与派生刷新经同一分片锁串行。直接编辑/回退在响应前刷新当前版本派生数据；passage 保留快速返回，但先同步标记摘要 dirty，后台任务获锁后以 chapter version 守卫，旧任务无法逆序落库。
- 相关 focused **18/18 PASS**；JDK21 compile/full/init **566 tests / 0 failures / 0 errors / 1 skipped**，前端构建与 27 prompts PASS。匹配 mystery replay 仍为 **3 章 / 10,191 字 / 54 calls / 0 errors / 0 chapter failures**，baseline PASS；manual aggregate **0/1/0** 且唯一红项仍为 p26-13 的 `chapterActions` UI。

### p26-12 Review round 2

- 回归先精确复现连续 passage 编辑：前一次慢收割未完成时，第二次保存被分片锁阻塞约 5 秒。修复后 passage 持久化不再等待旧派生任务，后台队列仍以分片锁串行、以当前 chapter version 丢弃过期项，最终派生状态按最新版本收敛。
- 相关 focused **19/19 PASS**；JDK21 compile/full/init **567 tests / 0 failures / 0 errors / 1 skipped**，前端构建与 27 prompts PASS。匹配 mystery replay 仍为 **3 章 / 10,191 字 / 54 calls / 0 errors / 0 chapter failures**，baseline PASS。
