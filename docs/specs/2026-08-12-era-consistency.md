# Spec: 时代一致性护栏（era-consistency）

> 触发事件：3 章快速验证的质量评估（overallScore 7/10）发现 HIGH 一致性矛盾——世界观设定"大明宝钞"（明代），第 3 章正文却出现"道光年间的《千字文》"（清代）。这是一类系统性问题，不是单本小说的偶发。

## 背景与动机

- 现状：WorldBuilder 生成的世界观条目隐含时代设定（朝代/年号/器物/官制），但 ChapterWriter 没有显式约束"必须与世界观时代一致"，ContinuityChecker 也没有专门的"时代矛盾"检测维度。
- 后果：长篇写作中 LLM 容易引入其他朝代的年号、典籍、官制（道光/康熙/大明/大清混用），削弱世界观严谨性，质量评估 consistency 维度低分。
- 这是任何古代/历史题材长篇都会复现的通用问题，需通用方案。

## 目标

1. 写作时：ChapterWriter 收到"时代一致性"硬约束，明确世界观确立的时代，禁止引入其他朝代的年号/器物/典籍/官制。
2. 检查时：ContinuityChecker 把时代矛盾作为 HIGH 一致性问题专门检测。
3. 通用：从世界观条目自动推导时代锚点，无需人工标注；对非历史题材（现代/科幻/玄幻架空）安全降级。

## 非目标

- 不做历史知识库/年号词典（用关键词集合覆盖主流朝代年号即可）。
- 不改数据库 schema（时代锚点在组装时实时推导，不持久化）。
- 不解决情节节奏（MEDIUM plot）——单独议题。

## 验收标准

- [ ] `mvn test -Dtest=EraAnchorTest` 通过：从含"明代/洪武"的世界观条目推导出时代锚点；多朝代冲突时标记。
- [ ] `mvn test -Dtest=EraConstraintInjectionTest` 通过：古代题材项目的写作约束含"时代一致性"行 + 时代锚点；现代题材不含。
- [ ] `mvn test -Dtest=ContinuityEraCheckTest` 通过：ContinuityChecker 注入时代锚点 + 检测指令。
- [ ] `mvn test` 全绿。
- [ ] `./scripts/e2e.sh --mode record --chapters 3` + 质量评估：consistency 维度不再出现"朝代/年号混用"HIGH 问题（人工确认或评分提升）。

## 影响面

- **涉及模块**: memory（ContextAssembler）、agent（ContinuityCheckerAgent）、新增 service（EraAnchorService）
- **涉及 prompt 模板**: 无变量名改动；约束文本在 Java 侧拼接注入
- **数据库 schema 变更**: 无
- **质量基线影响**: 需重跑 e2e 并 `--bless` 更新基线（约束变化改变产出）

## Feature 拆分

| id | name | 依赖 | acceptance | verify_cmd |
|----|------|------|------------|------------|
| ec-01 | 时代锚点推导 EraAnchorService | [] | 从世界观条目+concept 扫描朝代/年号关键词，返回时代锚点字符串；冲突时标注；无信号返回空 | `mvn test -Dtest=EraAnchorTest` |
| ec-02 | ChapterWriter 时代一致性约束 | [ec-01] | 古代/历史题材项目的 WritingConstraints 含时代锚点+禁混用约束；非历史题材不注入 | `mvn test -Dtest=EraConstraintInjectionTest` |
| ec-03 | ContinuityChecker 时代矛盾检测 | [ec-01] | 检查段注入时代锚点 + "年号/朝代矛盾判 HIGH" 指令 | `mvn test -Dtest=ContinuityEraCheckTest` |

## 风险与备选方案

- **风险**：关键词集合覆盖不全 → 漏判。备选：先用主流朝代+年号，后续按诊断扩充。
- **风险**：架空/玄幻题材误判（如"大明"出现在玄幻设定中）。缓解：仅当 concept/tags 含古代/历史/武侠/权谋等题材标记时启用强约束；否则软提示。
- **回滚**：EraAnchorService 返回空 → 约束/检查不注入 → 行为退化为现状。
