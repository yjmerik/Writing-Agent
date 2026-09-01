# Spec: 百万字稳定性 + 风格确定性 + 人机交互（Phase 16-19）

> 背景：2026-08-28 四路代码级架构评审（编排器/记忆/风格/交互）定位出 400 章尺度的系统性缺口。
> 同日：LLM 路由全量切换 glm-5.3-flash（智谱，思考模型，128k 窗口）。

## 背景与动机

评审结论（证据见会话记录，代码级 file:line）：

1. **百万字可达性**：HIGH ContinuityIssue 无界累积使 FINAL 门禁必挂；FINAL 前伏笔回收章是死代码路径；FactHarvester 等 4 处全量注入随章节数无界增长（千条事实 ≈ 33k token 爆窗且失败静默）；重写/段落编辑后向量 chunk 不清理（旧情节永久污染检索）；前情摘要"远期卷 stub 挤死最近 3 章细粒度"（截断方向错误）；premise 全量注入不参与预算裁剪。
2. **风格确定性**：网文"爽点+四段式+悬念钩子"范式在模板正文/刚性约束/评审闭环/数据结构四层无条件注入，题材规则只能追加不能关闭；武侠在 GenreProfile 枚举、GenreDetector 关键词、presets 三处全部缺席；volume-outline.st 无 genreRules 变量（项目越长题材约束断链越严重）；ConceiveGate 强制 POWER_SYSTEM 误伤非战斗题材。
3. **人机交互**：INTERACTIVE+write-all 正常暂停被误报 FAILED；checkpoint 审批只有一句 description；无写前指令通道；无实时进度推送。
4. **长程质量**：角色无生死状态（死人复活）；story_facts 无索引且 subject 别名分裂；全书级记忆只有 3 句话。

## 目标

1. 400 章尺度下系统可达 FINAL 且长程记忆不劣化（P0 缺陷全部修复）
2. 武侠题材开箱即用；非网文题材不被爽点范式污染（题材条件化）
3. 交互模式核心缺陷修复：PAUSED 终态正确、审批带质量信息、支持写前指令、写作进度实时可见
4. 全部改动经 mvn test 全绿 + e2e record（glm-5.3-flash）+ compare-baseline

## 非目标

- 不做 ANN 向量索引替换、向量库分片（EmbeddedVectorStore 保持现状）
- 不做后处理链并行化
- 不做 epub 导出、大纲拖拽重排、章节版本 diff UI
- 不做 CostBudgetMonitor 持久化（内存态保持，成本遥测已有 JSONL）
- 不改「爽点：」大纲数据标签（PlotterAgent 解析管道保持兼容，语义按题材重释）
- 不删除/重置既有项目数据

## 验收标准

- [ ] `mvn test` 全绿（0 failures，新增测试 ≥ 40）
- [ ] `./scripts/e2e.sh --mode record --chapters 3`（glm-5.3-flash）3 章完成、0 章节失败、0 LLM 错误
- [ ] `python3 scripts/compare-baseline.py --bless` 后再跑 `./scripts/e2e.sh`（replay）PASS
- [ ] 武侠预设端到端可用：新项目 preset=wuxia → genre=WUXIA、无修仙境界规则、ConceiveGate 不强制 POWER_SYSTEM
- [ ] `cd frontend && npm run build` 通过
- [ ] HIGH issue 老化：构造 >30 章前的未解决 HIGH，卷末归档后 FINAL 门禁通过
- [ ] FactHarvester prompt 长度有上界（构造 500 条事实，prompt 有截断不爆）

## 影响面

- **涉及模块**: orchestrator / memory / agent / service / web / domain / persistence / telemetry / frontend
- **涉及 prompt 模板**: chapter-writing.st（四段式条件化）、volume-outline.st（+genreRules）、plotting.st（爽点语义重释，标签不变）、master-outline.st、style-establish.st（无变量名变更）
- **数据库 schema 变更**: characters 表 + status/death_chapter 列；projects 表 + book_snapshot 列；story_facts 索引；checkpoints 表 + payload 列（均走 SqliteSchemaInitializer 迁移，旧库自动补列）
- **质量基线影响**: 模型从 deepseek-v4-flash 换 glm-5.3-flash + 约束条件化，产出必然变化 → e2e record 后 `--bless` 重建基线（人工确认质量不降）

## Feature 拆分

### Phase 16 — 百万字止血（P0）

| id | name | 依赖 | acceptance | verify_cmd |
|----|------|------|------------|------------|
| p16-01 | HIGH issue 老化 + FINAL 门禁口径 | [] | 构造 30 章前未复发 HIGH → 卷末 ArchiveCompactor 标 resolved；FINAL 门禁只统计近 60 章未解决 HIGH，历史 HIGH 不阻塞 FINAL | `mvn test -Dtest=HighIssueAgingTest` |
| p16-02 | FINAL 伏笔回收章死路修复 | [] | 大纲耗尽且 mustResolve>0 时生成 8 章回收卷（放宽 extend 限制），回收伏笔写进新章梗概而非无人消费的 issue | `mvn test -Dtest=FinalForeshadowingRecoveryTest` |
| p16-03 | FactHarvester 收窄 + fail-loud | [] | 收割 prompt 只含 cast 相关 + importance top 事实（≤80 条）；LLM 失败记 LLM_ERROR 事件不再静默 return | `mvn test -Dtest=FactHarvesterScopedTest` |
| p16-04 | 其余三处全量注入 cap | [] | ForeshadowingExtractor/ContinuityChecker threads/collectSettingsForCheck 注入均 ≤ 上限（伏笔 20/线索 15/设定 20） | `mvn test -Dtest=ContextInjectionCapTest` |
| p16-05 | 重写/编辑路径向量重建 | [] | rewriteForCorrection/rewriteChapter/editPassage 后旧 chunk 清理、新 chunk 入库（检索不再命中旧版本） | `mvn test -Dtest=VectorCleanupOnRewritePathsTest` |
| p16-06 | 摘要分层裁剪 + premise 预算 | [] | 超预算时先丢最老卷 stub、最近细粒度摘要最后丢；premiseSection 参与裁剪链（截断保头部） | `mvn test -Dtest=SummaryLayeredTrimTest` |

### Phase 17 — 风格确定性

| id | name | 依赖 | acceptance | verify_cmd |
|----|------|------|------------|------------|
| p17-01 | WUXIA 题材全链路 | [] | GenreProfile+GenreDetector（武侠/江湖/侠客/门派/镖局/恩仇）+GenreRuleProvider 规则+presets/wuxia.json+前端选项；tags 武侠项目 genre=WUXIA | `mvn test -Dtest=WuxiaGenreTest` |
| p17-02 | 爽点/四段式/钩子题材条件化 | [p17-01] | buildWritingConstraints 接 genre：LITERARY/HISTORICAL/WUXIA 等不强制"至少2爽点/四段式/悬念钩子"，改注入题材结构指引；开篇高压仅快节奏题材；评审反馈词按题材 | `mvn test -Dtest=GenreConditionalConstraintsTest` |
| p17-03 | volume-outline 补 genreRules | [] | volume-outline.st 注入 {{genreRules}}；planVolume 传入；长篇细纲带题材规则 | `mvn test -Dtest=VolumeOutlineGenreRulesTest` |
| p17-04 | ConceiveGate 题材豁免 + 风格细节债 | [] | MYSTERY/ROMANCE/LITERARY/WUXIA(非超自然) 不强制 POWER_SYSTEM；styleVoice 进 extractStyleGuide；preferredQuoteSources 被 buildKnowledgeQuote 消费 | `mvn test -Dtest=ConceiveGateGenreExemptionTest` |
| p17-05 | 自定义预设持久化 | [] | POST /api/presets 落 SQLite（表 custom_presets），重启后 GET 可见 | `mvn test -Dtest=CustomPresetPersistenceTest` |

### Phase 18 — 人机交互

| id | name | 依赖 | acceptance | verify_cmd |
|----|------|------|------------|------------|
| p18-01 | PAUSED 终态修正 | [] | INTERACTIVE 每章暂停后 write-all 任务终态=PAUSED（非 FAILED），message 说明等待审批 | `mvn test -Dtest=PausedTerminalStateTest` |
| p18-02 | checkpoint 审批质量 payload | [] | AFTER_CHAPTER checkpoint 携带 payload JSON（字数/评分/未解决问题数/前 200 字预览）；前端审批面板直接展示 | `mvn test -Dtest=CheckpointPayloadTest` |
| p18-03 | 写前指令通道 | [] | POST /write-chapter 与 write-all 接受 instruction 参数，透传本章 writingNotes；前端"写一章"带可选指令框 | `mvn test -Dtest=WriteInstructionTest` |
| p18-04 | SSE 实时进度推送 | [] | GET /api/projects/{id}/events（SseEmitter）；CHAPTER_WRITTEN/CHAPTER_FAILED/QUALITY 事件实时推送；前端 write-all 期间显示逐章进度条 | `mvn test -Dtest=SseEventBusTest` |

### Phase 19 — 长篇质量结构

| id | name | 依赖 | acceptance | verify_cmd |
|----|------|------|------------|------------|
| p19-01 | 角色生死状态链 | [] | Character.status(ACTIVE/DECEASED/DEPARTED)+deathChapter；FactHarvester 检测死亡自动置位；注入链标注【已故·第N章】；ArcScheduler 不调度死人 | `mvn test -Dtest=CharacterStatusTest` |
| p19-02 | story_facts 索引 + subject 规范化 | [] | (project_id,status) 索引建立；FactHarvester merge 前过 resolveCanonical；别名不再分裂平行事实 | `mvn test -Dtest=SubjectNormalizationTest` |
| p19-03 | 全书结构化快照 | [] | projects.book_snapshot JSON（主线进度/活跃阵营/关键人物状态/时间线锚点），卷末 LLM 生成，ContextAssembler 顶部注入（替代 book_summary 单段） | `mvn test -Dtest=BookSnapshotTest` |

## 风险与备选方案

- **风险 1**：glm-5.3-flash 思考模型的 JSON 输出格式与 DeepSeek 有差异 → ResponseParser 已宽松解析；e2e record 首跑即暴露，失败回流协议处理。
- **风险 2**：p17-02 约束条件化影响产出质量基线 → 必须 e2e + 人工审阅 + --bless；conditions 默认对 GENERAL/CULTIVATION 等网文题材保持现状（零行为变化），仅新条件分支改变非网文题材。
- **风险 3**：p16-01 HIGH 老化可能放过真问题 → 老化阈值 30 章 + 仅"未复发"（该章之后未再被新证据引用）才老化；FINAL 仍统计近 60 章 HIGH。
- **风险 4**：p18-04 SSE 在前端代理/打包环境下的兼容性 → EventSource 原生 API + 降级轮询（现有轮询保留不删）。
- **回滚**：全部改动按 feature 原子提交，可逐个 revert；schema 迁移只加列不删列。
