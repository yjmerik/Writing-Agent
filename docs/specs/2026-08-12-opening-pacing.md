# Spec: 开篇节奏加速（opening-pacing）

> 触发事件：质量 Agent 两轮复评均标记 MEDIUM plot"前3章节奏偏缓、爽点不密集"。

## 背景与动机

- 根因：`ChapterWriterAgent.buildChapterLengthGuide` 用 `chapterIndex % 3 != 0` 判定长短章。第 3 章（3%3==0）被标为"喘息/过渡章，2500-3500字，不必严格四段式，不需要爽点爆发"。但小说开篇前几章每章都应高压抓人，不该出现喘息章。
- 同时 `ContextAssembler.buildWritingConstraints` 没有针对开篇章节的"开篇钩子/快速冲突"专项约束。
- 后果：开篇节奏沉闷，爽点稀疏，读者可能在铺垫阶段流失。

## 目标

1. 前 N 章（默认 5）强制高压节奏：禁止喘息章判定，要求每章至少 2 爽点 + 强开篇钩子 + 章内冲突升级。
2. 通用：N 从 WritingPreferences 读取（可配），默认 5；N 章后恢复正常长短交替。

## 非目标

- 不改爽点轮换机制（ClimaxDiversityTracker 已处理）。
- 不解决中后期节奏（单独议题）。

## 验收标准

- [ ] `mvn test -Dtest=OpeningPacingTest` 通过：前 5 章的 buildChapterLengthGuide 不返回喘息章；buildWritingConstraints 含开篇加速约束。
- [ ] `mvn test` 全绿。
- [ ] 3 章 e2e record + 质量评估：plot 维度评分提升或节奏问题降级。

## 影响面

- **涉及模块**: agent（ChapterWriterAgent）、memory（ContextAssembler）
- **涉及 prompt 模板**: 无
- **数据库 schema 变更**: 无
- **质量基线影响**: 需重跑 e2e 并 `--bless`

## Feature 拆分

| id | name | 依赖 | acceptance | verify_cmd |
|----|------|------|------------|------------|
| op-01 | 开篇节奏加速 | [] | 前 N 章 buildChapterLengthGuide 不判定喘息；buildWritingConstraints 注入开篇钩子+爽点+冲突升级硬约束 | `mvn test -Dtest=OpeningPacingTest` |

## 风险

- **风险**：开篇高压可能导致信息过载。缓解：仅要求 2 爽点 + 钩子，不要求 3+ 事件。
