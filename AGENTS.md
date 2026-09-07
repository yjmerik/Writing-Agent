# AGENTS.md — 长篇小说自动写作系统

长篇小说 Agent 系统的 harness，用于可靠地推进架构优化和功能迭代。
技术栈：Spring Boot 3 (Java 17) + React 18 + SQLite + 嵌入式向量库。

## 项目概述

多 Agent 图架构的长篇小说自动写作系统：
- **24 个 Agent/辅助职责类**：7 个核心 Agent（WorldBuilder / CharacterDesigner / Plotter / ChapterWriter / ContinuityChecker / StyleGuardian / MemoryManager）+ 17 个辅助职责（原 12 个基础辅助 Agent，另含 FactHarvester、PassageEditor、PlotMotifTracker、RelationshipEvolutionTracker、ContentRevisionAgent）
- **多层长程记忆**：故事原旨 + StoryCanon 真典 + 全书快照/分层摘要 + 近期正文 + 向量检索 + 实体/线索/伏笔台账（ContextAssembler 按模型窗口预算汇成 WritingContext）
- **状态机编排**：CONCEIVING → PLANNING → WRITING ↔ VOLUME_DONE → FINAL；另有 PAUSED_AT_CHECKPOINT 与 NEEDS_REVISION
- **PromptTemplate 系统**：28 个外置 `.st` 模板 + 7 个题材预设（含 wuxia），支持变量插值和条件块
- **多模型路由**：按 `LlmTaskType`（8 种）路由；具体 provider/模型以当前检出的 `application.yml` 和环境配置为准，embedding 独立配置，Scenario/RecordReplay 网关提供零成本确定性验证
- **多样性与质量控制**：ConceiveGate / PlanGate / QualityCouncil / Arbiter + Continuity/Consistency/Reader/Style 检查，重写预算按项目配置（默认 1 次）

设计文档：`docs/superpowers/specs/2026-07-10-novel-writing-system-design.md`

## Harness 状态与完成记录

当前事实入口：`feature_list.json` → `progress.md` → `session-handoff.md`；完成索引为 `docs/harness/completions.json`，字段与维护说明见 `docs/harness/README.md`。历史归档仅供追溯，不作为当前操作指令。

- `active_feature` 与唯一 `in-progress` 项保持一致；`deferred` 表示暂停的既有工作，恢复时只选一项、核对依赖并新开分支。
- 新特性完成必须引用 `verified` 完成记录及已提交的验证摘要；Phase0–26的 `historical` 是有明确证据限制的迁移记录，不用于新特性。
- `./init.sh` 先执行离线状态核验；合并/交接另跑 `python3 scripts/check-harness.py --check-git` 核对提交归属。
- 当前进度与交接各不超过150行，含 Current / Files / Verification / Blockers / Next Steps；旧内容移到 `docs/harness/archive/`，当天更新三个状态入口的日期。

## Startup Workflow

Before writing code:

1. **Confirm working directory** with `pwd` 与 `git rev-parse --show-toplevel` — 主工作目录为 `/Users/erik/ZCodeProject/WritingAgent`；使用 worktree 时确认它属于同一仓库
2. **Read this file** completely
3. **Inspect Git state** — 检查 `git status --short`、`git branch --show-current` 和 `git log --oneline -5`，识别已有未提交工作
4. **Select one feature** — 读取 `feature_list.json` 的实际阶段和状态，只选一个依赖已合入 `main` 的 `not-started` feature；中断续做则核对原 feature 与分支
5. **Prepare feature branch** — 按下方「特性分支与合并协议」从 `main` 新建独立分支，再开始实现
6. **Run `./init.sh`** — 在本次特性的工作目录中验证基线健康

If `mvn test` fails, repair baseline first before adding new scope.

## Working Rules

- **One feature at a time**: 同一轮开发只推进一个 feature；完成合并和合并后验证，再开始下一个
- **One branch per feature**: 每个 feature 从 `main` 新建 `codex/<feature-id>-<slug>` 分支，执行下方完整合并协议
- **TDD encouraged**: 改 Agent/prompt 时先写或更新测试，再改实现
- **Bugfix gate (硬规则)**: 修 bug 必须先写复现该 bug 的 failing test，再改实现——不允许直接修
- **Verification required**: 声明完成前必须跑 `mvn compile && mvn test`
- **Update artifacts**: 实现期间保持 `in-progress`；合入 `main` 且合并后验证通过，才更新为 `completed` 并记录证据
- **Stay in scope**: 不修改与当前 feature 无关的文件
- **Prompt changes**: 改 `.st` 模板后必须验证对应 Agent 的测试仍通过（模板变量名不能改）；影响产出质量的 prompt 改动需重跑 `./scripts/e2e.sh` 对比基线
- **Leave clean state**: 下次会话必须能 `./init.sh` 立即启动

## 特性分支与合并协议（硬规则）

一个 feature 的闭环是：**选定 → 新建分支 → 实现与验证 → 提交 → 合并 `main` → 合并后验证 → 登记完成**。一个需求拆成多个 feature 时，也必须逐个走完闭环，不能在一个长期分支上连续堆积多个特性。

1. **确定范围**：记录唯一 feature ID、验收命令与依赖。每个特性分支只承载该 feature 的实现、测试及必要文档；发现独立问题先登记 backlog，不顺带实现。修复当前 feature 验收失败所需的改动仍留在本分支。
2. **从主分支建分支**：以本地 `main` 最新提交为起点，创建 `codex/<feature-id>-<slug>`。同一 feature 跨会话续做时复用其未合并分支；已完成其他 feature 的分支不复用。纯文档维护使用 `codex/docs-<slug>`，同样独立提交并合并。
3. **隔离已有工作**：已有其他特性的未提交修改时，保留其内容，在独立 worktree 中开发。不得把它们带入本轮提交，不自动清空、覆盖或批量暂存已有工作。禁止直接在 `main` 实现业务特性。
4. **验证并提交**：按 Definition of Done 运行适用检查，更新本特性的进度和证据，状态仍为 `in-progress`。明确列出要暂存的文件或补丁；提交前检查 `git diff --cached`，提交信息包含 feature ID。纯文档提交使用明确的文档主题。
5. **检查合并范围**：检查 `git log --oneline main..HEAD` 和 `git diff --stat main...HEAD`，确认待合入提交只属于本 feature。如果 `main` 已前进，先把 `main` 合入特性分支，解决冲突并重新验证最终结果，再合并回主分支。已有其他特性的提交不能借本次合并夹带进入 `main`。
6. **主动完成本地合并**：检查通过后执行合并，优先 `git merge --ff-only <feature-branch>`。用户授权实现一个 feature，即包含按本协议提交并合并到本地 `main` 的授权，无需每个 feature 再次询问；用户明确要求仅实现、保留分支或走 PR 时，遵从其要求并保留待合并状态。推送远端、发布和部署须另有授权。
7. **验证主分支**：在合并后的干净 `main` 检出中重新运行 `mvn compile && mvn test`，以及本 feature 的 `verify_cmd` 和受影响检查（前端构建、对应 Agent 测试、E2E/基线对比等）。原工作区的未提交修改不能被当作验证主分支的依赖。合并后失败则暂停下一特性，回到原特性分支修复、验证、再合并；期间如需交接，明确标注 `BROKEN`。
8. **登记完成再继续**：全部检查通过后，更新 `feature_list.json` 为 `completed`，在 evidence/交接记录中写明 feature 分支、实现提交、主分支包含该实现的提交、验证命令及结果。此时允许在 `main` 仅补提交这些完成状态和验证记录；业务修复仍走特性分支。确认 `git merge-base --is-ancestor <feature-commit> main` 成功，才开始下一个 feature。

纯文档维护检查差异、引用与规则一致性，不新增业务测试；现有编译和测试门禁仍执行。合并完成后保留不属于本轮的修改与分支，只清理本轮创建且已确认无待保留内容的临时 worktree。

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
- [ ] `mvn test` passes（以本次实际运行计数为准，0 failures / 0 errors）
- [ ] Feature 自带的 `verify_cmd` 通过（若有）
- [ ] Evidence recorded in `feature_list.json`
- [ ] If prompt changed: corresponding Agent test still passes
- [ ] If 影响产出质量（prompt/上下文组装/Agent 逻辑）: `./scripts/e2e.sh` + `compare-baseline.py` PASS
- [ ] 本 feature 的提交已合入 `main`，且干净主分支检出的合并后验证通过
- [ ] 已记录分支、实现/主分支提交和验证结果，再将 feature 标记为 `completed`

## End of Session Procedure（会话结束必做）

1. 完成「特性分支与合并协议」中的适用验证；不绿则修复或在 handoff 中标注 `BROKEN` + 原因
2. 更新 `feature_list.json`：仅已合并且验证通过的 feature → `completed` + evidence；中途交接保持 `in-progress`
3. 更新 `progress.md`（本次会话完成项、当前状态、Next Steps）
4. 更新 `session-handoff.md`（feature ID、分支、提交、是否已合入 `main`、验证证据、blockers、下一步入口）
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
├── agent/           # 24 个 Agent/辅助职责类 + AgentConfig/AgentHarness + util/ResponseParser
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
├── prompts/         # 28 个 .st 模板文件
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
- **LLM API errors**: 写作与 embedding 配置相互独立；遇余额/限流先查 `data/logs/events.jsonl`，再在 `application.yml` 切换模型配置
- **SQLite locked**: 单进程使用，避免多个 `write-all` 并发调用同一项目
