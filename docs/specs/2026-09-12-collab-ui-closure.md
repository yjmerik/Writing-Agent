# Spec: 协作 UI 闭环与并发专项（Phase 35）

> 依据：`docs/reports/2026-09-12-collaboration-ui-acceptance.md` 交付缺口清单；设计文档 5.3/5.6/5.7。后端 API（directives、foreshadow-candidates、plan/target-chapters）已就绪，本 spec 补 UI 接入与并发专项测试。

## 背景

验收报告确认：意见只有单个文本框、候选线索无界面、篇幅无法调整、无成书清单/导出、A4 并发场景未测。后端 Phase33 已具备全部 API，缺 UI 与专项验证。

## 目标

1. 意见面板接入三栏审查页（登记/列表/确认/撤销，消费 directives API）。
2. 候选线索管理界面（列表/提升/拒绝，消费 foreshadow-candidates API）。
3. 篇幅调整 UI（目标章数编辑 + 影响预览 + 确认生效，消费 plan/target-chapters API）。
4. 并发专项：多窗口并发放行、陈旧放行被拒（A4 未测项）。
5. 成书审查页第一版：覆盖清单（各章状态/稿号/报告有效性）+ 全书导出（Markdown 下载）。

## 非目标

- BookEdition 不可变快照持久化（后续 spec）；事实时间线/知识边界视图；P4 文学实验。

## 验收标准

- [ ] `mvn test -Dtest=ResolveReviewConcurrencyTest`：并发放行仅一方成功；屏障稿号不符的放行被拒。
- [ ] `bash scripts/verify-ui-directives-panel.sh` 等 3 个静态校验 PASS；`npm run build` 通过。
- [ ] 全量 mvn test 绿；e2e/基线不受影响（纯前端+新只读端点）。

## 影响面

- **涉及模块**: web/（成书聚合端点）、frontend（InteractiveReview/OutlineView/QualityView/新组件）、scripts/verify-*
- **数据库 schema 变更**: 无
- **质量基线影响**: 无 prompt 改动，无语料影响

## Feature 拆分

| id | name | 依赖 | acceptance | verify_cmd |
|----|------|------|------------|------------|
| p35-01 | 意见面板接入三栏 | [] | 静态校验+构建通过：登记/列表/确认/撤销按钮消费 directives API | `bash scripts/verify-ui-directives-panel.sh` |
| p35-02 | 候选线索管理界面 | [] | 静态校验+构建通过：候选列表/提升/拒绝 | `bash scripts/verify-ui-candidates-panel.sh` |
| p35-03 | 篇幅调整影响预览 UI | [] | 静态校验+构建通过：目标编辑/预览/确认 | `bash scripts/verify-ui-target-chapters.sh` |
| p35-04 | 并发放行 CAS 专项 | [] | 并发 resolve-review 仅一方成功；屏障稿号不符放行被拒 | `mvn test -Dtest=ResolveReviewConcurrencyTest` |
| p35-05 | 成书审查页与导出 | [] | 覆盖清单聚合端点 + 前端成书页签 + Markdown 导出下载 | `mvn test -Dtest=BookReviewPageTest` |
