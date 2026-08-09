# Session Handoff

## Current Objective

- **Goal**: 百万字可靠性优化（`docs/specs/2026-08-09-million-character-reliability.md`，18 个 feature：mc-p0 任务正确性/安全 → mc-p1 数据正确性 → mc-p2 长程记忆 → mc-p3 质量证据），依据审查报告修复 5 类阻断问题，并做端到端验证。
- **Current status**: P0/P1/P2 全部完成并提交；P3 单元测试完成；mc-p3-01（20 章 replay fixture + 基线）后台真实记录中。
- **Branch / commit**: main；最近提交 `13e8085 fix(mc-p3-01): 分层大纲截断空转修复`、`90378e6 feat(mc-p3)`。

## 审查 5 类阻断问题 → 修复状态

| 阻断 | 修复 | 证据 |
|---|---|---|
| 单活跃作业并发双写 | `startWriting` 同步等待旧任务退出（30s 超时）；cancel 只设内存标志，DB CANCELLED 循环退出后写 | `SingleActiveJobTest`/`WritingTaskCancelTest` |
| P1 数据正确性缺失 | Chapter.version 往返（mapChapter 覆盖）；summaries dirty + sourceChapterVersion；重写失效摘要 | `VersionedMemoryTest` |
| P2 长程记忆未实现 | model-aware token budget（窗口 90%）；连续性检查注入历史卷摘要+叙事线索台账；embedding cloud-api 默认 | `TokenBudgetSafetyTest`/`ContinuityHistoryInputTest`/`EmbeddingProfileTest` |
| P3 门禁绕过 + 无 fixture | VOLUME_DONE→step() 统一走 runFinalAuditGate；e2e.sh 异步轮询 + 报告从文件读取；20 章 fixture 记录中 | `FinalAuditGateTest` |
| 凭据无 fail-fast | validateKeys 校验所有模型、仅远程端点要求 key、本地豁免；测试加载系统 key（load-env.sh） | `LlmPropertiesValidateTest` |

## Completed This Session

- **凭据 fail-fast 语义修正**（mc-p0-04）：validateKeys 校验 default-model + 全部任务模型；远程端点必须带 key，localhost/127.x/*.local 豁免——修复仅配 DeepSeek 的真实部署被本地空 key 阻断的问题
- **测试环境加载系统 key**：`scripts/load-env.sh` 从 ~/.zshrc 提取 export 注入环境（不回显、不 source 整文件、始终成功返回）；init.sh 接入
- **JDK 26 兼容**：byte-buddy 1.17.8 + mockito 5.17.0（inline mock 重定形）
- **P0 行为测试**：WritingTaskRecoveryTest / HonestTerminalStateTest / SingleActiveJobTest / WritingTaskCancelTest / LlmPropertiesValidateTest
- **P2 长程记忆**：TokenBudget.budgetForModel（contextWindow 90%，deepseek 64K/本地 32K 已配）；Summary.sourceChapterVersion+dirty（表列+迁移，重写失效→细粒度重算/卷级过滤）；ContinuityCheckerAgent 历史卷摘要+线索台账；EmbeddingProfileTest 双档
- **P3 质量证据**：FinalAuditGateTest（门禁→NEEDS_REVISION）；LlmCostTelemetryTest（estimatedCostCny）；diagnose.sh 成本聚合
- **E2E 基建修复**：write-all 异步轮询至终态；报告 JSON 从文件读取（修复章节正文控制字符）；轮询上限按章节数缩放
- **embedding 切换百炼**：`DASHSCOPE_EMBEDDING_API_KEY`（用户提供，已入 ~/.zshrc）→ text-embedding-v3 OpenAI 兼容端点，实测 1024 维
- **分层大纲截断空转修复**（Bugfix gate）：planVolume 20 章+总纲注入致 completion 触顶 8192 截断 → 解析 0 章 → 写作循环空转；修复=卷块 8 章 + maxTokens 12000 + createPlaceholderVolume 占位兜底；`LayeredOutlineParsingTest` 4 用例

## Verification Evidence

| Check | Command | Result |
|---|---|---|
| 全量测试 | `source scripts/load-env.sh && mvn test` | 134 pass, 0 failures, 1 skipped |
| 完整 harness | `./init.sh` | 编译+测试+前端全通过 |
| 3 章 E2E record→replay | `./scripts/e2e.sh --mode record/replay --chapters 3` | 46 次回放调用、8644 字、0 错误 |
| 基线 | `python3 scripts/compare-baseline.py --bless && compare` | PASS（minChapters=3 已入库） |
| diagnose 成本聚合 | `./scripts/diagnose.sh /tmp/cost-events.jsonl` | 按 taskType 成本合计 + 总成本 |
| 20 章记录 | `./scripts/e2e.sh --mode record --chapters 20`（后台） | 进行中（修复前 0 章空转 → 修复后 8 synopses/卷正常推进） |

## Blockers

- 20 章真实记录耗时 ~60-100 分钟（单章 2-5 分钟：CHAPTER_WRITING + 检查链多次 LLM 调用）
- 单章偶发"空内容过短"失败（1/4 章），循环容错已处理，无需阻断

## Next Steps（下次会话入口）

1. 等 20 章 record 完成 → `./scripts/e2e.sh --mode replay --chapters 20` 零成本验证 → 重新 `--bless` 20 章基线 → compare PASS
2. `./scripts/e2e.sh --mode replay --chapters 20 --evaluate` 跑 QualityEvaluationAgent 全维度评分
3. 向会话目标推进：连续 30+ 章 ≈ 10 万字输出 + 独立质量评估达标
4. 若 20 章 replay 遇 fixture 缺失（LLM 输出偶变），按 Bugfix gate 回流修复
5. 恢复用户 8080 开发服务：`mvn spring-boot:run`（本会话清理 e2e 残留时停掉了）

## Files Changed（本会话）

- `src/main/java/.../llm/config/{LlmProperties,ModelConfig,LlmConfig}.java`
- `src/main/java/.../memory/{TokenBudget,ContextAssembler}.java`、`domain/Summary.java`
- `src/main/java/.../agent/{ChapterWriterAgent,ContinuityCheckerAgent,AgentConfig,PlotterAgent}.java`
- `src/main/java/.../orchestrator/NovelOrchestrator.java`、`service/WritingTaskExecutor.java`
- `src/main/java/.../persistence/sqlite/{SqliteNovelRepository,SqliteSchemaInitializer}.java`
- `src/main/resources/application.yml`、`pom.xml`、`init.sh`
- `scripts/{load-env,e2e,diagnose}.sh`、`baselines/golden.json`
- 测试：LlmPropertiesValidateTest / TokenBudgetSafetyTest / VersionedMemoryTest / ContinuityHistoryInputTest / EmbeddingProfileTest / FinalAuditGateTest / LlmCostTelemetryTest / LayeredOutlineParsingTest / WritingTaskRecoveryTest / HonestTerminalStateTest / SingleActiveJobTest / ChapterWriterAgentTest
