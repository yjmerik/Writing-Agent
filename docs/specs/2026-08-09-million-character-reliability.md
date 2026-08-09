# Spec: 百万字可靠性架构优化（首版单机可恢复写作）

> 文件名约定：`docs/specs/YYYY-MM-DD-<slug>.md`。新需求必须先落 spec，再拆 feature。
> 本 spec 为决策完备（decision-complete）文档：评审通过后可直接进入 `feature_list.json` 登记与逐 feature 实现，无需再次澄清。

## 背景与动机

WritingAgent 经过 Phase 0 至 Phase 9 的迭代，Agent 图、三层记忆、题材条件化 prompt、伏笔闭环、harness 可观测设施均已落地，当前完整 Maven 测试套件通过。但系统是为短篇规模（默认 24 章）设计的，未经历百万字（约 400 章 × 2500 汉字）长程写作的真实压力。一次面向长程可靠性的架构审计（基于本仓库 `src/main/java` 与 `src/main/resources` 实读）发现下列根因，均会直接阻塞百万字交付：

**J1. 任务不可恢复且取消无效（job correctness）。**
`service/WritingTaskExecutor.java` 的取消标志存在内存 `ConcurrentHashMap`（`cancelFlags`，第 30 行），进程重启即丢失。`cancel()`（第 91 行）只把标志位置 true 并立即把 DB 状态写成 `CANCELLED`（第 94-95 行），但 `NovelOrchestrator.runWritingLoop`（第 1143-1276 行）在写作循环内从不检查取消标志，只在本章失败退避的 `Thread.sleep` 被 interrupt 时才会跳出（第 1271 行）。因此取消对正在运行的循环毫无作用，循环会跑到自然结束，再由 `executeWriting`（第 68-72 行）事后根据标志改判 `CANCELLED` 或 `COMPLETED`。

**J2. 终态不诚实（false COMPLETED）。**
`runWritingLoop` 在连续 3 次单章失败（第 1266 行）或连续 10 章 HIGH 连续性问题（第 1249 行）时 `break` 跳出，返回已写章数。`executeWriting`（第 71 行）只要循环正常返回就一律置 `COMPLETED`，不区分「写满」与「中途熔断」。前端据此显示完成，但小说实际未写完。

**J3. 进程崩溃后 RUNNING 永驻。**
启动时不做「孤儿 RUNNING 任务」清扫，崩溃后 DB 里的 `writing_tasks` 行永远停在 `RUNNING`（`SqliteSchemaInitializer` 第 128 行建表，`SqliteNovelRepository` 第 888 行按 `status='RUNNING'` 查活任务）。重启后既不恢复也不标失败，`startWriting`（第 43 行）会因为「该项目已有 RUNNING 任务」而误把新任务先标 CANCELLED 再启动，状态语义混乱。

**D1. 默认规模与分层大纲与首版目标冲突。**
`domain/WritingPreferences.java`（第 58-59、84-85 行）默认 `targetChapters=24`、`layeredOutline=false`。本 spec 的百万字目标需要 400 章量级且依赖分层大纲延迟扩展（`NovelOrchestrator.tryExtendLayeredOutline`，第 1325 行）；默认值让长篇直接走单层大纲，约第 24 章后大纲耗尽。当前写作循环还可能将这种中途退出错误标记为完成，而不是可靠完成终局审计。

**D2. 章节正文就地覆盖，派生产物无失效边界（data correctness）。**
`domain/Chapter.java` 的 `setContent`（第 30 行）只 bump `version`，不区分「已提交的正文」与「由正文派生的产物」（卷摘要、滚动摘要、向量索引、实体图、伏笔台账）。`NovelOrchestrator.rewriteChapter`（第 1431-1510 行）重写后 `saveChapter` 覆盖正文（第 1454 行），重新抽取实体与伏笔（第 1457-1458 行），但**从不重新生成或失效覆盖该章的摘要**。`generateSummary`（第 1284-1293 行）只在卷末调用一次。结果：重写某章后，后续章节读到的摘要仍引用旧正文，长程一致性被静默破坏。

**D3. SQLite 既无 WAL，也无连接池与显式事务。**
`persistence/sqlite/ConnectionProvider.java`（第 15-17 行）每次 `DriverManager.getConnection` 拿裸连接，`SqliteSchemaInitializer` 全文无 `PRAGMA journal_mode=WAL`、无 `busy_timeout`、无外键开关（审计 grep 零命中）。`SqliteNovelRepository` 的多语句写入各自自动提交，百万字量级下「写一章 = 多表多语句」容易出现中途失败留下半成品，且读写互锁。

**M1. 向量召回默认是 stub。**
`application.yml`（第 8 行）`persistence.embedding: stub`，`PersistenceProperties`（第 11 行）默认却是 `cloud-api`。`persistence/embedding/StubEmbeddingProvider`（64 维字节累加哈希）只够测试用。默认配置下三层记忆的向量检索召回质量为零，与「长程记忆」名义完全不符。

**M2. 连续性检查缺少历史叙事输入。**
`ContinuityCheckerAgent` 当前接收的检查输入主要是当前章与相关设定，缺少历史卷摘要、前章叙事事实和叙事线索台账。长篇中后期检查器看不到早期叙事承诺。

**M3. token 预算估算不安全。**
`memory/TokenBudget.java`（第 16 行）用 `length()/1.5` 估算 token，`ContextAssembler.applyTokenBudget`（第 763、765-794 行）硬编码 `TOKEN_BUDGET=16000`，按固定优先级逐段字符截断。真实分词器对中文与英文混排的偏差，加上不同 `LlmTaskType` 模型上下文窗口不同，会导致要么过早截断（丢关键设定），要么超限被服务端拒收。

**T1. 遥测与成本核算薄弱。**
`telemetry/EventLog` 落盘 `LLM_CALL`/`LLM_ERROR`/`CHAPTER_WRITTEN`/`CHAPTER_FAILED`（第 22-27 行注释），`ObservedLlmGateway` 可记录部分 token 用量，但事件没有 agent 归因和估算成本，`scripts/diagnose.sh` 也只按 `taskType` 聚合。百万字写作会产生数千次 LLM 调用，没有成本视图就无法做退避策略与预算熔断。

**S1. 明文凭据已入 git。**
`application.yml`（第 15、25、34 等行）直接硬编码 GLM 与 DeepSeek 的真实 API key。这些值已在版本历史里，必须当作已泄露处理。

**P1. 缺少确定性长程质量证据。**
`scripts/e2e.sh` 只跑 3 章 replay，`baselines/golden.json` 尚未建立。没有可复现的多章（至少 20-50 章量级）fixture 与基线，无法证明「重写某章后后续章节一致性未退化」「连续 50 章伏笔回收率稳定」这类长程质量断言。当前写作循环中的 FINAL 审计分支不可达，因而没有真正的成书质量门禁。

## 目标

1. **首版可在单机进程崩溃后恢复地写完一本百万字小说**：默认目标 400 章 × ≥2500 汉字/章，单机、单项目同一时刻至多一个活动写作任务；进程重启后能识别中断态、恢复或安全标记失败，终态诚实反映「写满 / 中途熔断 / 被取消」。
2. **章节正文成为唯一可信源，派生产物可失效可重建**：重写或重生成任何章节后，覆盖该章的摘要、向量、实体、伏笔台账要么自动重建，要么被标记为脏并在下次组装上下文时跳过；FINAL 前有真正的成书质量门禁。
3. **长程写作的质量与成本可被机器证明**：存在可复现的多章 replay fixture 与基线，compare-baseline 能对「重写一致性」「伏笔回收率」「单章成本」给出 PASS/FAIL；遥测含 token 用量与估算成本。

## 非目标

- **不做多用户 / 多项目并发**。首版锁定单机、单活动写作任务；用户/鉴权/配额隔离留给 P4。
- **不做云端部署 / 水平扩容 / 分布式调度**。容器化、K8s、远程队列、对象存储均为 P4 议题。
- **不更换数据库引擎**。仍用 SQLite + 嵌入式向量库，只补 WAL、连接池、显式事务、profile；不迁移到 Postgres。
- **不改 Agent 业务逻辑与 prompt 文案**。本 spec 只动编排可靠性、持久化、记忆边界、遥测、配置与 harness；Agent 内部行为与 `.st` 模板变量名不变（影响产出质量的 prompt 改动走另立 spec）。
- **不做实时流式编排重写**。写作循环仍是「逐章同步 + 后台任务」模型，不引入响应式框架。
- **不实现 P4 平台扩展的任何代码**。P4 只在本文档登记为「待定」，不拆 feature。

## 目标架构边界

推荐的首版边界（first-release boundary）：**单机、单活动写作任务、可恢复的 400 章 × ≥2500 汉字**。以下五条边界共同构成这个边界，P0-P3 必须全部落地，P4 整体延后。

### 边界 1：可恢复的持久化写作作业（durable job）
- 写作作业的状态机由 DB 单点承载，`writing_tasks` 是真相源，内存标志仅作快速通道。
- 状态语义见下节「状态语义」。启动时做孤儿清扫；循环内每章前检查取消与中断态；终态区分写满 / 熔断 / 取消 / 失败。
- 单项目至多一个活动作业：新增作业前把同项目 RUNNING/PAUSED 作业收敛到终态。

### 边界 2：章节提交 vs 派生产物（chapter commit vs derived artifacts）
- `Chapter` 正文（`content`/`wordCount`/`version`）是**可信源**。引入「章节提交」语义：写满 ≥500 字（沿用现有空内容门槛）即视为已提交，正文不可在未 bump version 的情况下静默覆盖。
- 摘要、向量索引、实体图、伏笔台账、`ChapterReview`、`ContinuityIssue` 全部是**派生产物**，带 `sourceChapterVersion`（或等价指纹）。
- 任何正文变更（重写、段落编辑、卷末重算）必须：(a) bump version；(b) 把覆盖该章的派生产物标记为脏；(c) 在下次组装上下文 / FINAL 门禁前按需重建。
- 重写路径补「重算覆盖该章的摘要」一步，消除 D2 的脏读。

### 边界 3：版本化记忆（versioned memory）
- 三层记忆（结构化知识库 / 滚动摘要 / 向量检索）的产物都带版本指纹，指向其依据的章节版本。
- `ContextAssembler` 组装前先过滤脏产物；为连续性检查段补齐历史卷摘要与叙事线索台账（对齐 M2）。
- token 预算按 `LlmTaskType` 取模型 `max-tokens` 推导，估算改用与模型对齐的分词近似（可先用更稳的字符/Token 系数表，留扩展点接真实 tokenizer），消除 M3。

### 边界 4：SQLite / 向量 profile
- 引入 `persistence.profile` 概念，至少两个档：`embedded-dev`（默认，WAL + 单连接池，适合本地）、`embedded-prod`（WAL + HikariCP 池 + `busy_timeout` + 外键开）。首版单机用 `embedded-prod` 档。
- 所有跨表写入走显式事务（写一章 = 一个事务），失败整体回滚。
- 向量 `embedding` 默认档改为 `cloud-api`（真实 GLM embedding-3），`stub` 仅测试用；profile 文档明确两档差异。消除 D3 与 M1。

### 边界 5：遥测与评估闭环（telemetry / evaluation）
- `LLM_CALL` 事件补 `promptTokens`/`completionTokens`/`estimatedCostCny`，`diagnose.sh` 增成本聚合。
- 新增多章确定性 fixture（20-50 章 replay 基线）与「重写一致性」「伏笔回收率」两条基线断言，补 P1 的长程证据缺口。
- FINAL 状态前跑一次真正的成书质量门禁（连续性全局扫描 + 未回收伏笔终审 + 成本/篇幅回执），不通过则标 `NEEDS_REVISION` 而非 `FINAL`。

## 状态语义（显式定义）

写作作业与项目状态必须语义诚实，以下为唯一来源：

**WritingTask.Status（DB 真相源）：**
- `RUNNING`：后台线程正在写。唯一允许 `cancel()` 生效的状态；循环每章前检查。
- `PAUSED`：用户主动暂停或检查点挂起。恢复后从下一个未完成章继续。
- `COMPLETED`：**仅当** `chaptersWritten >= maxChapters`（写满）或大纲所有 synopsis 已写完且 FINAL 门禁通过。否则不得置 COMPLETED。
- `FAILED`：连续失败熔断（沿用 3 次单章失败 / 10 章 HIGH 问题阈值）或 FINAL 门禁不通过且用户选择中止。
- `CANCELLED`：用户取消且循环确已停止（DB 写 CANCELLED 必须在循环退出后，不得与 COMPLETED 竞态）。

**NovelState（项目级，沿用现有枚举，补语义）：**
- `CONCEIVING → PLANNING → WRITING`：沿用。
- `VOLUME_DONE`：当前卷写完，等待卷末派生产物结算（摘要、卷末评分、关系演变）。
- `FINAL`：仅在成书门禁通过后设置；门禁不通过改置 `NEEDS_REVISION`（新增，不破坏现有迁移：默认值兼容旧 FINAL 行）。
- 启动孤儿清扫：进程启动时把所有 `RUNNING` 的 WritingTask 收敛为 `FAILED`（`errorMessage=进程未干净退出`），把项目停在 `WRITING` 的标记可恢复；不自动续写，由用户显式触发恢复。

## 验收标准（分阶段，机器可验证）

### P0：任务正确性与安全（job correctness + security）
- [ ] `mvn test -Dtest=WritingTaskExecutorTest` 通过：断言「循环运行中调用 cancel 后，循环在当前章完成后退出且 WritingTask 终态为 CANCELLED」（复现 J1 的 failing test 先行）。
- [ ] `mvn test -Dtest=WritingTaskRecoveryTest` 通过：构造「DB 存在 RUNNING 任务 + 进程重启」场景，启动后该任务被收敛为 FAILED，项目可重新启动写作。
- [ ] `mvn test -Dtest=HonestTerminalStateTest` 通过：构造连续 3 次单章失败熔断，断言 WritingTask 终态为 FAILED 而非 COMPLETED（复现 J2）。
- [ ] `grep -n "sk-" src/main/resources/application.yml` 零命中明文 key（S1 凭据外移后，文件里只剩 `${...}` 占位）。
- [ ] `mvn test -Dtest=SecretsRedactionTest` 通过：断言 `application.yml` 中所有 `api-key` 均为 `${ENV_VAR}` 形式，且启动时缺失环境变量 fail-fast（而不是回退到任何硬编码值）。

### P1：数据正确性（chapter commit + SQLite profile + summary invalidation）
- [ ] `mvn test -Dtest=ChapterCommitBoundaryTest` 通过：重写章节后 `Chapter.version` 自增，覆盖该章的摘要被标记脏，下次组装上下文不读脏摘要（复现 D2）。
- [ ] `mvn test -Dtest=SqliteProfileTest` 通过：profile=`embedded-prod` 时连接带 `journal_mode=WAL` + `busy_timeout>0` + 外键开；写一章的跨表写入在同一事务内，模拟中途抛错断言整体回滚（复现 D3）。
- [ ] `./scripts/e2e.sh --mode replay --chapters 5` 仍 PASS（确认 profile 改造未破坏短程）。

### P2：长程记忆（versioned memory + continuity history + token safety + real embeddings）
- [ ] `mvn test -Dtest=VersionedMemoryTest` 通过：派生产物带 `sourceChapterVersion`，脏产物在 `ContextAssembler` 组装前被过滤。
- [ ] `mvn test -Dtest=ContinuityHistoryInputTest` 通过：连续性检查段包含历史卷摘要与叙事线索台账（复现 M2），对 50 章规模的合成数据断言非空。
- [ ] `mvn test -Dtest=TokenBudgetSafetyTest` 通过：token 预算按 `LlmTaskType` 模型 `max-tokens` 推导，且组装后 prompt token 估计不超过该模型窗口的 90%（复现 M3）。
- [ ] `mvn test -Dtest=EmbeddingProfileTest` 通过：默认 profile 下 `embedding=cloud-api`，`stub` 仅当显式 profile=`test` 时生效（复现 M1）。

### P3：确定性质量证据（multi-chapter fixture + FINAL audit + telemetry cost）
- [ ] `./scripts/e2e.sh --mode replay --chapters 20` PASS，产出 `data/e2e/e2e-report.json` 与新增 20 章基线；`python3 scripts/compare-baseline.py` 对「重写一致性」「伏笔回收率」断言 PASS（复现 P1 长程证据）。
- [ ] `mvn test -Dtest=FinalAuditGateTest` 通过：FINAL 前跑成书门禁（连续性全局扫描 + 未回收伏笔终审 + 成本回执），门禁不通过时项目置 `NEEDS_REVISION` 而非 `FINAL`（复现 P1 死审计）。
- [ ] `mvn test -Dtest=LlmCostTelemetryTest` 通过：`LLM_CALL` 事件含 `promptTokens`/`completionTokens`/`estimatedCostCny`；`./scripts/diagnose.sh` 输出按 `taskType` 的成本聚合行（复现 T1）。

### P4：平台扩展（deferred，不拆 feature）
- 多用户、云部署、远程队列、水平扩容、对象存储备份：全部延后，待首版单机百万字验证通过后再立 spec。

## 影响面

- **涉及模块**：`orchestrator/`（WritingTask 恢复、取消接循环、FINAL 门禁、重写摘要重算）、`service/WritingTaskExecutor`（持久化取消、孤儿清扫、诚实终态）、`persistence/sqlite/`（ConnectionProvider 池化、Schema WAL、Repository 事务边界）、`memory/`（ContextAssembler 过滤脏产物 + 连续性历史输入 + token 预算推导、TokenBudget 近似、版本指纹）、`persistence/embedding/`（profile 默认档）、`telemetry/`（EventLog + ObservedLlmGateway 补 token/成本）、`config/`（PersistenceProperties profile、新增 LlmProperties 的 ENV 注入）、`web/`（启动恢复端点、终态展示）、`scripts/`（多章 fixture/基线、diagnose 成本聚合）。
- **涉及 prompt 模板**：无。本 spec 不改 `.st` 文案与变量名；连续性历史输入的补齐在 `ContextAssembler` 的段组装层完成，不动模板。
- **数据库 schema 变更**：
  - `writing_tasks` 无 schema 变更（语义修正，不改列）。
  - 派生产物表（`chapter_summaries`/`volume_summaries`/`knowledge_entries`/`foreshadowings`/`entity_edges`/`chapter_reviews`）新增可空 `source_chapter_version` 列与 `dirty` 标志列；迁移策略：`ALTER TABLE ... ADD COLUMN ... `（SQLite 支持可空列增量），旧行默认 `dirty=0`、`source_chapter_version=NULL`，兼容现有数据。
  - 项目表新增可空 `final_audit_status` 列（兼容 `NEEDS_REVISION`）。
- **质量基线影响**：P3 新增 20 章 replay 基线，需要一次 `compare-baseline.py --bless`（理由：首版引入长程基线，属预期内的基线建立，不是质量退化）。
- **配置破坏性变更**：`application.yml` 中所有明文 `api-key` 改为 `${ENV_VAR}`；启动 README / `init.sh` 需声明必须导出的环境变量名（不打印值）。Profile 概念引入后，`persistence.embedding` 默认值从 `stub` 改为 `cloud-api`。

## 安全与凭据轮换（硬要求）

- **现存凭据视为已泄露**：`application.yml` 中当前硬编码的 GLM 与 DeepSeek API key 已进入 git 历史，必须在合并 P0 前到各自控制台**作废并重新签发**新 key。本 spec 及任何衍生文档、commit message、PR 描述都**不得打印任何 key 明文值**，只允许以「GLM embedding key」「DeepSeek 写作 key」等指代。
- **凭据外移到环境变量**：`application.yml` 改为 `${DEEPSEEK_API_KEY:}`、`${GLM_EMBEDDING_API_KEY:}` 形式（冒号后留空，缺失时启动 fail-fast，不回退硬编码）。本地开发通过 `.env` / shell 导出，`.env` 加入 `.gitignore`。
- **历史清理**：凭据外移后，用 `git filter-repo` 或等价手段从历史中清除旧值（执行由仓库所有者决定，本 spec 不自动执行 git 写操作）。在 key 作废前，历史清理不能替代轮换。
- **测试隔离**：单测与 e2e 的 `application-test.yml` / replay 模式一律不读真实 key；Stub/RecordReplay 路径不触达云。

## Feature 拆分

> 拆分规则见 AGENTS.md「需求拆分协议」。每个 feature 1-3 个文件改动、可独立验证、附机器可验证 acceptance 与 verify_cmd。登记 backlog 时每行需带 `dependencies` / `acceptance` / `verify_cmd` 字段。

| id | name | 依赖 | acceptance | verify_cmd | 文件（1-3） |
|----|------|------|------------|------------|-------------|
| mc-p0-01 | 写作循环接取消信号 | [] | 复现 J1 的 failing test：cancel 后循环在当前章完成后退出，WritingTask 终态 CANCELLED；循环内每章前查取消标志 | `mvn test -Dtest=WritingTaskCancelTest` | `service/WritingTaskExecutor.java`、`orchestrator/NovelOrchestrator.java` |
| mc-p0-02 | 启动孤儿 RUNNING 任务清扫 | [mc-p0-01] | DB 存在 RUNNING 任务时，Bean 启动后该任务收敛为 FAILED(errorMessage=进程未干净退出)，项目可重新启动写作 | `mvn test -Dtest=WritingTaskRecoveryTest` | `service/WritingTaskExecutor.java`、配置类 |
| mc-p0-03 | 诚实终态（写满 vs 熔断 vs 取消） | [mc-p0-01] | 连续 3 次单章失败或 10 章 HIGH 熔断时 WritingTask 终态为 FAILED；仅写满或大纲写完且门禁通过才 COMPLETED | `mvn test -Dtest=HonestTerminalStateTest` | `service/WritingTaskExecutor.java`、`orchestrator/NovelOrchestrator.java` |
| mc-p0-04 | 凭据外移到环境变量 + fail-fast | [] | `application.yml` 中 `api-key` 全部为 `${ENV_VAR}` 形式；缺失环境变量时启动失败而非回退硬编码；明文 key 零命中 | `mvn test -Dtest=SecretsRedactionTest` | `src/main/resources/application.yml`、`llm/config/LlmProperties.java` |
| mc-p0-05 | 单项目单活动作业收敛 | [mc-p0-01] | 同项目存在 RUNNING/PAUSED 时启动新作业，旧作业被收敛到终态且不产生两个并发循环 | `mvn test -Dtest=SingleActiveJobTest` | `service/WritingTaskExecutor.java` |
| mc-p1-01 | 章节提交边界 + version 自增 | [] | rewriteChapter/editPassage 后 Chapter.version 自增；正文未 bump version 不得覆盖；派生产物携带 sourceChapterVersion | `mvn test -Dtest=ChapterCommitBoundaryTest` | `domain/Chapter.java`、`orchestrator/NovelOrchestrator.java` |
| mc-p1-02 | 重写后失效覆盖该章的摘要 | [mc-p1-01] | rewriteChapter 后覆盖该章的 chapter/volume 摘要标记 dirty；ContextAssembler 组装前过滤 dirty 摘要或触发重算（复现 D2） | `mvn test -Dtest=SummaryInvalidationOnRewriteTest` | `orchestrator/NovelOrchestrator.java`、`memory/ContextAssembler.java` |
| mc-p1-03 | SQLite WAL + busy_timeout + 外键 profile | [] | profile=embedded-prod 时连接带 journal_mode=WAL、busy_timeout>0、foreign_keys=ON | `mvn test -Dtest=SqliteProfileTest` | `persistence/sqlite/ConnectionProvider.java`、`config/PersistenceProperties.java` |
| mc-p1-04 | 写一章跨表写入走单事务 | [mc-p1-03] | 写一章的多表写入在同一事务；模拟中途抛错断言整体回滚（无半成品章节） | `mvn test -Dtest=ChapterTransactionTest` | `persistence/sqlite/SqliteNovelRepository.java` |
| mc-p1-05 | 默认 targetChapters/layeredOutline 对齐首版 | [] | WritingPreferences 默认 targetChapters 与 layeredOutline 经首版 profile 决定（400/true 单机档），旧库兼容迁移 | `mvn test -Dtest=WritingPreferencesDefaultsTest` | `domain/WritingPreferences.java`、`persistence/sqlite/SqliteSchemaInitializer.java` |
| mc-p2-01 | 派生产物 sourceChapterVersion + dirty 过滤 | [mc-p1-01] | 摘要/实体/伏笔/Review 带 sourceChapterVersion；ContextAssembler 组装前过滤 dirty 产物 | `mvn test -Dtest=VersionedMemoryTest` | `memory/ContextAssembler.java`、`persistence/sqlite/SqliteNovelRepository.java` |
| mc-p2-02 | 连续性检查补历史卷摘要 + 叙事线索台账 | [mc-p2-01] | 连续性检查段含历史卷摘要与叙事线索台账；50 章合成数据断言非空（复现 M2） | `mvn test -Dtest=ContinuityHistoryInputTest` | `memory/ContextAssembler.java` |
| mc-p2-03 | token 预算按模型推导 + 安全分词近似 | [] | TokenBudget 按 LlmTaskType 模型 max-tokens 推导预算；组装后估计 token ≤ 模型窗口 90%（复现 M3） | `mvn test -Dtest=TokenBudgetSafetyTest` | `memory/TokenBudget.java`、`memory/ContextAssembler.java` |
| mc-p2-04 | 向量 embedding 默认 cloud-api 档 | [] | 默认 profile 下 embedding=cloud-api；stub 仅 test profile 生效（复现 M1） | `mvn test -Dtest=EmbeddingProfileTest` | `config/PersistenceConfig.java`、`src/main/resources/application.yml` |
| mc-p3-01 | 20 章 replay fixture + 长程基线 | [mc-p2-01,mc-p2-02] | `e2e.sh --mode replay --chapters 20` PASS；compare-baseline 对重写一致性/伏笔回收率 PASS | `./scripts/e2e.sh --mode replay --chapters 20 && python3 scripts/compare-baseline.py` | `scripts/e2e.sh`、`baselines/golden.json` |
| mc-p3-02 | FINAL 成书质量门禁复活 | [mc-p1-02,mc-p2-02] | FINAL 前跑连续性全局扫描 + 未回收伏笔终审 + 成本回执；门禁不通过置 NEEDS_REVISION（复现 P1 死审计） | `mvn test -Dtest=FinalAuditGateTest` | `orchestrator/NovelOrchestrator.java`、`domain/NovelState.java`（或新增枚举值兼容迁移） |
| mc-p3-03 | LLM token 用量 + 估算成本埋点 | [] | LLM_CALL 事件含 promptTokens/completionTokens/estimatedCostCny；diagnose.sh 输出按 taskType 成本聚合（复现 T1） | `mvn test -Dtest=LlmCostTelemetryTest` | `llm/gateway/ObservedLlmGateway.java`、`telemetry/EventLog.java` |
| mc-p3-04 | diagnose.sh 成本聚合 | [mc-p3-03] | diagnose.sh 对含 LLM_CALL 的事件输出按 taskType 的成本合计行 | `./scripts/diagnose.sh data/e2e/events.jsonl` | `scripts/diagnose.sh` |

> 说明：P4（多用户/云/扩容）不拆 feature，待首版单机百万字验证通过后另立 spec。

## 风险与备选方案

**风险与缓解：**
- **凭据轮换期间服务不可用**：DeepSeek/GLM key 作废到新 key 生效有窗口。缓解：先在环境变量里准备好新 key 并验证，再作废旧 key；replay 模式不依赖真实 key，可在轮换窗口继续开发。
- **派生产物重建放大 LLM 成本**：重写一章后重算摘要会多一次 `MEMORY_SUMMARY` 调用。缓解：dirty 标记 + 惰性重建（组装上下文时按需重建，而非写后立即重建）；P3 的成本遥测给出可见性。
- **WAL + 单进程并发写仍可能 busy**：缓解：`busy_timeout` 设足够大（如 5000ms），且首版锁定单活动作业，无并发写。
- **20 章 replay fixture 体积与维护成本**：fixture 按归一化 prompt SHA-256 命名，20 章量级 fixture 可能达数十 MB。缓解：fixture 存 `data/fixtures/llm/`，`.gitignore` 视情况只签入小规模冒烟 fixture，大规模 fixture 走 record-on-demand；compare-baseline 容差放宽对确定性指标（回收率、终态）零容忍，对字数保留容差。
- **schema 迁移破坏旧库**：缓解：所有新增列可空 + 默认值兼容；`SqliteSchemaInitializer` 的 `CREATE TABLE IF NOT EXISTS` + `ALTER TABLE ADD COLUMN` 链保持幂等。
- **FINAL 门禁过严导致项目卡 NEEDS_REVISION**：缓解：门禁分硬性（未回收 mustResolve 伏笔、连续性 HIGH 未解决）与软性（成本超预算）两档，软性只警告不阻塞；提供「人工确认后强制 FINAL」的 escape hatch（记审计事件）。

**被否决的备选方案：**
- **迁移到 PostgreSQL**：能解决 WAL/并发/事务的工程化问题，但违反「不换引擎」边界，且单机百万字不需要。否决；P4 再评估。
- **引入 Quartz / Spring Batch 做作业调度**：功能更强，但首版只需要「DB 持久化状态 + 后台线程 + 恢复清扫」，引入重型调度框架增加运维面。否决；保持现有 `ExecutorService` + DB 真相源模型。
- **用真实 tokenizer（如 jtokkit / tiktoken Java 移植）替换 TokenBudget**：精度最高，但引入新依赖与模型分词器映射维护成本，且首版不需要 token 级精度。否决；P2 先用按模型分档的字符/Token 系数表，留 `TokenBudget` 扩展点，P4 再按需接真实 tokenizer。
- **重写时同步阻塞重建所有派生产物**：语义最干净，但重写一章触发全链重算会严重拖慢交互。否决；采用 dirty 标记 + 惰性重建。
- **把 cancel 改成 Thread.interrupt + 自动恢复续写**：interrupt 易在 IO 中途留下脏状态，自动续写在崩溃恢复语义不清时更危险。否决；cancel 只在章节边界生效，崩溃后收敛为 FAILED 由用户显式恢复，不自动续写。

## 评审检查清单（spec 自检）

- [x] 所有 feature 均有 `dependencies` / `acceptance` / `verify_cmd`，且 acceptance 是机器可验证的命令式断言（非「实现了 X」）。
- [x] 每个 feature 标注 1-3 个文件，满足 AGENTS.md「需求拆分协议」。
- [x] 首版边界明确：单机、单活动作业、可恢复 400 章 × ≥2500 汉字；P4（多用户/云）延后并标注不拆 feature。
- [x] 五条架构边界（durable job / chapter commit vs derived / versioned memory / SQLite+vector profile / telemetry+evaluation）均有对应 feature 与验收。
- [x] 状态语义显式定义，且与现有 `WritingTask.Status` / `NovelState` 枚举对齐，新增 `NEEDS_REVISION` 通过可空列兼容迁移。
- [x] 凭据轮换要求明确：现存 key 视为已泄露须作废重签，外移到环境变量，文档不打印任何 key 明文。
- [x] 无 TBD / TODO / 占位段；所有验收命令对齐仓库现有 `mvn test -Dtest=` / `scripts/e2e.sh` / `scripts/diagnose.sh` / `compare-baseline.py` 约定。
