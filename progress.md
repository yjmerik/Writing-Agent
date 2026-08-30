# Session Progress Log

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
