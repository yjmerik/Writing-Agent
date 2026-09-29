# Spec: 读者视角评审 Agent（剧情/连续性/艺术性）

## 背景与动机

UI 真实用户测试（2026-09-29，报告 docs/reports/2026-09-29-ui-archtest/测试报告.md）确认现有
质检体系（ContinuityChecker/ConsistencyGuard/ReaderReview/QualityEvaluation）以**工程视角**
运行：检出矛盾、评分通过率。但用户（作者）缺一个**读者视角**的评审——
像真实读者一样读完已有章节，从三个维度给出整体反馈：
- **剧情**：主线是否清晰、节奏是否合理、悬念是否有吸引力、章节间推进感
- **连续性**：作为读者（不看台账）能否自然跟上人物/时间线/伏笔，有无读感断裂
- **艺术性**：文字质量、意象运用、对话鲜活度、有无审美层面的亮点或短板

与现有检测器的区别：不产出 ContinuityIssue、不触发重写/屏障——是**只读的读者报告**，
帮助作者在继续写作前获得「读者会怎么看这本书」的外部视角。

## 目标

1. 新增 `ReaderPerspectiveAgent`：输入项目已有章节（分层采样防 token 爆炸），
   输出结构化读者报告（三维度评分 + 逐章亮点/问题 + 整体建议）
2. REST 端点 `POST /api/projects/{id}/reader-review`（异步 LongTask）+ `GET` 查询
3. 报告持久化，可多次评审保留历史

## 非目标

- 不产出 ContinuityIssue、不参与仲裁/屏障/重写决策
- 不替代 QualityEvaluationAgent（卷末工程评价）或 ReaderReviewAgent（逐章门禁评分）
- 不做逐字校对（时代用语等已有 ConsistencyGate 覆盖）

## 验收标准

- [ ] `mvn test -Dtest=ReaderPerspectiveAgentTest` 通过
- [ ] `POST /reader-review` 返回 jobId，轮询得 DONE 且 result 含三维度评分与逐章反馈
- [ ] 评审不改变任何章稿/台账/屏障状态（只读性）

## 影响面

- **涉及模块**: agent/（新增 ReaderPerspectiveAgent）、web/（新增端点）、persistence/（报告表）
- **涉及 prompt 模板**: 新增 reader-perspective.st
- **数据库 schema 变更**: 新表 reader_review_report

## Feature 拆分

| id | name | 依赖 | acceptance | verify_cmd |
|----|------|------|------------|------------|
| NCS-138 | 读者视角评审 Agent | [] | 三维度报告产出且只读 | `mvn test -Dtest=ReaderPerspectiveAgentTest` |

## 风险与备选方案

- 报告不落 ContinuityIssue → 不会误触发屏障；风险是作者可能忽略建议 → 前端呈现层突出显示
- 分层采样：长篇防 token 爆炸（复用 NCS-126 的策略：首末全摘、中间分层）
