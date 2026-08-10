# Session Handoff

## Current Objective

- **Goal**: 百万字可靠性优化（`docs/specs/2026-08-09-million-character-reliability.md`，18 个 feature：mc-p0..mc-p3），依据审查报告修复 5 类阻断问题，并做端到端验证。
- **Current status**: **P0/P1/P2/P3 全部完成**。快速验证（3 章 record→replay→质量评估）闭环通过；基线已 bless（minEvaluationScore=7）。
- **Branch / commit**: main；最近提交见 `git log`。

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
