# Spec: 架构质量深化——长程一致性、风格、召回与 LLM Harness

> 来源：2026-09-28 五路架构深扫（记忆召回 / 一致性检查 / 风格 / LLM harness / 历史实测教训）。
> 本 spec 只做设计与登记；实现按「特性分支与合并协议」逐 feature 闭环推进。

## 背景与动机

两轮真实 UI 旅程修复了终局可达性与 LLM 输出稳定性表层（NCS-065..095），但架构深扫确认了四个域的深层缺口：

1. **召回**：向量查询只用章节大纲（`ContextAssembler.java:131`），不含实体名/伏笔锚点；三类来源混排无配额、无阈值（`EmbeddedVectorStore.java:89-101`，`sourceType` 零消费）；超预算时 `relevantSettings` 整体置空（`ContextAssembler.java:1127-1130`）与长程召回目标矛盾；唯一反馈回路（VALIDATED→recency 升高）是马太效应；recall 不过滤 `validFrom/validTo`。
2. **一致性**：RepairArbiter 三分类是注入但零调用的死代码，真典 CONFLICT 双 ACTIVE 继续双注入、靠 30 章老化"遗忘式修复"；重写不回滚旧稿派生事实（`sourceChapterVersion` 恒 0），记忆被污染；检查器与写作用不同相关性口径（`ContinuityCheckerAgent.java:219` 的 `relevantFacts(projectId, null, 15)` 使 recency 恒 0）；抽取器各看不同长度正文（4000/3000/5000 字）且不告知截断；伏笔/关系/桥段检测器静默吞异常（catch→空返回，ledger 照标 COMPLETED）；检查器漏检会被 FactHarvester 固化为真典事实，且无任何 check-the-checker 度量。
3. **风格**：StyleProfile 仅三句抽象形容词，`sampleChapterIndices` 是死字段（例文锚点从未接通）；风格检查在仲裁之后执行、styleScore 生产恒传 null（`ChapterProduction.java:371-373`），评分 20/100 也不触发重写；卷末基线"融入最新趋势"追随正文，系统性漂移被合法化；反馈回流截断 80 字；golden.json 零风格维度，CI 对风格退化全盲。
4. **LLM harness**：命名 provider 熔断体系在默认配置下休眠，legacy 3 连盲目重试并存；重试层层相乘无全局预算、线性退避无 jitter；结构化输出零 schema、同一解析片段 12+ 处复制、失败语义各自为政（`QualityEvaluationAgent` 静默全 0 分、伏笔静默丢失）；`finish_reason=length` 在最贵的 ChapterWriter 被无视，半章可入库；严格串行流水线是 recheck 4.5 分钟/预览 9.4 分钟的根因；成本账本内存态重启清零（NCS-054/055 已登记，不在本 spec 重复）。

**总体诊断**：长程记忆是四条互不联通的平行管线（级联摘要/真典/向量 chunk/台账图），唯一汇聚点是注入，没有统一的相关性裁决、预算协商与使用反馈；质量控制"检查多、闭环少"。本 spec 以两个横切机制（**注入清单**、**质量金标集**）为地基，分四域收敛。

## 目标

1. **注入可追溯、检查可核对**：每个章稿版本持久化注入清单；检查器与写作用同一相关性口径并基于清单做封闭式核对，误报与漏检可度量。
2. **召回质量可管理**：多查询混合检索 + 来源配额与阈值 + 使用反馈回路，长程信息进入上下文不再靠巧合命中。
3. **风格有锚点、退化可拦截**：例文锚点 + 意图/状态二分基线 + 每章量化守卫进仲裁与 golden 基线。
4. **LLM 调用单语义、可观测、可并行**：单一重试所有权、中心化结构化解析、截断门禁、语义缓存与后处理并行。

## 非目标

- **不重复实现 NCS-034..058 已登记机制**（边界见「与既有 backlog 的关系」），尤其成本持久化（NCS-054/055）、租约身份（NCS-036..038）、outbox（NCS-041..044）、检索评测（NCS-057）不在本 spec 重设 feature。
- 不改状态机（CONCEIVING→…→FINAL）、不改 24 Agent 职责划分与 SPI 接口边界（`VectorStore.search` 仅加可选参数的默认重载）。
- 不引入外部依赖：不接独立 rerank/向量数据库服务（阈值、配额、词法兜底均为纯计算），不换 tokenizer 库。
- 不在本 spec 内直接实现代码；每 feature 按 Definition of Done 独立闭环。

## 总体设计

### A. 横切机制一：上下文注入清单（Injection Manifest）— 地基

- **数据结构**：`ContextInjectionManifest { projectId, chapterIndex, chapterVersion, entries: [{section, kind(FACT|CHUNK|KNOWLEDGE|FORESHADOW|ENTITY|STYLE_ASSET), refId, estTokens}], totalTokens, windowBudget }`。
- **生成**：`ContextAssembler.assemble` 在拼装 WritingContext 的同一路径中同步产出清单（同源生成，禁止事后重建），由 `ChapterProduction` 随章稿持久化（键：projectId + chapterIndex + chapterVersion）。
- **三个消费方**：①检查器封闭核对段（NCS-097）；②召回反馈回路（NCS-114：manifest × FactHarvester 交叉 = 注入命中率）；③金标集与检索评测的度量底座（NCS-137 / NCS-057）。
- **不变量**：清单条目与实际注入一一对应；清单不可变（新稿版本新清单）。

### B. 一致性域

1. **检查与写作同源 + 封闭式核对（NCS-097）**：ContinuityChecker/ConsistencyGuard 不再自拼 settings，改用 ContextAssembler 同一相关性口径（带 chapterIndex 的 `relevantFacts` + 向量召回）；prompt 增「本章已注入约束核对清单」段——核对 N 条已知约束（真典事实/伏笔/实体状态）是否被正文遵守，开放式检查降为补充。消除真典双通道注入（`collectSettingsForCheck` vs `buildCanonSection`）；recheck 与首查共用同一 settings builder，同名检查可比。
2. **矛盾仲裁闭环（NCS-098）**：激活 RepairArbiter 三分类。触发源：harvest CONFLICT（当前双 ACTIVE 无出口）、WORLDBUILDING 类 HIGH、跨章时间线矛盾。REWRITE→单章重写；SUPERSEDE_CANON→真典合法演进（更新真典不改正文）；MANUAL_REVIEW→人工屏障。裁决前 CONFLICT 对从 recall 过滤，不再双注入。
3. **重写派生回滚（NCS-099）**：FactHarvester 真实赋值 `sourceChapterVersion`；重写接受后按旧稿版本 supersede 旧派生事实/实体边/伏笔条目。与 NCS-039/040/041（向量索引隔离、outbox）互补：那是索引与派生任务层，本项是真典与台账层。
4. **fail-loud 推广（NCS-118/119）**：后处理各阶段失败→显式重试一次→仍失败置 UNKNOWN 信封 + 事件，ledger 不标 COMPLETED。"未执行"对全部阶段可见，NCS-007 原则从 2 类 checker 扩展到全部检测器。
5. **伏笔台账硬化（NCS-120）**：回收检测 prompt 携带台账 id，LLM 返回 `id + 证据引文`，替换 8 字前缀/关键词交集启发式；PLOT_COMMITMENT 与 Foreshadowing 双承诺系统建立同步点。
6. **issue 老化治理（NCS-124）**：HIGH 归档前置条件 = 检查器复验未复现 或 显式处置记录（豁免/修复引用），斩断"用遗忘替代修复"。
7. **金标集（NCS-137）**：植入矛盾语料（时点冲突/能力矛盾/姓名漂移/伏笔错回收/风格突变），RecordReplay 确定性回放，断言 checker 召回率/误报率阈值，进回归。这是"检查器漏检被固化为真典"这一自我强化回路的唯一制衡。

### C. 风格域

1. **三层锚点模型**：`intent`（preset + 作者审美档案，不可漂移）/ `styleSamples`（例文 2-3 段 500-1000 字：preset 携带 + 作者 likedSnippets + 作者确认的优秀生成段落）/ `state`（每章量化特征）。例文以 few-shot 注入 chapter-writing 与全部修订路径。
2. **闭环（NCS-100/101/125）**：量化守卫每章执行（确定性、零成本），超阈值产 STYLE issue；styleScore 进 QualityCouncil 生产调用，低于阈值在重写预算内触发重写；LLM 风格检查降频但以量化证据为输入；reader-review 增 style 维度使风格获得每章门禁能力。
3. **基线二分（NCS-128）**：卷末 updateProfile 只更新 state；漂移永远相对 intent + 例文参照测量，检测器不再追随被检对象。
4. **量化指标进基线（NCS-104）**：句长分布漂移、跨章 n-gram 重复率、cliche 命中率（统一 ClicheDetector 口径 + 从生成正文动态挖掘高频 n-gram，LLM 检查发现的超标词回流禁用词表）、对话密度；写入章节质量报告并新增 golden.json `styleMetrics` 维度，compare-baseline.py 校验。
5. **资产模型扩展（NCS-103）**：preset JSON 携带例文段落/词汇表/句式规范；ConsistencyGate 词表从 Java 硬编码外置到 preset。

### D. 召回域

1. **查询规划（NCS-110）**：`RecallQueryPlanner` 合成多路查询——章节大纲 / cast 规范名串 / 待回收伏笔关键词 / 本章场景地点；各路取 topK/N 合并去重（跨路 id 去重 + 同章相邻 chunk 合并）。向量失败或全低于阈值时词法兜底通道（实体名/别名精确匹配 + 知识条目）。
2. **配额与阈值（NCS-111）**：`VectorStore.search` 支持 sourceType 配额（默认 CHAPTER:WORLDBUILDING:CHARACTER = 0.5:0.25:0.25）与绝对相似度阈值（默认 0.35，可配）——低于阈值宁缺毋滥，不再"永远凑满 K 条"。
3. **相关性硬化（NCS-109）**：`resolveCanonical` 查别名表（当前只查 name）；`matchesCast` 用规范名匹配；recall 过滤 `validFrom/validTo` 失效事实；mustCap 截断保序（cast 命中优先于 importance 排序）。
4. **台账相关性化（NCS-113）**：实体图召回接入本章 cast/地点（替代纯最近 5 章 recency）；伏笔/线索注入混合"本章相关性 × 悬置时长"权重；与本章无关的长期悬置线索降权隔离（僵尸线索不再霸榜）。
5. **反馈回路（NCS-114）**：manifest × harvest 交叉计算各 kind 注入命中率，低命中 kind 降配额；探索配额——importance≥4 且长窗未注入的事实保底 1 条注入，打破 VALIDATED 马太效应。
6. **预算自适应（NCS-112）**：relevantSettings 从"整体置空"改为按预算截断；各截断常量随 budget 线性缩放；组装结束后兜底校验（估算超窗发 `CONTEXT_BUDGET_EXCEEDED` 事件），消灭不可裁盲区静默超窗。
7. **向量存储工程化（NCS-134）**：按项目分索引文件；embedding 维度校验，不匹配自动重建并发事件（替换当前 cosine=0 静默失联）；增量持久化替代全量 Java 序列化。

### E. LLM Harness 域

1. **单一重试所有权（NCS-129/136）**：传输层重试归网关，按 LlmTaskType 配置 `{timeout, maxAttempts, backoff: 指数+jitter}`（EXTRACTION 60s/2 次，CHAPTER_WRITING 900s/1 次，CHECK 类 180s/2 次）；Agent 层只保留语义重试（prompt 递降/补问）；删除 AgentHarness 死代码或收编为唯一入口。每逻辑调用一个 correlationId 贯穿全部事件，每章一个 attempt/成本上限（重试不再层层相乘）。
2. **中心化结构化解析（NCS-105/106）**：新组件 `LlmJson`：剥围栏→解析→带因修复重试一次→类型化失败（TRUNCATED/INVALID/EMPTY）→STRUCTURED_OUTPUT 事件；provider 支持时启用 response_format；截断 JSON 走续写补全。先迁移高危点（QualityEvaluationAgent 静默全 0 分、ForeshadowingExtractor 静默丢失），再全量收口（12+ 处复制粘贴清零）。
3. **截断门禁（NCS-107）**：ChapterWriter 消费 `finish_reason=length`——续写补全一次，仍截断按失败走递降重试，杜绝半章入库。
4. **路由收敛（NCS-108）**：application.yml 迁移到 providers/routing 唯一形态（熔断/failover 真正生效），legacy 解析保留一版弃用告警后删除；文档化 yml/env/UI 覆盖三方优先级。
5. **语义缓存（NCS-131）**：装饰网关，键 = (taskType, provider, model, 归一化 prompt 指纹, temperature)，仅对低温度确定性任务白名单启用（CONTINUITY_CHECK/EXTRACTION/STYLE_GUARDIAN 等），失效键 = projectId + chapterVersion，命中发 `LLM_CACHE_HIT` 事件。复用 RecordReplay 已有指纹实现。
6. **并行扇出（NCS-132）**：章保存后实体/伏笔/线索/关系四提取器并行（SQLite 写经既有串行执行器保持单写者）。
7. **长任务持久化（NCS-133）**：LongTask 落 sqlite job 表 + 阶段级进度上报 + 取消标记 + 过期清理，重启可查终态。
8. **可观测（NCS-130）**：LLM_ERROR 增 provider/model/attempt/promptFingerprint（支撑"同一 prompt 反复失败"聚类）；EventLog 改异步有界队列批量刷盘。
9. **传输层卫生（NCS-135）**：内容审查"温和化"注入行为显式事件化并纳入指纹可预期性；收敛/删除零消费的 `streamComplete` 双实现。

### 与既有 backlog 的关系（边界映射）

| 本 spec 观点 | 既有 not-started feature | 处理 |
|---|---|---|
| 成本账本持久化/调用准入 | NCS-054/055 | 不重设，引用 |
| 索引隔离/先建后换/outbox/租约 | NCS-036..044 | 不重设；NCS-099（真典/台账层回滚）与之互补，实现时对齐版本语义 |
| 影响证据持久化/依赖补查闭包 | NCS-045/046 | 不重设；NCS-096 清单可作为其"检查范围持久化"的底座 |
| 事实支持组/来源撤回传播 | NCS-052/053 | 不重设；与 NCS-098/099 同族，实现顺序建议 098→099→052/053 |
| 检索语义评测 | NCS-057 | 不重设；NCS-096/114 为其提供数据底座，NCS-137（check-the-checker）与其构成"检查器×检索"双评测 |
| 场景合同/偏离分析/卷末回读 | NCS-048..051 | 不重设、不依赖 |

## 验收标准

- [ ] `python3 scripts/check-harness.py` 通过：新增 NCS-096..NCS-137 共 42 项 `not-started`，全部含 acceptance/verify_cmd/dependencies/spec，无依赖环，current_phase=51，`updated_at=2026-09-28`
- [ ] `./init.sh` 离线状态核验通过（本 spec 为纯文档登记，不新增业务测试）
- [ ] 人工检查：本文件「Feature 拆分」表覆盖架构审查全部发现（四域 + 两横切），且未与 NCS-034..058 重复登记同一机制
- [ ] 后续实现门禁沿用 Definition of Done：凡改 `.st`/上下文组装/Agent 逻辑的 feature，合入时 `./scripts/e2e.sh` + `compare-baseline.py`，注入上下文变化的需重建回放语料（p32-15 教程）

## 影响面

- **涉及模块**: memory/（ContextAssembler、RelevanceGateway、TokenBudget、新增 RecallQueryPlanner）、agent/（ContinuityChecker、StyleGuardian、FactHarvester、Foreshadowing 族、LlmJson）、orchestrator/（ChapterProduction、ChapterPostProcessor、NovelOrchestrator）、service/（RepairArbiter、QualityCouncil、ArchiveCompactor、EntityGraphService、LongTaskService、新增 StyleMetricsService）、llm/（DefaultLlmGateway、OpenAiCompatibleClient、ModelConfig、新增 LlmJson/SemanticCache）、telemetry/（EventLog、ObservedLlmGateway）、persistence/（EmbeddedVectorStore、manifest/job 表）、scripts/（compare-baseline.py）、baselines/
- **涉及 prompt 模板**: continuity-check.st / style-check.st / reader-review.st / passage-edit.st / chapter-correction.st / foreshadow-*.st（**只增变量，不改既有变量名**）；新增 book-snapshot.st、chapter-continuation.st
- **数据库 schema 变更**: 新表 context_injection_manifest、long_task_job；StyleProfile 增 styleSamples/拆 intent-state（附迁移：旧行三字段并入 intent）；golden.json 增 styleMetrics 维度（`--bless` 重建）
- **质量基线影响**: NCS-097/110/111/112/113/114/102/117 等改注入上下文的 feature 合入时必然分叉旧回放语料——按 DoD 重建 fixture 并 `--bless`，重建理由记入 evidence；NCS-104 直接扩展 golden 结构

## Feature 拆分

> 波次即建议实施顺序：P0=止血与闭环（错误不再自我强化的最小闭环），P1=上限提升，P2=规模化与成本。每项独立闭环，可随时暂停。

| 波次 | id | name | 依赖 | acceptance（摘要，全量见 feature_list.json） | verify_cmd |
|---|---|---|---|---|---|
| P0 | NCS-096 | 上下文注入清单 | [] | 组装同步产出 manifest 并按章稿版本持久化，条目与注入一一对应 | `mvn test -Dtest=InjectionManifestTest` |
| P0 | NCS-097 | 检查与写作同源封闭核对 | [NCS-096] | 检查器输入与写作同口径+清单核对段；recheck 与首查同判据；真典单通道注入 | `mvn test -Dtest=ContinuityContextParityTest` |
| P0 | NCS-098 | 矛盾仲裁闭环激活 | [] | CONFLICT/WORLDBUILDING HIGH 走三分类裁决；裁决前 CONFLICT 对不注入 | `mvn test -Dtest=RepairArbiterClosedLoopTest` |
| P0 | NCS-099 | 重写派生数据按稿回滚 | [] | sourceChapterVersion 真实赋值；重写接受后旧稿派生事实被 supersede | `mvn test -Dtest=RewriteDerivedRollbackTest` |
| P0 | NCS-100 | 风格评分进质量仲裁 | [] | styleScore 进 QualityCouncil 生产调用；低于阈值预算内重写；解析失败发事件 | `mvn test -Dtest=StyleGateArbitrationTest` |
| P0 | NCS-101 | 风格反馈完整回流与 STYLE 类型 | [] | 新增 ContinuityIssue.Type.STYLE；风格反馈注入不再截 80 字 | `mvn test -Dtest=StyleFeedbackFlowTest` |
| P0 | NCS-102 | 风格例文锚点 | [] | StyleProfile.styleSamples 持久化并作为 few-shot 注入 chapter-writing | `mvn test -Dtest=StyleSampleAnchorTest` |
| P0 | NCS-103 | preset 风格资产扩展 | [NCS-102] | preset JSON 携带例文/词汇表/句式规范并落 StyleProfile；词表外置 | `mvn test -Dtest=PresetStyleAssetTest` |
| P0 | NCS-104 | 风格量化指标与基线维度 | [] | 句长/n-gram/cliche 指标每章计算；golden.json 增 styleMetrics 且 compare-baseline 校验 | `python3 scripts/compare-baseline.py && mvn test -Dtest=StyleMetricsServiceTest` |
| P0 | NCS-105 | LlmJson 组件与高危迁移 | [] | 统一解析+带因重试+类型化失败+事件；QualityEvaluation 不再静默全 0 分 | `mvn test -Dtest=LlmJsonComponentTest` |
| P0 | NCS-106 | 结构化解析全量收口 | [NCS-105] | 12+ 处手写解析全部走 LlmJson，grep 断言固化无残留 | `mvn test -Dtest=LlmJsonMigrationCoverageTest` |
| P0 | NCS-107 | ChapterWriter 截断门禁 | [] | finish_reason=length 触发续写补全，仍截断不入库 | `mvn test -Dtest=ChapterWriterTruncationGateTest` |
| P0 | NCS-108 | 路由语义收敛 | [] | providers/routing 唯一形态；legacy 弃用告警；配置优先级文档化 | `mvn test -Dtest=LlmRoutingConvergenceTest` |
| P1 | NCS-109 | RelevanceGateway 相关性硬化 | [] | 别名规范名匹配；validFrom/validTo 过滤；mustCap 保序 | `mvn test -Dtest=RelevanceHardeningTest` |
| P1 | NCS-110 | 多查询混合检索与词法兜底 | [NCS-109] | 大纲+cast+伏笔锚点多路查询合并；向量失败词法兜底 | `mvn test -Dtest=HybridRecallQueryTest` |
| P1 | NCS-111 | 向量检索配额阈值去重 | [] | sourceType 配额+绝对阈值+相邻 chunk 合并，低于阈值不注入 | `mvn test -Dtest=VectorSearchQuotaTest` |
| P1 | NCS-112 | 上下文预算裁剪自适应 | [] | relevantSettings 截断化；常量随 budget 缩放；组装后超窗校验+事件 | `mvn test -Dtest=ContextBudgetAdaptiveTest` |
| P1 | NCS-113 | 台账召回相关性化 | [NCS-109] | 实体/伏笔/线索按本章 cast 相关性混合权重；僵尸线索降权 | `mvn test -Dtest=LedgerRelevanceTest` |
| P1 | NCS-114 | 召回使用反馈回路 | [NCS-096] | manifest×harvest 命中率统计；低命中降配额；探索配额保底注入 | `mvn test -Dtest=RecallFeedbackLoopTest` |
| P1 | NCS-115 | ContextAssembler 工程卫生 | [] | 主角判定结构化；双关系段去重；缓存 LRU 淘汰；token 求和增量计算 | `mvn test -Dtest=ContextAssemblerHygieneTest` |
| P1 | NCS-116 | 快照与卷摘要生成修复 | [] | bookSnapshot 专用 JSON 模板+失败事件；卷摘要输入有 token 预算 | `mvn test -Dtest=BookSnapshotGenerationTest` |
| P1 | NCS-117 | 抽取输入策略统一 | [] | FactHarvester/EntityExtractor/ConsistencyGuard 共享分段策略并声明截断 | `mvn test -Dtest=ExcerptPolicyTest` |
| P1 | NCS-118 | 伏笔族后处理 fail-loud | [] | 提取/回收/审计失败显式重试或 UNKNOWN 信封；ledger 不标 COMPLETED | `mvn test -Dtest=ForeshadowFailLoudTest` |
| P1 | NCS-119 | 关系桥段族 fail-loud | [] | RelationshipEvolutionTracker/PlotMotifTracker 同 fail-loud 语义 | `mvn test -Dtest=TrackerFailLoudTest` |
| P1 | NCS-120 | 伏笔台账 id 引用链 | [] | 回收检测按 id+证据引文对账；与 PLOT_COMMITMENT 建同步点 | `mvn test -Dtest=ForeshadowIdChainTest` |
| P1 | NCS-121 | 检查信封解析增强 | [] | 「已回收」严重度/类型枚举对齐 prompt；VALID_EMPTY 仅零 issue；maxTokens 提升 | `mvn test -Dtest=CheckResultEnvelopeTest` |
| P1 | NCS-122 | 关系双轨一致性 | [] | EntityEdge 与 RELATIONSHIP_STATE 交叉校验；CharacterAsOf 口径统一 | `mvn test -Dtest=RelationshipDualTrackTest` |
| P1 | NCS-123 | 检测器产出分级回流 | [] | 桥段/说明文密度/多样性产出卷级趋势门禁+聚合注入反馈 | `mvn test -Dtest=DetectorTrendGateTest` |
| P1 | NCS-124 | issue 老化复验治理 | [] | HIGH 归档需复验未复现或显式处置记录 | `mvn test -Dtest=IssueAgingPolicyTest` |
| P1 | NCS-125 | reader-review 风格维度 | [] | reader-review.st 增 style 维度并计入 overall | `mvn test -Dtest=ReaderReviewStyleTest` |
| P1 | NCS-126 | 卷末评价采样覆盖 | [] | 采样率提升至分层≥20%（或全章摘要合成），style 评语带覆盖说明 | `mvn test -Dtest=QualityEvaluationSamplingTest` |
| P1 | NCS-127 | 修订路径风格注入 | [NCS-102] | passage-edit/chapter-correction/纠偏重写携带 styleGuide+例文 | `mvn test -Dtest=RevisionStyleInjectionTest` |
| P1 | NCS-128 | 风格基线意图状态二分 | [NCS-103] | updateProfile 只更新 state；漂移检测对 intent 测量 | `mvn test -Dtest=StyleBaselineSplitTest` |
| P2 | NCS-129 | 按任务超时与重试策略 | [] | per-task {timeout,attempts,指数退避+jitter}；传输重试归网关单层 | `mvn test -Dtest=PerTaskRetryPolicyTest` |
| P2 | NCS-130 | LLM 可观测性增强 | [] | correlationId 贯穿；LLM_ERROR 带Provider/model/attempt/指纹；EventLog 异步批量写 | `mvn test -Dtest=LlmObservabilityTest` |
| P2 | NCS-131 | 语义缓存网关 | [] | 白名单任务按指纹缓存；章稿版本失效键；LLM_CACHE_HIT 事件 | `mvn test -Dtest=SemanticCacheGatewayTest` |
| P2 | NCS-132 | 后处理并行扇出 | [] | 四提取器并行且写库串行保持，单章后处理耗时显著下降 | `mvn test -Dtest=PostProcessFanOutTest` |
| P2 | NCS-133 | LongTask 持久化与进度 | [] | job 表持久化+阶段进度+取消+过期清理；重启可查终态 | `mvn test -Dtest=LongTaskPersistenceTest` |
| P2 | NCS-134 | 向量存储工程化 | [] | 按项目分文件；维度校验自动重建+事件；增量持久化 | `mvn test -Dtest=VectorStoreEngineeringTest` |
| P2 | NCS-135 | 传输层卫生收敛 | [] | 审查注入显式事件化且指纹可预期；streamComplete 死 API 收敛 | `mvn test -Dtest=ClientHygieneTest` |
| P2 | NCS-136 | AgentHarness 死代码清理 | [] | executeWithRetry 无生产引用（删除或收编），grep 断言固化 | `bash -c '! grep -rn "executeWithRetry" src/main/java'` |
| P2 | NCS-137 | check-the-checker 金标集 | [NCS-097] | 植入矛盾语料回放断言召回率≥阈值、误报率≤阈值 | `mvn test -Dtest=CheckerGoldenSetTest` |

## 风险与备选方案

- **规模风险**：42 项拆分粒度小、各自独立闭环，可随时暂停/重排；P0 的 13 项构成"错误不再自我强化"的最小闭环，优先做完再做 P1/P2。
- **回放语料分叉**：改注入上下文的 feature（097/110..114/102/117 等）必然分叉旧 fixture——按 DoD 逐 feature 重建并记录理由，避免一次性大分叉不可归因。
- **检查口径切换阵痛**：封闭式核对（097）短期可能提高 issue 产出量——先落金标集（137 依赖 097，可在 097 后立即做）校准阈值再放量。
- **路由收敛动线上配置**（NCS-108 改 application.yml）：需在真实环境（本机 18100/云上）冒烟验证；云上属另一账号仅用户控制台操作，交付物含验证步骤文档。
- **被否决的方案**：①引入外部 rerank/向量数据库服务——违背零外部依赖约束，阈值+配额+词法兜底先证收益；②四条记忆管线合并为单一存储——改动面与迁移风险过大，用 manifest+同源口径做"逻辑统一"而非"物理统一"；③为 ChapterWriter 引入流式增量落库——当前瓶颈在串行检查链而非生成本身（NCS-132 并行化先行）。
- **回滚**：每个 feature 独立分支独立合并，回滚即 revert 单个合并提交；manifest/金标集为纯新增只读机制，不迁移旧数据。
