# AGENTS.md — 长篇小说自动写作系统

长篇小说 Agent 系统的 harness，用于可靠地推进架构优化和功能迭代。
技术栈：Spring Boot 3 (Java 17) + React 18 + SQLite + 嵌入式向量库。

## 项目概述

多 Agent 图架构的长篇小说自动写作系统：
- **7 个 Agent**：WorldBuilder / CharacterDesigner / Plotter / ChapterWriter / ContinuityChecker / StyleGuardian / MemoryManager
- **三层记忆**：结构化知识库 + 滚动摘要 + 向量检索
- **状态机编排**：CONCEIVING → PLANNING → WRITING → VOLUME_DONE → FINAL
- **PromptTemplate 系统**：9 个外置 `.st` 模板，支持变量插值和条件块
- **多模型路由**：按任务类型路由到不同 LLM（GLM-5.2 / DeepSeek / 本地模型）

设计文档：`docs/superpowers/specs/2026-07-10-novel-writing-system-design.md`

## Startup Workflow

Before writing code:

1. **Confirm working directory** with `pwd` — should be `/Users/erik/ZCodeProject/WritingAgent`
2. **Read this file** completely
3. **Read `feature_list.json`** — 查看当前优化进度（4 Phase × 16 features）
4. **Run `./init.sh`** — 编译 + 测试验证环境健康
5. **Review recent commits** with `git log --oneline -5`

If `mvn test` fails, repair baseline first before adding new scope.

## Working Rules

- **One feature at a time**: 从 `feature_list.json` 选一个 `not-started` 的 feature
- **TDD encouraged**: 改 Agent/prompt 时先写或更新测试，再改实现
- **Verification required**: 声明完成前必须跑 `mvn compile && mvn test`
- **Update artifacts**: 每完成一个 feature，更新 `feature_list.json`（status→completed, evidence）和 `progress.md`
- **Stay in scope**: 不修改与当前 feature 无关的文件
- **Prompt changes**: 改 `.st` 模板后必须验证对应 Agent 的测试仍通过（模板变量名不能改）
- **Leave clean state**: 下次会话必须能 `./init.sh` 立即启动

## Key Architecture Constraints

修改时必须遵守的架构约束：

1. **Agent 间不直接调用** — 全部经 NovelOrchestrator 调度
2. **Prompt 外置** — Agent 的 systemPrompt 必须用 `promptService.render(templateName, vars)`，不硬编码
3. **SPI 分层** — 业务层只依赖 `NovelRepository` / `VectorStore` / `EmbeddingProvider` 接口
4. **幂等性** — `runConceiving` 的各步骤已有幂等检查（查 DB 是否已有则跳过）
5. **写作循环容错** — 单章失败不跳章（退避重试），空内容不入库（≥500字门槛）

## Definition of Done

A feature is done only when ALL of the following are true:

- [ ] Target behavior is implemented
- [ ] `mvn compile` passes without errors
- [ ] `mvn test` passes (86+ tests, 0 failures)
- [ ] Evidence recorded in `feature_list.json`
- [ ] If prompt changed: corresponding Agent test still passes

## Verification Commands

```bash
# Full verification
./init.sh

# Quick compile check
mvn compile -q

# Run tests only
mvn test

# Start server for manual testing
mvn spring-boot:run

# Build frontend (if UI changes)
cd frontend && npm run build
```

## Project Structure

```
src/main/java/com/writing/novel/
├── agent/           # 7 Agent + AgentHarness + ResponseParser
├── config/          # PersistenceConfig, LlmConfig 等
├── domain/          # 14 个领域模型 (Project, Chapter, Character, ...)
├── llm/             # LlmGateway, OpenAiCompatibleClient, PromptTemplateService
├── memory/          # ContextAssembler, WritingContext, 三层记忆服务
├── orchestrator/    # NovelOrchestrator (状态机+Agent调度)
├── persistence/     # SQLite + 嵌入式向量库 + Embedding
├── spi/             # NovelRepository, VectorStore, EmbeddingProvider
└── web/             # REST Controller + GlobalExceptionHandler
src/main/resources/
├── prompts/         # 9 个 .st 模板文件
└── application.yml  # 模型配置 + 持久化配置
frontend/src/        # React + Ant Design 前端
```

## Escalation

- **Architecture decisions**: 参考 spec 文档或 `session-handoff.md`
- **Prompt tuning**: 改 `.st` 文件后如果测试失败，检查模板变量名是否与 Agent 代码的 `Map.of(...)` 一致
- **LLM API errors**: GLM-5.2 有 5 小时限额，DeepSeek 有余额限制。`application.yml` 中切换模型配置
- **SQLite locked**: 单进程使用，避免多个 `write-all` 并发调用同一项目
