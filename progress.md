# Session Progress Log

## Current State

**Last Updated:** 2026-08-09
**Phase:** 百万字可靠性优化（docs/specs/2026-08-09-million-character-reliability.md）
**Tests:** 116 pass, 0 failures, 1 skipped (`RealLlmSmokeTest`，需 `REAL_LLM=true`)

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
