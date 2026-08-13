# Spec: 配角刻画深度 + 事件模式多样性（depth-diversity）

> 触发事件：质量 Agent 三次复评（overallScore 9）后剩余两个 MEDIUM——配角塑造偏功能性、爽点/事件触发模式重复（铜铃异响→账册显现→人物死亡）。

## 背景与动机

- **配角厚度**：buildCharacterVoiceSection 只注入说话风格，未要求配角展现独立性格/动机/弧光；LLM 把配角当功能性工具人。
- **模式重复**：ClimaxDiversityTracker 轮换爽点"类型"，但不约束"事件触发→展开→结局"的递进结构；3 章都用"异响→账册显字→死亡"同一模板。

## 目标

1. 出场配角必须展现独立性格/动机（至少一个不服务于主线的个人化反应）。
2. 禁止与前章相同的事件递进结构（触发→验证→爆发模板）。

## 验收标准

- [ ] `mvn test -Dtest=CharacterDepthDiversityTest` 通过。
- [ ] `mvn test` 全绿。
- [ ] 10 章 e2e record + 质量评估：characters/pattern_diversity 维度提升或 MEDIUM 降级。

## Feature 拆分

| id | name | acceptance | verify_cmd |
|----|------|------------|------------|
| cd-01 | 配角刻画深度约束 | 有配角的章节约束含"配角独立性格"要求 | `mvn test -Dtest=CharacterDepthDiversityTest` |
| pd-01 | 事件递进模式多样性约束 | 约束含"禁止与前章相同的事件触发→展开→结局结构" | 同上 |
