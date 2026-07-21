# Session Handoff

## Current Objective

- **Goal**: 实施 4-Phase 架构优化方案，解决深度审查发现的 30 个问题
- **Current status**: Harness 已创建，Phase 1 待启动
- **Branch / commit**: main / `3d41c9d` (最新)

## Completed This Session

- [x] 深度架构审查（5 维度 × 30 问题）
- [x] 4-Phase 优化方案设计（16 个 feature）
- [x] Harness 创建（AGENTS.md / feature_list.json / progress.md / init.sh / session-handoff.md）

## Verification Evidence

| Check | Command | Result | Notes |
|---|---|---|---|
| 编译 | `mvn compile` | ✅ BUILD SUCCESS | |
| 测试 | `mvn test` | ✅ 86 tests, 0 failures | 1 skipped (RealLlmSmokeTest) |
| 模板 | `find prompts -name *.st` | ✅ 9 个 | |
| 前端 | `static/index.html` | ✅ 已构建 | |

## 4-Phase 优化方案概要

### Phase 1：信息流打通（6 features，最高优先级）
解决"LLM 写到第 20 章完全失忆"+"Agent 间断层"
- p1-01: 故事原旨（premise）注入上下文 ← **下一步从这里开始**
- p1-02: CharacterDesigner 注入构思+世界观（解决人物总是"陆昭然"）
- p1-03: Plotter 注入世界观+人物
- p1-04: 卷级摘要全量注入（解决第 50 章看不到第 1 卷）
- p1-05: 章节 chunk 检索修复（向量检索目前对前文召回贡献为零）
- p1-06: styleGuide 从 StyleProfile 动态注入

### Phase 2：长篇规模（3 features）
解决"系统只能写 6-15 章短篇"
- p2-01: 大纲规模参数化（删除 2-3 卷硬限制）
- p2-02: 世界观按类型多样选样
- p2-03: worldbuilding.st 去法则化

### Phase 3：闭环接通（4 features）
解决"反馈机制空转"
- p3-01: ContinuityIssue.resolve 接通
- p3-02: StyleGuardian.checkConsistency 接入闭环
- p3-03: 伏笔系统接入（表/类齐全但无写入点）
- p3-04: 卷末触发全局质量评价

### Phase 4：UI + 鲁棒性（3 features）
- p4-01: Steps 状态修复（current 写死为 FINAL）
- p4-02: 按钮互斥
- p4-03: 静默 catch 改为日志

## Files Changed This Session

- `AGENTS.md` — 新建（harness 启动路径+工作规则）
- `feature_list.json` — 新建（16 个 feature 追踪）
- `progress.md` — 新建（会话连续性日志）
- `init.sh` — 新建（验证脚本）
- `session-handoff.md` — 新建（本文件）

## Decisions Made

- **4-Phase 分阶段**：先打通信息流（让 LLM 不失忆），再扩规模，再接闭环，最后修 UI
- **feature 颗粒度**：每个 feature 对应 1-3 个文件改动，可独立验证
- **依赖关系**：p1-03 依赖 p1-02（Plotter 注入需要先有人物），其余可并行

## Blockers / Risks

- GLM-5.2 内容审查（已有三级降级重试但极端情况仍可能失败）
- GLM-5.2 限额（5小时上限，DeepSeek 余额有限）
- 大纲解析正则脆弱（Phase 2 p2-01 中考虑改 JSON 输出）

## Next Session Startup

1. Read `AGENTS.md`.
2. Read `feature_list.json` — 找到第一个 `not-started` 的 feature（应为 p1-01）。
3. Review this handoff.
4. Run `./init.sh` before editing.
5. 从 p1-01 开始：修改 `WritingContext.java` 和 `ContextAssembler.java`。

## Recommended Next Step

实施 **p1-01 故事原旨注入**：
1. WritingContext 新增 `premiseSection` 字段
2. toSystemPrompt 最前面输出 `【故事原旨】`
3. ContextAssembler.assemble 从 Outline.premise + Project.concept 注入
4. applyTokenBudget 中 premise 设为最高优先级（永不被裁剪）
