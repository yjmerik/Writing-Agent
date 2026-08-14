# Session Handoff

## Current Objective

- **Goal**: 过程化质量门禁架构（`docs/specs/2026-08-14-quality-gate-architecture.md`，Phase 11，四波 21 feature）。在现有实现上增强创作质量的过程化控制与长程一致性——大纲生成/章节审查过程中实时质检、根据结果优化调整、过程中发现而非最后反复改。
- **Current status**: **Phase 11 实现中：14/21 feature 完成**（Q0 地基 6/6 + Q1 前置门禁 4/4 + Q2 质检委员会 QualityCouncil/Arbiter 2 + Q3 fail-loud/baseline 门禁 2），commit e3b4dd8..57ea186，全量 mvn test BUILD SUCCESS（需 ALIYUN_LLM_API_KEY）。**剩余 7**：q2-03 合并重写接入 / q2-04 重写预算 / q2-05 熔断配置 / q3-01 质量事件遥测 / q3-02 diagnose 质量分类 / q3-05 测试网 / q3-06 FinalAuditGate（吸收 p10-w3-09）。
- **Branch / commit**: main；本次会话改动为 `docs/specs/2026-08-14-quality-gate-architecture.md`（新）、`feature_list.json`（+21 feature）、`progress.md`、`session-handoff.md`，未 commit。
- **上一阶段（Phase 10 / mc spec）状态**: 见 `progress.md` 历史。

## Phase 11 四大支柱（详见 spec）

1. **过程化前置门禁（ConceiveGate + PlanGate）**：大纲/梗概生成后实时质检，软门禁（次数上限→标记 `quality_risk`→INTERACTIVE 人机暂停，不硬阻塞）。
2. **章节质检委员会 + 统一仲裁（QualityCouncil + Arbiter）**：四检测器各出报告→合并纠偏指令→一次重写喂入全部问题→重写后全量复检（解除现有互斥）。
3. **真典作为一致性检测的唯一真相层**：检查器收窄+读真典+人物单轨化+三分类仲裁（补齐 Phase 10 未落地）。
4. **可观测质量遥测 + 稳定性硬化 + 回归网**：7 类质量事件、diagnose 质量分类、fail-loud、重新启用 `minEvaluationScore` 门禁。

## 架构 Review 结论速览（2026-08-13）

- 系统在 ~30 章/≈9.6 万字验证通过，向百万字（400 章）扩展有 5 道扩展墙（~40/120/200/200+/全程）。
- 4 个系统性根因：A 全量加载进单次 LLM 调用 / B 图表只增不减 / C 历史记忆级联压缩衰减 / D 修复闭环只向当前章只1次。
- 核心动作：引入 `story_facts`（可累积/可纠错/可检索的事实真相层）替代 book_summary 为主长程记忆，并作为修复闭环的纠错对象。

## Next Steps（下次会话入口）

1. **开始 Phase 11 Q0 地基补齐**（无跨波依赖，先行）——按 Bugfix gate，每个 feature 先写复现 failing test：
   - `p11-q0-01` 检查器接入 RelevanceGateway 收窄（**修复 W2-05 未落地，最高优先**：5 检查器构造器注入门面、不再全量 `findAll*`）
   - `p11-q0-02` 检查器消费真典 ACTIVE 事实（修复 W2-07 未落地，依赖 q0-01）
   - `p11-q0-06` ArchiveCompactor 归档补齐 5 类（独立，可并行）
2. Q0 全绿后进入 Q1（ConceiveGate/PlanGate 前置门禁，依赖 q0-02 真典）与 Q2（QualityCouncil/Arbiter，依赖 q0-04 三分类）。
3. Q3（可观测遥测 + fail-loud + minEvaluationScore 门禁 + 测试网）横切，部分可先行（q3-01/q3-03/q3-04 无依赖）。
4. 每完成一个 feature：按 End-of-Session 程序更新 `feature_list.json`（status→completed + evidence）与 `progress.md`。
5. **重点约束**：Q0 全部为 `type=bug`，必须先写复现 failing test 再改实现（Bugfix gate）；涉及 prompt/上下文/Agent 逻辑的 feature（Q1/Q2）完成后须 `./scripts/e2e.sh` + `compare-baseline.py` PASS。

## 审查 5 类阻断问题 → 修复状态

| 阻断 | 修复 | 证据 |
|---|---|---|
| 单活跃作业并发双写 | `startWriting` 同步等待旧任务退出（30s 超时）；cancel 只设内存标志 | `SingleActiveJobTest`/`WritingTaskCancelTest` |
| P1 数据正确性缺失 | Chapter.version 往返；summaries dirty + sourceChapterVersion；重写失效摘要 | `VersionedMemoryTest` |
| P2 长程记忆未实现 | model-aware token budget（窗口 90%+输出预留）；连续性检查注入历史卷摘要+线索台账；embedding cloud-api 默认 | `TokenBudgetSafetyTest`/`ContinuityHistoryInputTest`/`EmbeddingProfileTest` |
| P3 门禁绕过 + 无 fixture | VOLUME_DONE→step() 统一走 runFinalAuditGate；E2E 轮询/从文件读报告 | `FinalAuditGateTest` |
| 凭据无 fail-fast | validateKeys 校验所有模型、仅远程要求 key、本地豁免；测试加载系统 key | `LlmPropertiesValidateTest` |

## Completed This Session

- **P0**：单活跃作业、cancel 状态机、诚实终态、孤儿清扫、凭据 fail-fast 语义修正、load-env.sh、JDK26 兼容
- **P1**：Chapter.version 往返、摘要 dirty + sourceChapterVersion（表列+迁移）
- **P2**：TokenBudget.budgetForModel、连续性检查历史卷摘要+线索台账、embedding 百炼 text-embedding-v3
- **P3**：FinalAuditGateTest、LlmCostTelemetryTest、diagnose 成本聚合、E2E 异步轮询 + 报告从文件读取
- **4 个可靠性 bug（Bugfix gate）**：
  1. 分层大纲截断空转 → 卷块 8 章 + maxTokens 12000 + 占位兜底
  2. record→replay 非确定 → 失败也录制、回放复现同类型异常
  3. 推理模型输出触顶（reasoning_content 耗 token）→ CHAPTER_WRITING maxTokens 20000 + 组装预算预留输出
  4. 卷大纲解析行内"预计第N章回收"误截断 → 行首锚定 + 兼容 markdown 加粗章节行

## Verification Evidence（快速验证 3 章）

| Check | Command | Result |
|---|---|---|
| 全量测试 | `source scripts/load-env.sh && mvn test` | 138 pass, 0 failures, 1 skipped |
| 完整 harness | `./init.sh` | 编译+测试+前端全通过 |
| 3 章 record | `./scripts/e2e.sh --mode record --chapters 3` | 3 章、11,081 字、46 调用、0 失败 |
| 3 章 replay | `./scripts/e2e.sh --mode replay --chapters 3` | 与记录完全一致 |
| 质量评估 | 对 replay DB 调 `/api/projects/{id}/evaluate` | overallScore **7/10**（人物7/世界观6/情节6/对话7/文风8/多样性7/一致性5） |
| 基线 | `compare-baseline.py --bless && compare` | PASS（minChapters=3, minTotalWords=8864, minEvaluationScore=7） |

## 质量评估发现的问题（一致性为主）

- HIGH：世界观设定"大明宝钞"（明代）vs 第3章"道光年间《千字文》"（清代）——朝代矛盾
- MEDIUM：第1章时间线衔接不清；前3章节奏偏慢、爽点不密集；明代/清代年号混用
- LOW：配角形象模糊

## Blockers

- 20 章真实记录约 2 小时（单章 2-5 分钟）；快速验证用 3 章即可
- 质量一致性（朝代年号）需后续统一历史语境或改虚拟年号

## Next Steps（下次会话入口）

1. 用 3-5 章快速验证闭环已就绪；需要长篇小说时再跑 20+ 章 record
2. 处理质量评估 HIGH 一致性矛盾（朝代年号统一）——按 Bugfix gate 写 failing test
3. 向会话目标推进：连续 30+ 章 ≈ 10 万字 + 独立质量评估达标
4. 恢复用户 8080 开发服务：`mvn spring-boot:run`（会话中清理 e2e 残留时停掉）

## Files Changed（本会话）

- `src/main/java/.../llm/config/{LlmProperties,ModelConfig,LlmConfig}.java`
- `src/main/java/.../llm/gateway/{RecordReplayLlmGateway,ObservedLlmGateway}.java`
- `src/main/java/.../memory/{TokenBudget,ContextAssembler}.java`、`domain/Summary.java`
- `src/main/java/.../agent/{ChapterWriterAgent,ContinuityCheckerAgent,AgentConfig,PlotterAgent}.java`
- `src/main/java/.../orchestrator/NovelOrchestrator.java`、`service/WritingTaskExecutor.java`
- `src/main/java/.../persistence/sqlite/{SqliteNovelRepository,SqliteSchemaInitializer}.java`
- `src/main/resources/application.yml`、`pom.xml`、`init.sh`、`scripts/{load-env,e2e,diagnose}.sh`、`baselines/golden.json`
- 测试：LlmPropertiesValidateTest / TokenBudgetSafetyTest / VersionedMemoryTest / ContinuityHistoryInputTest / EmbeddingProfileTest / FinalAuditGateTest / LlmCostTelemetryTest / LayeredOutlineParsingTest / WritingTaskRecoveryTest / HonestTerminalStateTest / SingleActiveJobTest / ChapterWriterAgentTest / RecordReplayLlmGatewayTest
