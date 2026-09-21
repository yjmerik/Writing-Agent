# Spec: 实体提取失败不得被后处理忽略

## 背景与动机

NCS-033 的 2026-09-21 真实录制中，三章实体提取均返回截断 JSON。EntityExtractorAgent 返回携带 error 的结果，但调用方忽略该结果，将三次 ENTITY_GRAPH 均记为 COMPLETED；实体和关系表均为零。诊断：`docs/harness/evidence/ncs033/continued-entity-diagnosis-20260921.json`。本轮仅登记，不扩展实现范围。

## 目标

实体提取的错误结果必须传播到后处理账本与可观测错误；有效空图可完成，失败阶段可重试且不重复已有实体或关系。

## 非目标

不修改模型配置，不修复其他后处理阶段，不修改人物或小说正文，不重录或更新 golden。

## 验收标准

- [ ] 先写 failing test：截断 JSON 或 ExtractionResult.error 非空时，不得将 ENTITY_GRAPH 记为 COMPLETED，且输出可定位错误。
- [ ] 有效空图兼容；修复响应后恢复能完成，已保存数据不重复。
- [ ] `mvn test -Dtest=EntityGraphValidityTest,PostProcessingRecoveryTest` 通过；编译、全量测试及适用 E2E 通过。

## 影响面

- **涉及模块**: EntityExtractorAgent、ChapterPostProcessor、EntityGraphValidityTest。
- **涉及 prompt 模板**: 预计无；若改变上下文需依 AGENTS.md 重建语料。
- **数据库 schema 变更**: 无。
- **质量基线影响**: 暴露原本未计入报告的业务解析失败；不得用放宽门槛获取 PASS。

## Feature 拆分

| id | name | 依赖 | acceptance | verify_cmd |
|----|------|------|------------|------------|
| NCS-064 | 实体提取失败结果不得被忽略 | [] | 错误结果不完成账本；有效空图兼容；恢复无重复 | `mvn test -Dtest=EntityGraphValidityTest,PostProcessingRecoveryTest` |

## 风险与备选方案

与 NCS-063 均涉及 ChapterPostProcessor，须按单特性分支协议逐项合并；保持人工复核状态，不将业务解析失败冒充网络成功。
