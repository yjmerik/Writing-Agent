# Session Handoff

## Current Objective

- **Goal**: 百万字长篇架构优化（`docs/specs/2026-08-13-million-word-architecture.md`，Phase 10，三波 23 feature）。
- **Current status**: **22/23 feature 完成，214 tests 0 failures，3 轮 e2e 全部 PASS**。W1(7)+W2(7)+W3(8) 已实现并端到端验证。剩余 w3-01（总纲中段重规划，需 LLM 重规划，单独立 spec 推进）。
- **Branch / commit**: `phase-10-w1-w2-foundation`（3 个 commit，见 progress.md）。
- **Branch / commit**: main（未 commit，工作区有 W1+W2 改动）；最近提交见 `git log`。
- **上一阶段（mc spec）状态**: P0/P1/P2/P3 全部完成，详见下方历史。

## 架构 Review 结论速览（2026-08-13）

- 系统在 ~30 章/≈9.6 万字验证通过，向百万字（400 章）扩展有 5 道扩展墙（~40/120/200/200+/全程）。
- 4 个系统性根因：A 全量加载进单次 LLM 调用 / B 图表只增不减 / C 历史记忆级联压缩衰减 / D 修复闭环只向当前章只1次。
- 核心动作：引入 `story_facts`（可累积/可纠错/可检索的事实真相层）替代 book_summary 为主长程记忆，并作为修复闭环的纠错对象。

## Next Steps（下次会话入口）

1. **开始 W1 第一波（止血正确性）**——按 Bugfix gate，每个 feature 先写复现 failing test：
   - `p10-w1-01` stableSectionCache 写联动失效（**正确性 bug，最高优先**）
   - `p10-w1-02` 脏卷摘要重算路径（**静默历史丢失**）
   - `p10-w1-03` 向量去污 + top-K 联动预算
   - `p10-w1-04..07` 成本熔断/退避降级/章节状态机/后处理并行+重写互斥
2. W1 完成后跑 `./scripts/e2e.sh --mode record --chapters 30` 验证无中断 + 模拟 429 验证降级。
3. W1 全绿后进入 W2（故事真典 + 检索收窄），需先做 `p10-w2-01` story_facts 表。
4. 每个 feature 完成按 End-of-Session 程序更新 feature_list.json（status→completed + evidence）。

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
