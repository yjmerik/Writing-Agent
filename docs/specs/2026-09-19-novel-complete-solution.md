# Spec: 长篇小说人机协作系统 · 完整方案 v2.0 落地（novel-complete-solution）

> 输入：`/Users/erik/.codex/visualizations/2026/09/14/01a0a06f-a436-7d83-978a-f334074f66c8/novel-complete-solution.zip`
> （统一正文 design.md 22 章 + index.html + 用户动线/工作台原型/运行时方案/代码审查四个来源附件）。
> 本 spec 将该方案转为可实施需求；方案自身声明命令/对象名为目标设计，不声称已有实现。

## 背景与动机

方案 v2.0（2026-09-15）把小说生成运行时、作者工作台与从 0 到定稿的动线合为一份完整设计，
实施路线为六个里程碑 M1–M6（§19），联合验收 T01–T12（§20），并保留原审查 F01–F22 追溯（§22）。
当前仓库（main a2a74d6，Phase 43 完成）已具备大量协作基础，但对照方案仍有结构性缺口
（跨章修订闭环、谓词化断言、时点知情视图、SceneContract、卷末回读、审美档案、
WorkspaceView 统一读取、成书 UI 闭环、epoch 租约等——见 Current State / Gap Analysis）。

## Current State（以 main a2a74d6 代码为准，2026-09-19 核实）

已具备（不重复实现）：
- **协作指令**：AuthorDirective（HARD/ONCE/PREFERENCE）版本化生命周期、按章×稿证据行、
  HARD 超预算即阻断注入（p37）； dismissal/revoke/CAS。
- **正文版本**：ChapterVersion 不可变快照、ChapterRevisionService 乐观版本+分片锁+派生刷新隔离
  （刷新失败仅 derivedRefreshPending，不回滚正文，p32）；本地草稿冲突副本（draftStore）。
- **候选隔离**：RevisionProposal（READY/APPLIED/STALE/CANCELLED，requestKey 幂等，apply 校验
  baseVersion+sourceHash，不匹配标 STALE 不写入）。
- **审阅**：ChapterApproval 身份绑定（version+contentHash+reviewSetHash+幂等键）、ReviewBarrier
  历史、复检身份绑定（recheck）、问题处置（FALSE_POSITIVE/EXCEPTION/DEFER，身份绑定）。
- **成书快照**：BookEdition（manifestHash=章稿正典 JSON SHA-256，approve 校验快照身份而非浮动
  当前稿，export 固定字节）——但**前端未接入**。
- **事实证据**：StoryFact（类型/状态/evidenceGrade）+ fact_evidence（章×稿×quote×anchorHash，
  validFrom/validToChapter）+ observers（learnedAtChapter）——后端有"as-of"数据，**无读取视图**。
- **计划**：分层大纲（premise/卷/章 synopsis+beats+cast+plotPurpose+伏笔）、惰性扩卷、
  未写部分替换、目标章数变更带承诺决策（MIGRATE/OPEN_END，PlanChange）。
- **运行**：WritingTask 持久化+孤儿清扫（重启转 PAUSED）、post_process_ledger 阶段断点续跑、
  ProjectWorkCoordinator 进程内独占、CostBudgetMonitor 预算暂停、SSE 事件流、RecordReplay 网关。
- **检查**：Continuity/Consistency/Reader/Style + QualityCouncil/Arbiter、finalGate
  （mustResolve>3 / 近 60 章 HIGH>10 阻塞——即 F14 批评的数量容忍+近期窗口）。
- **前端**：6 页签工作区（创作工具/世界观/大纲/章节管理/质量/成书审查）+ 模型配置页；
  URL 权威路由、审阅策略 MANUAL/CHAPTER/VOLUME、自由指令。

结构性缺口（→ 目标状态，方案章节引用）：
1. 持久 epoch 租约：停止/接手后晚到的 Agent 写入跨重启仍可被拒（§14 R08）。
2. 最终请求窗口核算：TokenBudget 在组装期估算，无"序列化输入+输出预留+安全余量 ≤ 模型窗口"
   的最终核算与模型切换重核算（§10 R11/F12）。
3. AuthorIntent 创作约定聚合 + 空作品起步指引缺失（§05 J01/J02；现有 concept/tags 不构成契约）。
4. WorkspaceView 统一读取协议缺失；状态由多端点拼装（§15）。
5. 检查结果信封缺失：解析失败/空响应与"无问题"混同（§13 R09/F10）。
6. 谓词化断言缺失：事实以"主体+类型"覆盖，否定可被当同义确认（§09 R02/F03）。
7. 来源撤回传播弱：唯一证据失效不显式失去支持（§09 R03/F04）。
8. 人物时点读取合同（as-of / 知情边界 / 信念 vs 事实）无 API 与 UI（§07 R01/R15/UX07）。
9. CharacterDecision 关键决策不可追踪（§07 F16）。
10. 统一 RevisionRequest：六类修改入口未归一，无范围批准与内容采纳分离（§12 F07/F09）。
11. 跨章影响分析/修复候选/依赖组整组接受缺失——密信场景无法闭环（§12 R04/R14/F01）。
12. SceneContract 缺失（章纲有 beats 但无因果合同字段）（§06 F15）。
13. 计划偏离对照与"按正文更新计划"显式闭环缺失（§06）。
14. 卷末回读（ReviewVolume）缺失：卷末只有自动汇总（§06 J08）。
15. AestheticProfile 审美档案缺失（§13 F17）。
16. 三类质量信息（必须处理/尚未查清/文学建议）未分层呈现（§13 UX09）。
17. 选择性组合采纳（新哈希+重检）缺失（§13 R18/UX10）。
18. 委托策略仅审阅节奏，无按变更类别的授权分级（§05 DELEGATION）。
19. 运行经验库（接受/拒绝样本复用）缺失（§13 Reflexion/L4）。
20. BookEdition 前端未接入；定稿校验未绑定 manifest；工作稿/定稿导出区分未呈现（§16 UX11/F14）。
21. 任务与版本中心视图缺失（§14/§16 U6）。

## Target State（按方案 M1–M6 里程碑）

- **M1 可信执行与起步边界**：epoch 租约拒晚写；最终请求预算；AuthorIntent 聚合与创作约定
  工作区+空态指引；WorkspaceView 统一读取+稿件身份栏/任务条；检查结果信封（UNKNOWN≠无问题）。
- **M2 有来源的故事状态**：谓词断言+否定冲突；来源撤回传播；人物时点读取 API 与知情视图 UI；
  CharacterDecision 记录。
- **M3 跨章修订闭环**：统一 RevisionRequest（六入口归一+范围批准）；文本变更影响分析
  （MUST_REPAIR/RECHECK/UNAFFECTED_WITH_REASON）；隔离修复候选；依赖组整组接受/重基/幂等；
  修订工作区 UI 四步。
- **M4 长程叙事与人物**：SceneContract 结构与注入；计划偏离对照与双向校正；卷末回读报告；
  按正文更新计划。
- **M5 艺术迭代与协作**：审美档案；三类信息分层审阅 UI；选择性组合采纳；委托策略分级；
  运行经验库。
- **M6 成书闭环**：BookEdition UI（创建/详情/确认定稿/导出固定版本，工作稿 vs 定稿区分）；
  定稿校验绑定 manifest（修正 F14 数量容忍+近期窗口）；任务与版本中心；联合验收浏览器 Journey。

## Gap Analysis 汇总（状态 × 影响面）

| # | 目标（方案 §） | 现状 | 层面 | Feature |
|---|---|---|---|---|
| 1 | epoch 租约拒晚写（§14/R08） | 未实现（进程内协调器+孤儿清扫） | persistence+service | NCS-001 |
| 2 | 最终请求窗口核算（§10/R11） | 部分实现（组装期预算） | llm+memory | NCS-002 |
| 3 | AuthorIntent 聚合（§05/J01-02） | 冲突（concept/tags 非契约） | domain+persistence+API | NCS-003 |
| 4 | 创作约定 UI+空态（§03/§05/§17） | 未实现（偏好散在侧栏） | frontend | NCS-004 |
| 5 | WorkspaceView（§15） | 部分实现（status/workspace 分立） | service+API | NCS-005 |
| 6 | 稿件身份栏/任务条（§03/§17） | 部分实现（CollaborationBar 混合） | frontend | NCS-006 |
| 7 | 检查结果信封（§13/R09-10） | 冲突（解析失败≈无问题风险） | agent+service | NCS-007 |
| 8 | 谓词断言+否定冲突（§09/R02） | 冲突（主体+类型覆盖） | domain+agent+prompt | NCS-008 |
| 9 | 来源撤回传播（§09/R03） | 部分实现（supersede 链存在） | service | NCS-009 |
| 10 | 时点读取 API（§07/R01/R15） | 部分实现（数据在，无读取合同） | service+API | NCS-010 |
| 11 | 知情视图 UI（§07/UX07） | 未实现 | frontend | NCS-011 |
| 12 | CharacterDecision（§07/F16） | 未实现 | domain+agent+prompt | NCS-012 |
| 13 | 统一 RevisionRequest（§12） | 部分实现（各入口分立） | domain+service | NCS-013 |
| 14 | 影响分析（§12/R04） | 部分实现（仅人物变更预览） | service | NCS-014 |
| 15 | 修复候选生成（§12） | 未实现（单章候选有） | service | NCS-015 |
| 16 | 整组接受/重基（§12/R14） | 未实现 | service | NCS-016 |
| 17 | 修订工作区 UI（§12/UX04） | 未实现 | frontend | NCS-017 |
| 18 | SceneContract（§06/F15） | 部分实现（beats 无因果合同） | domain+prompt+context | NCS-018 |
| 19 | 计划偏离双向校正（§06） | 未实现 | post-process+frontend | NCS-019 |
| 20 | 卷末回读（§06/J08） | 未实现（自动汇总而已） | service+prompt | NCS-020 |
| 21 | 按正文更新计划（§06） | 部分实现（replan 不带回读输入） | service | NCS-021 |
| 22 | 审美档案（§13/F17） | 未实现 | domain+context+frontend | NCS-022 |
| 23 | 三类分层审阅（§13/UX09） | 部分实现（处置有，呈现混同） | frontend+API | NCS-023 |
| 24 | 选择性组合采纳（§13/R18） | 未实现 | service+frontend | NCS-024 |
| 25 | 委托策略分级（§05/DELEGATION） | 部分实现（节奏型策略） | domain+service+frontend | NCS-025 |
| 26 | 运行经验库（§13/L4） | 未实现 | domain+prompt | NCS-026 |
| 27 | BookEdition UI（§16/UX11） | 冲突（后端有前端无） | frontend | NCS-027 |
| 28 | 定稿校验绑定（§16/R13/F14） | 冲突（数量容忍+近期窗口） | service+gate | NCS-028 |
| 29 | 任务与版本中心（§14/§16/U6） | 部分实现（分散） | frontend | NCS-029 |
| 30 | 联合验收 Journey（§20/T01-11） | 未实现 | acceptance | NCS-030 |

## 非目标（Non-goals）

1. **真实模型长篇质量实验与真人盲评/消融**（方案 §20 T12、长篇实验二/三阶段）：需要真实模型
   大规模成本与真人读者，方案自身定位为独立证据层而非实现项；本轮以确定性 E2E + replay
   浏览器验收替代，prompt 质量变化遵循项目 fixture/baseline 规则（含人工 bless gate）。
2. 对外发布、EPUB/TXT 等新导出格式（维持 Markdown 导出）。
3. 图数据库、微服务拆分、多机写入、LangGraph/Python 迁移（方案 §22 明确暂不采用）。
4. WCAG 认证与完整无障碍审计、手机端专属布局（保留既有响应式；组件语义按方案 §17 实现）。
5. 模型微调记忆。
6. 八视图原型的像素级视觉复刻（沿用 AntD，按方案信息架构与状态语义实现）。
7. 全系统纯事件溯源改造。
8. 既有 deferred p40-05/p40-06 的恢复。
9. 不修改研发 harness 本身（scripts/check-harness 等）。

## Final Acceptance Criteria（Goal 完成条件）

- [ ] 方案 22 章需求 → 30 个 NCS feature 映射完整（见 Feature 拆分与 Traceability）
- [ ] 全部 NCS feature `completed` 且合入 main，evidence 齐全（分支/实现提交/主分支提交/
      verify_cmd/compile/test/build/e2e/baseline 结果）
- [ ] main 工作树 clean；`./init.sh` PASS；`mvn compile` PASS；`mvn test` 0 失败 0 错误
- [ ] 各 feature verify_cmd PASS；`cd frontend && npm run build` PASS
- [ ] `./scripts/e2e.sh` 确定性回放 PASS；`python3 scripts/compare-baseline.py` PASS
      （prompt 类改动按规则重建 fixture、双次 replay 一致、质量对比经人工确认后 bless）
- [ ] 浏览器 UI E2E：T01–T11 场景在真实 UI 走通（replay 模式），console 无本次实现相关异常、
      Network 无非预期 4xx/5xx，报告落 docs/reports/
- [ ] feature_list.json / progress.md / session-handoff.md 与真实状态一致，无本方案
      not-started/in-progress 遗留
- [ ] Requirement Traceability Matrix（方案→spec→feature→commit→证据）无遗漏

## 影响面

- **涉及模块**: llm/gateway、memory、agent（FactHarvester/Plotter/ContinuityChecker/
  ContentRevision）、service（新增 WriteLease/WorkspaceView/ImpactAnalysis/RevisionRepair/
  VolumeReadback/CharacterAsOf 等）、orchestrator（finalGate）、persistence（schema 新表+列）、
  web（新 controller）、frontend（新页签/视图/组件）
- **涉及 prompt 模板**: fact-harvest.st（NCS-008，变量名不改只增）、plotter 相关 .st（NCS-018）、
  新增模板（决策提取/卷末回读/偏离对照/修复生成，均为新增文件不动既有变量名）
- **数据库 schema 变更**: 新表（project_write_leases、author_intents、character_decisions、
  revision_requests、impact_items、aesthetic_profiles、revision_outcomes、volume_readbacks、
  edition 校验报告表）+ 既有表 safeAddColumn 扩列（story_facts 谓词列等）；全部 ADD COLUMN/
  CREATE IF NOT EXISTS 兼容迁移，不删数据
- **质量基线影响**: NCS-008/012/018/019/020/022 改变注入上下文或写作管线 prompt → 旧 replay
  fixture 必然分叉：按 AGENTS.md 重建 fixture（E2E_FIXTURE_DIR 按日期）、连续两次 replay 一致、
  新旧质量对比展示，FAIL 时**暂停等待人工确认后 --bless**；其余 feature 不触碰 prompt 指纹

## Feature 拆分（NCS = Novel Complete Solution；phase 44–49 对应 M1–M6）

依赖为 DAG；执行顺序按编号（依赖满足后）。每个 feature 遵循完整特性分支与合并协议。

| id | name | deps | acceptance（摘要） | verify_cmd |
|----|------|------|--------------------|------------|
| NCS-001 | 持久写入门禁与 epoch 租约 | [] | 停止/接手/取消递增持久 epoch；晚到的 Agent 写入（候选 apply/后处理落库）epoch 不匹配被拒并留事件；作者直存不受阻；重启后仍生效 | `mvn test -Dtest=WriteLeaseEpochTest` |
| NCS-002 | 最终请求窗口核算 | [] | 发送前核算序列化输入+输出预留+安全余量≤模型窗口，超限按策略缩减/失败并留事件；fallback 换模型后重核算；不静默截断 | `mvn test -Dtest=FinalRequestBudgetTest` |
| NCS-003 | AuthorIntent 创作约定聚合 | [] | GET/PUT intent 版本化契约（原旨/类型/主题/视角/样本/篇幅/自主度/反面模式）；创建即空契约合法；历史可查 | `mvn test -Dtest=AuthorIntentApiTest` |
| NCS-004 | 创作约定工作区 UI 与空态指引 | [003] | 新「创作约定」页签编辑/保存/版本；空项目显示具体下一步指引 | `npm --prefix frontend run build` |
| NCS-005 | WorkspaceView 统一读取 | [] | 单端点返回 identity/contentStatus/run/review(含 unknown/stale)/availableActions(reason)/revisionOfView | `mvn test -Dtest=WorkspaceViewTest` |
| NCS-006 | 稿件身份栏与任务条 UI | [005] | 常驻身份栏（对象/稿号/保存/核对）+任务条（动作/等待原因/继续入口），数据来自 workspace-view | `npm --prefix frontend run build` |
| NCS-007 | 检查结果信封与「尚未查清」 | [] | 坏 JSON/空/截断/拒绝 → INVALID/UNKNOWN 落库非空非 PASS；门禁视 UNKNOWN 必查为阻塞；零预算不放行 | `mvn test -Dtest=CheckResultEnvelopeTest` |
| NCS-008 | 谓词化断言与否定冲突 | [] | 事实带 predicate/object/polarity/validFrom/To；同主体+谓词反极性并存产出冲突记录不静默覆盖 | `mvn test -Dtest=PredicateAssertionTest` |
| NCS-009 | 来源撤回与支持传播 | [008] | 唯一证据失效→UNSUPPORTED+事件+停注入；多证据保留；证据绑定稿版本 | `mvn test -Dtest=SupportWithdrawalTest` |
| NCS-010 | 人物时点读取 API | [008] | as-of(N)：状态事实/已知(含来源)/所信(可错)/关系边；不含 N 之后内容 | `mvn test -Dtest=CharacterAsOfTest` |
| NCS-011 | 人物时点知情视图 UI | [010] | 时点选择器+作者/角色/读者知识分列+错误信念标注+全书视角后文提示 | `npm --prefix frontend run build` |
| NCS-012 | CharacterDecision 决策记录 | [008] | 触发/所知/选择/代价/证据按章落库；经历视图可跳来源；提取失败 UNKNOWN | `mvn test -Dtest=CharacterDecisionTest` |
| NCS-013 | 统一修订请求与范围批准 | [] | 六入口归一登记（基准/锚点/意图/保护/来源）；影响计划范围批准与内容采纳分离记录 | `mvn test -Dtest=RevisionRequestTest` |
| NCS-014 | 文本变更影响分析 | [013,008] | 后文扫描分类 MUST_REPAIR/RECHECK/UNAFFECTED_WITH_REASON+理由+覆盖说明；确定性可测 | `mvn test -Dtest=ImpactAnalysisTest` |
| NCS-015 | 影响修复候选生成 | [014] | MUST_REPAIR 章生成隔离修复候选挂接修订请求，不动主稿 | `mvn test -Dtest=RevisionRepairTest` |
| NCS-016 | 依赖组整组接受与重基 | [015,001] | 整组原子（失败回滚无半套）；基准漂移 409+候选保留；commandId 幂等；保护锚点哈希核对 | `mvn test -Dtest=GroupAcceptanceTest` |
| NCS-017 | 修订工作区 UI | [016] | 四步（影响→建议→逐项审阅→采纳核对）+顶部原要求/基准/返回正文+「采纳 N 章修订」 | `npm --prefix frontend run build` |
| NCS-018 | SceneContract 结构与注入 | [] | 合同字段结构化存储+规划产出+写作上下文注入；outline 可编辑 | `mvn test -Dtest=SceneContractTest` |
| NCS-019 | 计划偏离对照与双向校正 | [018] | 每章偏离记录（实现/替代/未实现/新机会）；大纲页三选择并落决定 | `mvn test -Dtest=PlanDeviationTest` |
| NCS-020 | 卷末回读报告 | [018] | 卷末生成回读报告（人物/节奏/承诺/偏离/建议）持久化并进审阅；未决阻断下卷自动续写 | `mvn test -Dtest=VolumeReadbackTest` |
| NCS-021 | 按正文更新计划 | [020] | 以卷摘要+承诺状态+人物变化再生成未写部分；PlanChange 留痕；已写不动 | `mvn test -Dtest=ReplanFromTextTest` |
| NCS-022 | 审美档案 | [] | 认可片段(+理由)/减少表达/偏好维度存储版本化；注入写作上下文；创作约定页维护 | `mvn test -Dtest=AestheticProfileTest` |
| NCS-023 | 三类信息分层审阅 UI | [007] | 必须处理/尚未查清/文学建议分列；问题卡（问题/位置/为什么/两侧证据/检查版本/处理动作）；无证据不得标解决 | `npm --prefix frontend run build` |
| NCS-024 | 选择性组合采纳 | [016] | 多候选片段组合→新哈希→强制重检后采纳；不继承原通过状态 | `mvn test -Dtest=SelectiveAcceptTest` |
| NCS-025 | 委托策略分级 | [013] | 按变更类别 AUTO/CONFIRM；范围内自动接受留痕，范围外屏障；约定页展示暂停条件 | `mvn test -Dtest=DelegationPolicyTest` |
| NCS-026 | 运行经验库 | [013] | 修订接受/拒绝样本+原因按类存储；同类任务注入；可删除；不入故事事实 | `mvn test -Dtest=RevisionExperienceTest` |
| NCS-027 | BookEdition 成书 UI 闭环 | [] | 版本记录视图：创建/列表/详情（覆盖/批准/承诺/例外）/确认定稿（哈希绑定）/导出固定版本；工作稿 vs 定稿区分 | `npm --prefix frontend run build` |
| NCS-028 | 定稿校验绑定与门禁修正 | [027] | 校验报告绑定 manifestHash；旧问题不因窗口消失；确认定稿需该 manifest 报告；新稿不继承 | `mvn test -Dtest=EditionValidationTest` |
| NCS-029 | 任务与版本中心 UI | [005] | 任务页（目标/进度/等待原因/费用/继续/停止）+版本记录入口（章稿历史+整书记录） | `npm --prefix frontend run build` |
| NCS-030 | 联合验收浏览器 Journey | [001–029] | replay 模式真实 UI 走通 T01–T11；console/Network 检查；报告落 docs/reports | 手动浏览器验收+报告 |

## 风险与备选方案

- **fixture 分叉风险**（NCS-008/012/018/019/020/022）：严格按 AGENTS.md 重建→双 replay→对比→
  人工确认后 bless；若某 feature 可将 prompt 变化限制在新任务类型（e2e 管线外）则不改旧指纹。
- **SQLite 单写者**：整组接受采用同连接顺序写+补偿回滚（快照恢复），不引入长事务持锁跨 LLM 调用。
- **finalGate 语义变更**（NCS-028）：新绑定式校验只影响成书路径，写作循环门禁保持既有行为，
  E2E（3 章）不触达 FINAL，避免无谓基线扰动。
- **前端体量**：新视图尽量复用 AntD 既有模式与 client 封装；每 UI feature 独立构建验证。
- **回滚**：所有 schema 变更 ADD COLUMN/CREATE IF NOT EXISTS 可逆于代码层；新端点/视图增量交付。
