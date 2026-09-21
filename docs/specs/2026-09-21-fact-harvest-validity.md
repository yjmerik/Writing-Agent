# Spec: 真典提取无效响应不得记为完成

## 背景与动机

NCS-033 的 2026-09-21 E2E 真实录制中，第3章真典提取响应仅为 `[`，`FACT_HARVEST` 却标为 `COMPLETED`，没有新增事实或错误事件。诊断：`docs/harness/evidence/ncs033/continued-fact-harvest-diagnosis-20260921.json`。这是独立后处理缺陷，仅登记 backlog；本轮不扩展实现范围。

## 目标

区分有效空数组与无效输出；无效输出产生可定位的失败证据，后处理账本保持可恢复的未完成状态；不得部分写入后伪报成功。

## 非目标

不放宽一致性门槛、不修改模型配置、不清除人工屏障、不重录或更新 golden。

## 验收标准

- [ ] 先写 failing test：输入 `[`、错误 JSON、缺少必需字段或数组中含非法项，不得返回成功空结果或部分成功；合法 `[]` 仍兼容。
- [ ] 通过集成测试验证无效提取不会将 `FACT_HARVEST` 标记为 `COMPLETED`；存在可定位错误，恢复后能完成且无重复事实。
- [ ] `mvn test -Dtest=FactHarvestValidityTest,FactHarvesterTest,PostProcessingRecoveryTest` 通过。
- [ ] 编译、全量测试、适用 E2E 与基线比较完成；必须保留本次无效响应作为回归样本，不以重新采样替代复现。

## 影响面

- **涉及模块**: FactHarvesterAgent、ChapterPostProcessor、一个组合回归测试类。
- **涉及 prompt 模板**: 预计无；如改动需按 AGENTS.md 重建匹配语料与双回放。
- **数据库 schema 变更**: 无。
- **质量基线影响**: 原本被静默接受的失败应显式暴露；不能用降低门槛更新 golden。

## Feature 拆分

| id | name | 依赖 | acceptance | verify_cmd |
|----|------|------|------------|------------|
| NCS-063 | 真典提取无效输出不得完成后处理 | [] | 无效 JSON/非法条目不得成功或完成账本；合法空数组可完成；恢复无重复 | `mvn test -Dtest=FactHarvestValidityTest,FactHarvesterTest,PostProcessingRecoveryTest` |

## 风险与备选方案

错误传播可能改变现有继续写作行为，应保留可恢复状态并验证已有人工屏障。不能将所有空结果判失败；必须区分有效 `[]` 与不可解析响应。
