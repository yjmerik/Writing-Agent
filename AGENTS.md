# AGENTS.md — 长篇小说自动写作系统

长篇小说 Agent 系统的 harness，用于可靠地推进架构优化和功能迭代。
技术栈：Spring Boot 3 (Java 17) + React 18 + SQLite + 嵌入式向量库。

## 项目概述

多 Agent 图架构的长篇小说自动写作系统：
- **23 个 Agent/辅助职责类**：7 个核心 Agent（WorldBuilder / CharacterDesigner / Plotter / ChapterWriter / ContinuityChecker / StyleGuardian / MemoryManager）+ 16 个辅助职责（原 12 个基础辅助 Agent，另含 FactHarvester、PassageEditor、PlotMotifTracker、RelationshipEvolutionTracker）
- **多层长程记忆**：故事原旨 + StoryCanon 真典 + 全书快照/分层摘要 + 近期正文 + 向量检索 + 实体/线索/伏笔台账（ContextAssembler 按模型窗口预算汇成 WritingContext）
- **状态机编排**：CONCEIVING → PLANNING → WRITING ↔ VOLUME_DONE → FINAL；另有 PAUSED_AT_CHECKPOINT 与 NEEDS_REVISION
- **PromptTemplate 系统**：27 个外置 `.st` 模板 + 7 个题材预设（含 wuxia），支持变量插值和条件块
- **多模型路由**：按 `LlmTaskType`（8 种）路由；当前 8 类任务均使用 DeepSeek v4-flash，DashScope text-embedding-v3 向量化，Scenario/RecordReplay 网关提供零成本确定性验证
- **多样性与质量控制**：ConceiveGate / PlanGate / QualityCouncil / Arbiter + Continuity/Consistency/Reader/Style 检查，重写预算按项目配置（默认 1 次）

设计文档：`docs/superpowers/specs/2026-07-10-novel-writing-system-design.md`

## Startup Workflow

Before writing code:

1. **Confirm working directory** with `pwd` — should be `/Users/erik/ZCodeProject/WritingAgent`
2. **Read this file** completely
3. **Read `feature_list.json`** — 查看当前优化进度（当前 Phase 25，共 179 features；以文件内 `current_phase` 和状态汇总为准）
4. **Run `./init.sh`** — 编译 + 测试验证环境健康
5. **Review recent commits** with `git log --oneline -5`

If `mvn test` fails, repair baseline first before adding new scope.

## Working Rules

- **One feature at a time**: 从 `feature_list.json` 选一个 `not-started` 的 feature
- **TDD encouraged**: 改 Agent/prompt 时先写或更新测试，再改实现
- **Bugfix gate (硬规则)**: 修 bug 必须先写复现该 bug 的 failing test，再改实现——不允许直接修
- **Verification required**: 声明完成前必须跑 `mvn compile && mvn test`
- **Update artifacts**: 每完成一个 feature，更新 `feature_list.json`（status→completed, evidence）和 `progress.md`
- **Stay in scope**: 不修改与当前 feature 无关的文件
- **Prompt changes**: 改 `.st` 模板后必须验证对应 Agent 的测试仍通过（模板变量名不能改）；影响产出质量的 prompt 改动需重跑 `./scripts/e2e.sh` 对比基线
- **Leave clean state**: 下次会话必须能 `./init.sh` 立即启动

## 需求拆分协议（新需求入口）

新需求进入系统的唯一路径：

1. **写 spec**: 复制 `docs/specs/TEMPLATE.md` 为 `docs/specs/YYYY-MM-DD-<slug>.md`，填写目标/非目标/验收标准/影响面
2. **拆 feature**: 每个 feature 满足——1-3 个文件改动、可独立验证、标注依赖
3. **登记 backlog**: 追加到 `feature_list.json`，新 feature 必须含以下字段：
   - `acceptance`: 机器可验证的完成标准（不是"实现了 X"，而是"跑 Y 命令得到 Z 结果"）
   - `verify_cmd`: 该 feature 专属验证命令（如 `mvn test -Dtest=XxxTest`）
4. **backlog 耗尽时**: 跑 `./scripts/diagnose.sh` + `python3 scripts/compare-baseline.py`，从失败分类/质量退化中产生新 feature；若均无问题，向用户请求新需求，不要自造需求

## 失败回流协议（识别问题 → 修复闭环）

- E2E 或运行期失败 → `./scripts/diagnose.sh` 产出失败分类报告
- 每个需要修复的失败分类 → 按需求拆分协议登记为 feature（`"type": "bug"`，evidence 引用诊断输出）
- 修复遵循 Bugfix gate：先 failing test 复现，后修复，最后重跑 e2e 确认失败分类消失

## Key Architecture Constraints

修改时必须遵守的架构约束：

1. **Agent 间不直接调用** — 全部经 NovelOrchestrator 调度
2. **Prompt 外置** — Agent 的 systemPrompt 必须用 `promptService.render(templateName, vars)`，不硬编码
3. **SPI 分层** — 业务层只依赖 `NovelRepository` / `StoryCanon` / `VectorStore` / `EmbeddingProvider` 接口
4. **幂等性** — `runConceiving` 的各步骤已有幂等检查（查 DB 是否已有则跳过）
5. **写作循环容错** — 单章失败不跳章（退避重试），空内容不入库（≥500字门槛）

## Definition of Done

A feature is done only when ALL of the following are true:

- [ ] Target behavior is implemented
- [ ] `mvn compile` passes without errors
- [ ] `mvn test` passes (当前 480+ tests, 0 failures)
- [ ] Feature 自带的 `verify_cmd` 通过（若有）
- [ ] Evidence recorded in `feature_list.json`
- [ ] If prompt changed: corresponding Agent test still passes
- [ ] If 影响产出质量（prompt/上下文组装/Agent 逻辑）: `./scripts/e2e.sh` + `compare-baseline.py` PASS

## End of Session Procedure（会话结束必做）

1. `mvn test` 确认全绿（不绿则修复或在 handoff 中标注 BROKEN + 原因）
2. 更新 `feature_list.json`（完成的 feature → completed + evidence）
3. 更新 `progress.md`（本次会话完成项、当前状态、Next Steps）
4. 更新 `session-handoff.md`（目标、已完成、验证证据、blockers、下一步入口）
5. 状态文件的日期必须是当天——过期的状态文件比没有更有害

## Verification Commands

```bash
# Full verification
./init.sh

# Quick compile check
mvn compile -q

# Run tests only
mvn test

# E2E（确定性回放，零 LLM 成本；首次需先 record 建 fixture）
./scripts/e2e.sh                     # 默认 replay 模式写 3 章 → e2e-report.json
./scripts/e2e.sh --mode record       # 真实调用并录制 fixture（建/更新基线时）

# 质量基线对比（prompt/上下文/Agent 逻辑改动后必跑）
python3 scripts/compare-baseline.py            # PASS/FAIL
python3 scripts/compare-baseline.py --bless    # 人工确认质量后更新基线

# 诊断报告（失败分类 + LLM 统计，agent 可读）
./scripts/diagnose.sh                          # 默认读 data/logs/events.jsonl
./scripts/diagnose.sh data/e2e/events.jsonl    # 读 E2E 产生的事件

# Start server for manual testing
mvn spring-boot:run

# Build frontend (if UI changes)
cd frontend && npm run build
```

## 可观测性设施

- **结构化事件日志**: `data/logs/events.jsonl`（JSON Lines）。覆盖 LLM、章节、质量门禁、重写、熔断与 FINAL 审计等事件；由 `telemetry/EventLog` 写入，`ObservedLlmGateway` 与 NovelOrchestrator 埋点
- **应用日志落盘**: `data/logs/app.log`（logback 滚动，14 天）
- **LLM 录制/回放**: `llm.replay.mode` = `"off"`/`"record"`/`"replay"`（application.yml，off 必须带引号），fixture 存 `data/fixtures/llm/`，按任务类型与归一化 prompt 的 SHA-256 指纹命名（配置 JSON 的 `updatedAt` 不参与指纹）。装饰链：Observed → RecordReplay → Default

## Project Structure

```
src/main/java/com/writing/novel/
├── agent/           # 23 个 Agent/辅助职责类 + AgentConfig/AgentHarness + util/ResponseParser
├── config/          # PersistenceConfig, PersistenceProperties
├── domain/          # 35 个领域模型/枚举 (Project, Chapter, Character, StoryFact, WritingTask, ...)
├── llm/             # LlmGateway/DefaultLlmGateway, OpenAiCompatibleClient, PromptTemplateService, LlmTaskType
├── memory/          # ContextAssembler, WritingContext, RelevanceGateway, TokenBudget, 分层记忆服务
├── orchestrator/    # NovelOrchestrator + ChapterProduction/PostProcessor + OrchestratorPolicies
├── persistence/     # SqliteNovelRepository + EmbeddedVectorStore + EmbeddingProvider 实现
├── service/         # 25 个业务模块 (Arbiter, ProjectWorkCoordinator, ArchiveCompactor, PresetRegistry, ...)
├── spi/             # NovelRepository, StoryCanon, VectorStore, EmbeddingProvider, VectorSearchResult
├── telemetry/       # EventLog, EventContext, CostBudgetMonitor
├── util/            # NameSanitizer
└── web/             # 11 个 REST/SSE/SPA 入口与支持类
src/main/resources/
├── prompts/         # 27 个 .st 模板文件
├── presets/         # 7 个题材预设 (cultivation/general/historical/mystery/romance/thriller/wuxia)
├── logback-spring.xml  # 日志落盘 data/logs/app.log
└── application.yml  # 模型配置 (按 LlmTaskType 路由) + 持久化 + llm.replay 配置
scripts/             # e2e.sh (端到端), diagnose.sh (诊断), compare-baseline.py (质量基线)
baselines/           # golden.json 质量基线（--bless 生成）
docs/specs/          # 需求 spec（TEMPLATE.md 起步）
frontend/src/        # React + Ant Design 前端 (2 页面 + 6 组件 + api/client.ts)
```

## Escalation

- **Architecture decisions**: 参考 spec 文档或 `session-handoff.md`
- **Prompt tuning**: 改 `.st` 文件后如果测试失败，检查模板变量名是否与 Agent 代码的 `Map.of(...)` 一致
- **LLM API errors**: 当前写作任务走 DeepSeek v4-flash，embedding 走 DashScope；遇余额/限流先查 `data/logs/events.jsonl`，再在 `application.yml` 切换模型配置
- **SQLite locked**: 单进程使用，避免多个 `write-all` 并发调用同一项目
