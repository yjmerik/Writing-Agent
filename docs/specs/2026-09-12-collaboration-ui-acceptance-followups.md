# Spec: 界面验收发现的缺陷回流

> 证据：[docs/reports/2026-09-12-collaboration-ui-acceptance.md](../reports/2026-09-12-collaboration-ui-acceptance.md)，验证代码 4437b5a。

## 背景与动机

原方案尚未通过作者完整流程验收。此次仅登记可独立验证的小修复，不开始实现。完整意见、计划、成书等缺口见报告，后续继续拆分，不以这些修复代表原方案完成。

## 目标

消除空标签启动异常、旧报告误作当前、草稿丢失及当前稿/待办不一致；保留阅读定位与窄屏问题明细。

## 非目标

本轮不修改业务代码、不调 prompt、不更新 golden、不进行真实文学效果实验。版本化批准全链与候选持久化需另拆上下游切片，不在屏障稿号同步中顺带承诺。

## 验收标准

每项先以表中命令复现失败，再修实现；实现后命令退出 0，并完成仓库 compile/test 与适用 E2E 门禁。浏览器脚本必须通过 ego-browser heredoc 操作本地测试界面，不改数据库伪造结果。

## 影响面

- Agent 输入规范化、当前质量报告投影、修订与屏障、React 编辑/路由/响应式审查。
- 目标文件是计划范围；未创建的测试/脚本在实现时加入，每项 1–3 个文件。
- 无预定 prompt 模板改动；若实际修复影响注入或产出逻辑，遵守 record/replay 与人工 golden 确认门禁。
- 草稿基准和历史屏障需保留；具体迁移在各 feature 实现前核实。

## Feature 拆分

| ID | 范围 | 依赖 | 验收 | verify_cmd |
|---|---|---|---|---|
| p34-01 | 空类型标签的新项目可开始构思 | 无 | null/空 tags 的构思先失败复现，修复后正常生成世界观；无模型调用前 NPE。 | `mvn test -Dtest=WorldBuilderOptionalTagsTest` |
| p34-02 | 当前章质量报告拒绝冒用旧稿评价 | 无 | 当前章 v6 只有 v2 报告时，不返回它作为当前有效评价；历史报告仍保留。 | `mvn test -Dtest=ChapterQualityVersionValidityTest` |
| p34-03 | 正文未保存草稿刷新可恢复 | 无 | 通过 ego-browser 在本地场景编辑第7章、刷新、恢复同项目同目标草稿；版本已变时明确冲突，不覆盖正式稿。 | `bash scripts/verify-ui-draft-recovery.sh` |
| p34-04 | 正式修订同步当前人审屏障稿号 | 无 | 待审 v2 保存 v3 后，当前有效待办与权威正文稿号一致；历史屏障不丢失，未复核不得默认为已批准。 | `mvn test -Dtest=ReviewBarrierRevisionBindingTest` |
| p34-05 | 章节和工作区视图可通过刷新恢复 | 无 | 选择第7章并刷新仍定位第7章；章节和视图可深链接恢复，后台新增章节不抢走选择。 | `bash scripts/verify-ui-chapter-route.sh` |
| p34-06 | 窄屏审查抽屉保留问题明细 | 无 | ego-browser 390px 场景抽屉展示与桌面相同的问题明细和已有操作，键盘可访问，不只显示问题数量。 | `bash scripts/verify-ui-mobile-review.sh` |

## 风险与备选方案

先修严重正确性问题；本轮已确认第1章屏障实际挡住续写，不把稿号错位扩大解释为已经发生绕过。真实改写受外发授权限制，本地确定性模型只验证机制。
