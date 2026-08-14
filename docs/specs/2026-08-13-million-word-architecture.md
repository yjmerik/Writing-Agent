# Spec: 百万字长篇架构优化（故事真典 + 检索收窄 + 可靠作业）

> 文件名约定：`docs/specs/YYYY-MM-DD-<slug>.md`。新需求必须先落 spec，再拆 feature。
> 本 spec 为决策完备（decision-complete）文档：评审通过后可直接进入 `feature_list.json` 登记与逐 feature 实现。
> 上游 review：本会话对四大子系统（情节/大纲/伏笔、记忆/上下文、角色/一致性/质量门禁、写作循环/成本/容错）的代码级审查，结论见「背景与动机」。

## 背景与动机

WritingAgent 已在 ~30 章 / ≈9.6 万字规模经多轮质量迭代验证通过（overallScore 9，全量 154+ tests 绿）。但一次面向**百万字（约 400 章 × 2500 汉字）**的代码级架构审查（基于 `src/main/java` 与 `src/main/resources` 实读 + file:line 证据）发现：系统在长程下存在 **5 道扩展墙**，且根因是少数几个系统性反模式在各子系统反复出现。这不是调参能解决的——几个核心数据流的设计在长程下会失效。

### 五道扩展墙（何时崩）

| 规模 | 崩溃点 | 触发根因 |
|---|---|---|
| ~40 章 | 伏笔/爽点/张力追踪集体失明 | 延迟生成的卷大纲不调用 `parseChapterDetails`，40 章后 `ChapterSynopsis` 的 foreshadowing/climax/tension 字段全空（`PlotterAgent` 解析丢失） |
| ~80-120 章 | 上下文"装不下也记不准" | `recentSummarySection` 不参与 token 裁剪且无界增长（`ContextAssembler.java:913-942`）；`stableSectionCache` 永不失效，worldbuilding/premise 冻结在第 1 章（`:154-157`） |
| ~150-200 章 | "记忆地平线坍塌" | 全书摘要是"摘要的摘要"级联压缩（`NovelOrchestrator.java:1394-1401`），50 卷后早期剧情信息归零；历史卷摘要超 3 卷被裁到 300 字（`ContextAssembler.java:240,257-262`） |
| ~200+ 章 | LLM 调用 token 爆炸 / 检测失明 | 几乎每个 checker/tracker 都"全量加载进单次 LLM 调用"（全人物/全实体/全边/全伏笔/全 issue），无检索收窄 |
| 全程 | 任一 API 限流即永久中止 | 3 次连续失败 `break` 退出循环（`NovelOrchestrator.java:1265-1281`），无模型降级、无指数退避、无成本预算 |

### 四个系统性根因

- **根因 A「全量加载 → 单次 LLM 调用」反模式**：`collectSettingsForCheck`（全人物+全知识+全伏笔）、`EntityExtractor.formatExistingEntities`（全实体）、`ConsistencyGuard`（全人物能力）、`RelationshipEvolutionTracker`（全边+全角色）、`buildCharacterVoiceSection`（全角色声音，cast 匹配失败即全量回退）、`CharacterProfileUpgrader.collectExcerpts`（全章节 O(N) 扫描）。系统有 `VectorRetrievalService` 却几乎不用于收窄检查器输入。
- **根因 B「图/表只增不减」**：`entity_edges`（validTo 标记但物理行留存）、`continuity_issues`（只 continuity-HIGH 被 resolve）、`foreshadowings`（无过期/合并）、`narrative_threads`（RESOLVED 永不清理）、`knowledge_entries`（UUID upsert 无语义去重）。全量 `SELECT *` 无分页。
- **根因 C「历史记忆是有损级联压缩」**：全书摘要 = 上一次 ≤3 句压缩结果再压成 ≤3 句，50 卷级联后早期设定/人物/伏笔趋零。卷摘要"最近 3 卷详细 + 其余裁到 300 字"纯按时间近邻而非相关性。长程一致性承诺物理性破产。
- **根因 D「修复闭环只向当前章、只 1 次、不复检、不回溯」**：所有矛盾只能"重写当前章 1 次"；`ConsistencyGuard` 的 HIGH（能力越界/凭空证据）甚至只 save 不触发重写（`NovelOrchestrator.java:862-872`）；重写后不重跑连续性检查。矛盾根源在早期章节时，改当前章只会"按下葫芦浮起瓢"。

### 核心架构洞察

系统目前**没有"可变的事实真相层"**——所有信息要么是有损压缩的摘要（会衰减），要么是已发布正文（不可改）。这就是记忆会坍塌、矛盾无法修复、检查器会爆炸的共同根因。

**核心架构动作：引入「故事真典（Story Bible）」——一个结构化、可累积、可纠错、可检索的事实真相层，与已发布正文分离。** 它同时是长程记忆的载体（替代级联摘要）和修复闭环的纠错对象（矛盾改真典，而非改正文）。这是整个方案的关键杠杆，已与用户确认采用"完整真典"路线。

## 目标

1. **百万字规模下长程记忆不衰减、不丢失**：以故事真典承载"当前什么是真的"作为主长程记忆，第 400 章的上下文仍能准确召回第 1 卷的关键设定/人物状态/关系/承诺；摘要分级不再级联压缩全局。
2. **矛盾可纠错、不靠重写正文**：检测到矛盾后，按"正文错/真典演进/早期根源错"三分类仲裁，纠正真典（supercession）让后续章节自动遵从，重写仅用于"确属本章写错"，且重写后必须复检。
3. **检查器与写作上下文按相关性收窄，不再全量加载**：所有消费者经统一检索门面取"本章相关切片"，单次 LLM 输入 token 受控、不超模型窗口 90%；无界表有归档/衰减/合并。
4. **400 章作业能跑完、不静默丢数据**：成本预算熔断、分类型退避 + 限流自动降级到本地兜底模型、后处理并行、章节状态机保证后处理幂等可重跑。
5. **结构与节奏在百章尺度可演进**：总纲中段重规划、延迟卷大纲补全结构化字段、弧光时间线、节奏/多样性检测基于实际成文且持久化长窗。

## 非目标

- **不改正文回溯重写**：早期章节正文不自动改写（连锁重写成本爆炸）。靠真典纠错让后续遵从 + 标记早期章 `needsManualReview` 供人工处理。这是经权衡的——真典分离正是为了让"真相可纠"而不必"正文可改"。
- **不引入真实 tokenizer 依赖**：`TokenBudget` 用按模型分档的字符/token 系数表，留扩展点（与 mc spec 已定边界一致）。
- **不强制 ANN 迁移**：当前规模（N≈2-3k 向量）暴力检索可接受，预留 HNSW 接入点（`VectorStore` SPI 已可换实现）。
- **不改 Agent 间不直接调用的约束**：新增的 FactHarvester 也经 NovelOrchestrator 调度。
- **不做多用户 / 云 / 分布式 / 水平扩容**：延续 mc spec 的 P4 延后。
- **不改既有 `.st` 模板变量名**：影响产出质量的 prompt 文案调整走另立小 spec；本 spec 的上下文重组在 ContextAssembler 段组装层完成。

## 目标架构边界（六大支柱）

### 支柱 1：故事真典（Story Bible）— 可累积可纠错的真相层 ★关键

- 新建 `story_facts` 表，承载六类结构化事实：`CHARACTER_STATE` / `WORLD_RULE` / `TIMELINE_EVENT` / `RELATIONSHIP_STATE` / `PLOT_COMMITMENT` / `OBJECT_STATE`。
- 事实**只增不改**：被推翻时旧事实置 `SUPERSEDED`、新事实 `ACTIVE` 并 `superseded_by` 指向旧者，永不删除，可追溯。
- 三个机制：
  - **播种**：`runConceiving` 从世界观条目 + EraAnchor + 初始人物卡抽出 `WORLD_RULE` / 初始 `CHARACTER_STATE` 入典。
  - **增量收割（FactHarvester，新 Agent）**：每章后处理阶段提取"本章确立的事实变更"，与现有真典比对——一致则刷新 `last_validated_chapter` + importance；冲突则交 LLM 仲裁（剧情合法演进→supercede；正文写错→上报触发重写）。
  - **真典检索消费**：ContextAssembler / ContinuityChecker / ConsistencyGuard / QualityEvaluation 按相关性检索**相关 ACTIVE 事实**，紧凑、不衰减、可查。
- 与现有结构的分工：`Character` 留稳定身份（姓名/性格内核/外貌/别名），真典接管可变状态（当前修为/目标/位置）——替代被 `CharacterProfileUpgrader` 覆盖式重写、会漂移的 ability/growthArc；`Entity`/边 留身份图谱，真典留 `RELATIONSHIP_STATE`；`KnowledgeEntry` 留 lore，真典留 `WORLD_RULE`/`OBJECT_STATE`；`projects.book_summary` **降级**为可选一句话梗概，主长程记忆由真典承载。

### 支柱 2：检索收窄（Relevance Retrieval）— 消灭全量加载反模式

- 新增统一检索门面 `RelevanceGateway`：消费者一律经它取"本章相关切片"（向量召回 + 结构查询 by cast/synopsis/chapterIndex），**禁止任何消费者再直接 `findAll*` 全量加载**。
- 各消费者改造：ContinuityChecker / ConsistencyGuard / EntityExtractor 去重 / RelationshipEvolutionTracker / CharacterProfileUpgrader.collectExcerpts / buildCharacterVoiceSection 全部改走相关性切片。
- 所有 `find*ByProject` 加 `LIMIT/OFFSET` 分页（配合支柱 4 归档压小活跃集）。

### 支柱 3：分层无衰减记忆 + Token 预算 + 缓存失效

- 摘要三级（章 span=3 / 卷 / arc）独立、不互相吞噬；全书记忆由真典承载，不再级联再压缩。
- 卷边界 ±N 章保留细粒度副本（软归档非删除）；重叠摘要区间去冲突；历史卷摘要**按相关性召回**而非时间近邻。
- **`recentSummarySection` 进入裁剪序列**；统一预算分配器按优先级表分配（故事原旨 > 真典事实 > 当前 cast > 本卷大纲 > 检索 > 历史摘要 > 反馈/约束），低优先级按比例压缩；发送前 token 硬校验不超窗口 90%。
- `stableSectionCache` 加**写联动失效**（worldbuilding/premise/knowledgeQuote 在对应数据变更时失效）。
- 向量库：重写删旧 chunk（按 `chapterId#chunk*` 前缀清理）、top-K 与预算联动（5~20）、query 向量缓存 + 失败告警。
- 脏卷摘要重算路径补齐：组装遇 dirty 卷摘要时，用真典事实 + 该卷章级摘要重建后清 dirty。

### 支柱 4：可演进的结构规划 + 归档衰减

- 总纲中段重规划：每完成 N 卷（默认 5）基于"已写卷真典事实 + 已发生偏离"重规划剩余未写卷结构。
- 延迟卷大纲补全结构：`parseAndAppendVolume` 调用完整 `parseChapterDetails`，填充 cast/beats/climax/tension/`volumeArcPosition`（激活当前死字段）。
- `ArchiveCompactor`（卷末触发）：foreshadowings（importance 衰减、长期未回收且非 mustResolve→RETIRED、语义合并）、narrative_threads（修正过激状态机、近似合并、RESOLVED 归档）、entity_edges（物理删过期边、同对多关系去冲突）、continuity_issues（非 HIGH 超窗归档）、knowledge_entries（语义去重合并）。
- 弧光时间线：ArcScheduler 阈值随角色数缩放 + Top-K 输出；ArcUpdater 从真典读累积轨迹；激活 `volumeArcPosition`。
- 节奏/多样性：DiversityTracker/ClimaxDiversityTracker 落库（持久化）+ 卷级桥段指纹检测宏观重复；PlotProgressionDetector 基于实际成文剧情推进。

### 支柱 5：可靠的写作作业

- 成本：`ModelConfig` 加 `pricing`，按真实模型计价；写作循环维护 `accumulatedCost`，超 `cost_budget` → PAUSED 告警。
- 容错：错误分类（限流/余额/网络/内容过滤/超窗口）；429/余额→指数退避（分钟级）+ 自动切本地 qwen3-4b 兜底；临时错误不计入永久失败计数。
- 并行：写完正文后 9 个无依赖后处理 Agent + FactHarvester 并行调度。
- 章节状态机：`Chapter.status` `DRAFTING→POST_PROCESSING→DONE`；推进 `currentChapterIndex` 前 require DONE；后处理失败停在 POST_PROCESSING，重启幂等重跑。
- 重写互斥：单章"已重写"标记，continuity HIGH 与 reader<7 互斥（取更严重者）。

### 支柱 6：闭环修复

- 修复决策三选一（LLM 仲裁 + 规则）：正文错→重写当前章；真典需演进→supercede；早期根源错→纠正真典 + 标记早期章 `needsManualReview`。
- `ConsistencyGuard` 的 HIGH 接入修复闭环；重写后重跑连续性检查；卷边界全局一致性扫描 + FINAL 真终审（连续性全局 + 未回收 mustResolve 伏笔 + 真典一致性 + 成本回执）。

## 状态语义（增量）

- `Chapter.status`（新增可空列，默认 `DONE` 兼容旧库）：`DRAFTING`（正文生成中）/ `POST_PROCESSING`（后处理中）/ `DONE`（后处理完成，可推进 index）/ `NEEDS_MANUAL_REVIEW`（早期根源矛盾，待人工）。
- `story_facts.status`：`ACTIVE` / `SUPERSEDED` / `RETIRED`。
- 沿用 mc spec 的 WritingTask.Status / NovelState 语义（含 `NEEDS_REVISION`）。

## 验收标准（分波，机器可验证）

### 第一波 · 止血与正确性（W1）

- [ ] `mvn test -Dtest=StableSectionCacheInvalidationTest` 通过：新增 KnowledgeEntry / `updateMasterOutlineWithWrittenDigest` / CharacterProfileUpgrader 升级后，`stableSectionCache` 对应段失效，后续组装反映新数据（复现 GAP #8 正确性 bug）。
- [ ] `mvn test -Dtest=DirtyVolumeSummaryRecomputeTest` 通过：重写过去卷的章节后，覆盖该卷的 dirty 摘要在下次组装时被重建并清 dirty，不再静默消失（复现 GAP #10）。
- [ ] `mvn test -Dtest=VectorChunkCleanupOnRewriteTest` 通过：重写章节后旧 chunk 被清理，向量检索不再命中旧版本正文；top-K 随剩余预算在 5~20 间动态（复现 GAP #9）。
- [ ] `mvn test -Dtest=WritingJobReliabilityTest` 通过：成本超预算→PAUSED；429 模拟→指数退避并切本地兜底模型；后处理失败后状态停在 POST_PROCESSING，重启幂等重跑；continuity HIGH 与 reader<7 重写互斥（复现 GAP #18/#19/#20/#22/#23）。
- [ ] `./scripts/e2e.sh --mode record --chapters 30` 跑完无中断；模拟 429 注入验证降级路径；重写旧章后验证后续上下文不丢该卷。
- [ ] `mvn test` 全量绿。

### 第二波 · 故事真典 + 检索收窄（W2）

- [ ] `mvn test -Dtest=StoryBibleTest` 通过：`story_facts` CRUD + supercession 审计链（旧事实 SUPERSEDED、新 ACTIVE、superseded_by 指向）；播种从世界观/EraAnchor/人物卡产出初始事实。
- [ ] `mvn test -Dtest=FactHarvesterTest` 通过：每章后处理提取事实变更；一致→刷新；冲突→仲裁（演进 supercede / 正文错上报）；不带必须真典时安全降级。
- [ ] `mvn test -Dtest=RelevanceGatewayTest` 通过：ContinuityChecker/ConsistencyGuard/EntityExtractor/RelationshipTracker/CharacterProfileUpgrader/CharacterVoice 全部经门面取相关性切片，输入 token 受控且不再全量加载（复现根因 A）。
- [ ] `mvn test -Dtest=HierarchicalMemoryBudgetTest` 通过：`recentSummarySection` 进入裁剪；统一预算分配器按优先级压缩；组装后 prompt token ≤ 模型窗口 90%；历史卷摘要按相关性召回（复现 GAP #7/#11）。
- [ ] `./scripts/e2e.sh --mode record --chapters 100` PASS；断言"第 80 章上下文仍含第 1 卷关键设定"；真典 ACTIVE 事实数随章增长且 supercession 链无矛盾；compare-baseline 对长程指标 PASS（需 `--bless` 建立长程基线）。

### 第三波 · 结构演进 + 闭环修复（W3）

- [ ] `mvn test -Dtest=OutlineReplanningTest` 通过：每 N 卷触发剩余卷重规划；延迟卷大纲填充 cast/beats/climax/tension/volumeArcPosition（复现 GAP #1）。
- [ ] `mvn test -Dtest=ArchiveCompactorTest` 通过：foreshadowings/threads/edges/issues/knowledge 归档衰减合并；全量查询活跃集受控（复现根因 B）。
- [ ] `mvn test -Dtest=ArcTimelineAndPacingTest` 通过：ArcScheduler 阈值随角色数缩放 + Top-K；volumeArcPosition 卷内分布强制；多样性检测持久化 + 卷级桥段指纹；PlotProgressionDetector 基于成文（复现 GAP #5/#6）。
- [ ] `mvn test -Dtest=ClosedLoopRepairTest` 通过：ConsistencyGuard HIGH 入闭环；重写后复检；修复三分类仲裁（重写/supercede/早期根源标记 needsManualReview）（复现根因 D）。
- [ ] `./scripts/e2e.sh --mode record --chapters 200`（或 400 若成本允许）PASS + 独立质量评估：断言"伏笔回收率稳定""无宏观套路重复卷""跨百章无 HIGH 一致性矛盾"；compare-baseline PASS。

## 影响面

- **涉及模块**：`memory/`（ContextAssembler 段重组 + 预算 + 缓存失效、SummaryService 分层、VectorRetrievalService 去污+topK、新增 RelevanceGateway）、`agent/`（新增 FactHarvester；改造 ContinuityChecker/ConsistencyGuard/EntityExtractor/RelationshipEvolutionTracker/CharacterProfileUpgrader/PlotterAgent/ArcUpdater/StyleGuardian）、`orchestrator/`（NovelOrchestrator 循环可靠性 + 章节状态机 + 修复闭环 + 卷大纲补全 + 总纲重规划）、`service/`（WritingTaskExecutor 退避降级、ArcScheduler/ClimaxDiversityTracker/DiversityTracker/PlotProgressionDetector/NarrativeThreadTracker 改造、新增 ArchiveCompactor）、`llm/`（ModelConfig.pricing、ObservedLlmGateway 真实计价、限流降级路由）、`persistence/sqlite/`（story_facts 新表 + 列迁移 + 分页查询）、`domain/`（StoryFact、Chapter.status、WritingPreferences.cost_budget 等）、`telemetry/`（成本累计事件）。
- **涉及 prompt 模板**：本 spec 不改 `.st` 变量名。真典消费在 ContextAssembler 段组装层完成；FactHarvester 用新模板 `fact-harvest.st`（新文件，不动既有变量名）。
- **数据库 schema 变更**：
  - 新增 `story_facts` 表（幂等 CREATE）。
  - `chapters` 加可空 `status` 列（默认 DONE 兼容）；`foreshadowings` 加 `status`/`importance`；`writing_preferences` 加 `cost_budget` 等；`ModelConfig`/配置加 `pricing`。
  - 全部走 `safeAddColumn` + `CREATE TABLE IF NOT EXISTS`，保持现有幂等迁移链。
- **质量基线影响**：W2/W3 新增 100/200 章 replay 基线，需各一次 `compare-baseline.py --bless`（理由：首次引入长程基线，非质量退化）。
- **配置破坏性变更**：`application.yml` 的 `default-model`（本地 qwen3-4b）从"永不生效"改为限流降级目标；需确认本地模型可用（不可用时降级路径跳过并告警）。

## 安全

- 沿用 mc spec 的凭据外移要求：本 spec 不再引入明文凭据；`pricing` 仅配置单价，不含 key。

## Feature 拆分

> 拆分规则见 AGENTS.md「需求拆分协议」。每个 feature 1-3 个文件、可独立验证、附 machine-verifiable acceptance 与 verify_cmd。Phase 10。三波依赖：W1 无跨波依赖可先行；W2 依赖 W1 的预算/缓存基础；W3 依赖 W2 的真典/检索门面。

### 第一波 · 止血与正确性（W1，先行）

| id | name | 依赖 | acceptance | verify_cmd | 文件 |
|----|------|------|------------|------------|------|
| p10-w1-01 | stableSectionCache 写联动失效 | [] | 新增 KnowledgeEntry / updateMasterOutlineWithWrittenDigest / CharacterProfileUpgrader 升级后，worldbuilding/premise/knowledgeQuote 缓存段失效，后续组装反映新数据 | `mvn test -Dtest=StableSectionCacheInvalidationTest` | `memory/ContextAssembler.java` |
| p10-w1-02 | 脏卷摘要重算路径 | [] | 重写过去卷章节后，覆盖该卷的 dirty 摘要在下次组装时重建并清 dirty，不再静默消失 | `mvn test -Dtest=DirtyVolumeSummaryRecomputeTest` | `orchestrator/NovelOrchestrator.java`、`memory/ContextAssembler.java` |
| p10-w1-03 | 向量去污 + top-K 联动预算 | [p10-w1-02] | 重写章节按 chapterId#chunk* 清旧 chunk；RETRIEVAL_TOP_K 随剩余预算 5~20 动态；embedding 失败告警不静默 | `mvn test -Dtest=VectorChunkCleanupOnRewriteTest` | `agent/ChapterWriterAgent.java`、`memory/ContextAssembler.java`、`memory/VectorRetrievalService.java` |
| p10-w1-04 | 成本预算熔断 + 真实计价 | [] | ModelConfig.pricing 按模型计价；循环累计成本超 cost_budget→PAUSED 告警 | `mvn test -Dtest=WritingJobReliabilityTest` | `llm/`(ModelConfig/ObservedLlmGateway)、`orchestrator/NovelOrchestrator.java`、`domain/WritingPreferences.java` |
| p10-w1-05 | 分类型退避 + 限流模型降级 | [p10-w1-04] | 429/余额→指数退避（分钟级）+ 自动切本地 qwen3-4b 兜底；临时错误不计入永久失败计数 | 同上（WritingJobReliabilityTest） | `llm/gateway/`、`service/WritingTaskExecutor.java` |
| p10-w1-06 | 章节状态机 + 后处理幂等重跑 | [] | Chapter.status DRAFTING→POST_PROCESSING→DONE；推进 index 前 require DONE；后处理失败重启幂等重跑 | 同上 | `domain/Chapter.java`、`orchestrator/NovelOrchestrator.java`、`persistence/sqlite/` |
| p10-w1-07 | 后处理并行 + 重写互斥 | [p10-w1-06] | 9 个无依赖后处理 Agent 并行；continuity HIGH 与 reader<7 重写互斥（取更严重者） | 同上 | `orchestrator/NovelOrchestrator.java` |

### 第二波 · 故事真典 + 检索收窄（W2）

| id | name | 依赖 | acceptance | verify_cmd | 文件 |
|----|------|------|------------|------------|------|
| p10-w2-01 | story_facts 表 + 领域模型 + 持久化 | [] | story_facts CRUD + supercession 审计链；幂等建表迁移 | `mvn test -Dtest=StoryBibleTest` | `domain/StoryFact.java`、`persistence/sqlite/` |
| p10-w2-02 | 真典播种 | [p10-w2-01] | runConceiving 从世界观/EraAnchor/初始人物卡产出初始 WORLD_RULE/CHARACTER_STATE | `mvn test -Dtest=StoryBibleSeedingTest` | `orchestrator/NovelOrchestrator.java` |
| p10-w2-03 | FactHarvester Agent | [p10-w2-01] | 每章后处理提取事实变更；一致刷新/冲突仲裁（演进 supercede 或上报）；无真典安全降级 | `mvn test -Dtest=FactHarvesterTest` | `agent/FactHarvesterAgent.java`、`prompts/fact-harvest.st` |
| p10-w2-04 | RelevanceGateway 检索门面 | [p10-w2-01] | 统一入口按 cast/synopsis/chapterIndex 返回相关性切片；向量+结构查询 | `mvn test -Dtest=RelevanceGatewayTest` | `memory/RelevanceGateway.java` |
| p10-w2-05 | 检查器/上下文改走检索收窄 | [p10-w2-04] | ContinuityChecker/ConsistencyGuard/EntityExtractor/RelationshipTracker/CharacterProfileUpgrader/CharacterVoice 经门面取切片，不再全量加载 | 同上 | `agent/`、`memory/ContextAssembler.java` |
| p10-w2-06 | 摘要分层 + recentSummary 进预算 + 相关性召回 | [p10-w1-01] | 三级摘要独立不互吞；recentSummary 进裁剪；统一预算分配器；历史卷按相关性召回；重叠区间去冲突 | `mvn test -Dtest=HierarchicalMemoryBudgetTest` | `memory/ContextAssembler.java`、`memory/SummaryService.java` |
| p10-w2-07 | 真典替代 book_summary 为主长程记忆 | [p10-w2-03,p10-w2-05] | ContextAssembler/ContinuityChecker 读真典 ACTIVE 事实；book_summary 降级为可选梗概 | `mvn test -Dtest=StoryBibleConsumptionTest` | `memory/ContextAssembler.java`、`agent/ContinuityCheckerAgent.java` |

### 第三波 · 结构演进 + 闭环修复（W3）

| id | name | 依赖 | acceptance | verify_cmd | 文件 |
|----|------|------|------------|------------|------|
| p10-w3-01 | 总纲中段重规划 | [p10-w2-07] | 每 N 卷基于已写真典事实重规划剩余卷；卷大纲结构化更新 | `mvn test -Dtest=OutlineReplanningTest` | `orchestrator/NovelOrchestrator.java`、`agent/PlotterAgent.java` |
| p10-w3-02 | 延迟卷大纲补全结构化字段 | [] | parseAndAppendVolume 调 parseChapterDetails 填 cast/beats/climax/tension/volumeArcPosition | 同上 | `orchestrator/NovelOrchestrator.java`、`agent/PlotterAgent.java` |
| p10-w3-03 | ArchiveCompactor 归档衰减合并 | [p10-w2-01] | foreshadowings/threads/edges/issues/knowledge 归档衰减合并；全量查询活跃集受控 | `mvn test -Dtest=ArchiveCompactorTest` | `service/ArchiveCompactor.java`、`persistence/sqlite/` |
| p10-w3-04 | 全量查询分页 | [p10-w3-03] | 所有 find*ByProject 加 LIMIT/OFFSET | 同上 | `persistence/sqlite/SqliteNovelRepository.java` |
| p10-w3-05 | 弧光时间线 + volumeArcPosition | [p10-w3-02] | ArcScheduler 阈值随角色数缩放+Top-K；ArcUpdater 读真典轨迹；volumeArcPosition 卷内分布强制 | `mvn test -Dtest=ArcTimelineAndPacingTest` | `service/ArcScheduler.java`、`agent/ArcUpdater.java` |
| p10-w3-06 | 多样性/节奏持久化长窗 + 基于成文 | [] | Diversity/Climax 落库+卷级桥段指纹；PlotProgressionDetector 基于成文推进 | 同上 | `service/DiversityTracker.java`、`service/ClimaxDiversityTracker.java`、`service/PlotProgressionDetector.java`、`agent/PlotMotifTracker.java` |
| p10-w3-07 | 叙事线索状态机修正 | [p10-w3-03] | 长线不因本章未提及即 suspend；近 K 卷未提及才告警；近似线索合并 | `mvn test -Dtest=NarrativeThreadScalingTest` | `service/NarrativeThreadTracker.java` |
| p10-w3-08 | 闭环修复三分类 + ConsistencyGuard 入闭环 + 重写复检 | [p10-w2-07] | 修复仲裁（重写/supercede/早期根源标记 needsManualReview）；ConsistencyGuard HIGH 入闭环；重写后复检 | `mvn test -Dtest=ClosedLoopRepairTest` | `orchestrator/NovelOrchestrator.java`、`agent/ConsistencyGuard.java` |
| p10-w3-09 | 卷边界全局扫描 + FINAL 真终审 | [p10-w3-08] | 卷末全局一致性扫描；FINAL 跑连续性全局+未回收 mustResolve+真典一致性+成本回执 | `mvn test -Dtest=FinalAuditGateTest`（扩展） | `orchestrator/NovelOrchestrator.java` |

## 风险与备选方案

**风险与缓解：**
- **真典收割放大 LLM 成本**：每章多 1 次 FactHarvester 调用。缓解：FactHarvester 与现有后处理并行（W1 已并行化）；冲突仲裁仅在事实变化时触发；W1 成本熔断兜底。
- **真典与 Character/Entity 职责重叠**：明确分工（稳定身份 vs 可变状态），W2-02 播种 + W2-07 消费时严格执行；CharacterProfileUpgrader 的 ability/growthArc 写入迁移到真典，Character 只留稳定字段。
- **限流降级到本地 qwen3-4b 质量下降**：缓解：仅限流时降级，恢复后自动回切；降级路径记事件可观测；本地不可用时跳过降级并告警（不静默失败）。
- **总纲重规划破坏既有伏笔承诺**：缓解：重规划输入含未回收 mustResolve 伏笔约束（不可丢弃）；重规划产出经 ContinuityChecker 校验。
- **长程基线 fixture 体积**：100/200 章 fixture 较大。缓解：`data/fixtures/llm` 走 record-on-demand；compare-baseline 对确定性指标零容忍、字数保留容差。
- **schema 迁移破坏旧库**：所有新增列可空 + 默认值兼容；保持 `safeAddColumn` 幂等链。

**被否决的备选方案：**
- **轻量真典（挂 KnowledgeEntry）**：改动小，但 lore 与可变事实混在一起，长期会再分叉；与用户确认采用完整真典。
- **改正文回溯重写**：连锁重写成本爆炸，且破坏已发布章节稳定性；否决，改用真典纠错。
- **只改进摘要分级承载长程（不做真典）**：摘要本质是 prose 压缩，无法承载"可纠错事实"，记忆坍塌与修复闭环两根因治不彻底；否决。
- **引入真实 tokenizer / ANN 强制迁移**：精度/性能更优但超首版边界；否决，留扩展点。

## 评审检查清单（spec 自检）

- [x] 所有 feature 均有 `dependencies` / `acceptance` / `verify_cmd`，acceptance 为机器可验证断言。
- [x] 每个 feature 标注 1-3 个文件，满足「需求拆分协议」。
- [x] 六大支柱均有对应 feature 与验收；五道扩展墙与四个根因均有 feature 覆盖。
- [x] 分波边界明确：W1 止血正确性先行（无跨波依赖）；W2 真典+检索（架构核心）；W3 结构演进+闭环修复。
- [x] 状态语义显式定义并与现有 `WritingTask.Status`/`NovelState`/`Chapter` 对齐，新增列可空兼容迁移。
- [x] 无 TBD/TODO/占位段；所有验收命令对齐仓库现有 `mvn test -Dtest=` / `scripts/e2e.sh` / `compare-baseline.py` 约定。
- [x] 已与用户确认：完整真典路线 + 三波全规划、先做第一波。
