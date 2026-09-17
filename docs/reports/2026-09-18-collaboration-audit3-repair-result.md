# 第三轮修复与最终复验 — 2026-09-18

原报告六类软件问题已修复，按9个独立特性依次提交、合入本地main并完成干净检出复验。最终业务提交为 `fb9e03396660189d7c5029dea8357e371b737770`；本文件与完成状态属于其后的验证回执。未推送远端。

**整体验收仍为 INCOMPLETE**：真实文学实验缺严重连续性错误计数；两题材回放仍等待人工复核。另有独立的旧候选HTTP状态问题已登记p41-10，未混入本轮业务实现。

## 修复结果

| 原问题 | 最终真实行为 |
|---|---|
| 批准依据版本不匹配 | 错误稿号裁决的写入返回409；库中既有错误稿号裁决也不能放行批准（409）。revision=1证据不能满足revision=2意见（409）；补匹配证据后批准200。 |
| 计划失败回滚不完整 | 持续偏好写入失败返回500，偏好、大纲、卷、梗概、决定、计划状态逐行与失败前一致；保持PENDING；移除故障同请求200，重复成功请求409无重复写入。 |
| 人物修改绕过预览 | 缺失/非布尔confirm或无凭证请求拒绝；false只预览，true须匹配来源凭证。实际HTTP缺confirm400且动机不变，正规预览确认200。 |
| 历史事实来源误拒 | 正式编辑产生v3后，真实历史v2的hash及引文返回200；不存在版本、错hash、新稿专有引文和跨项目来源拒绝。UNKNOWN引文语义保留。 |
| 实验验收标准过宽 | 恢复质量均差≥−0.2、中位耗时下降≥20%、严重错误不增加；章配对与去重、有限数字校验、固定seed置信区间。240行合成反例现在FAILED、退出1，质量差−1、耗时降幅−50%。 |
| 未生成候选的输入丢失 | 同浏览器按项目/对象/章/段落独立暂存，刷新逐字恢复；取消与失败保留，成功应用仅清除对应草稿；恢复旧候选覆盖新要求前须明确选择；存储异常有警告且保留内存输入。 |

实际HTTP复验脚本与结果：[脚本](/tmp/audit3-final/api-probes.py)、[结果](/tmp/audit3-final/api-probes.json)、[计划各表前后状态](/tmp/audit3-final/plan-state-before-after.json)。SQL故障和历史错误记录仅写入本轮临时库；未触及用户小说数据。

## 主分支验证

| 检查 | 实际结果 |
|---|---|
| 编译与后端全量 | 931 tests，0 failures，0 errors，1 skipped。 |
| 统计契约 | 14项通过；包含原退化反例、边界、非法数据、重复评委、严重错误和配对bootstrap可复现性。 |
| 前端构建 | 通过；保留既有打包体积提示，无新增构建错误。 |
| 全部协作回归 | 38通过，0失败，0跳过，0 flaky。 |
| 草稿专项 | 9通过；修复前同组9项均失败，原始证据在 `/tmp/p41-09-qa-red-confirmed`。 |
| 完整验收入口 | `bash scripts/verify-collaboration-acceptance.sh --with-browser --base-url http://127.0.0.1:18089` 返回1；PASS=7 FAIL=1，唯一失败项为真实实验INCOMPLETE。脚本旧展示文字仍写5 scenarios，实际统计日志为14项。 |
| Harness | `python3 scripts/check-harness.py --check-git` 通过；历史Phase0–26证据限制告警保留。 |

完整验收日志：[结果](/tmp/audit3-final-acceptance.log)；后端：[全量](/tmp/p41-09-main.log)；浏览器：[主分支报告](/tmp/writingagent-acceptance-qa/report.json)；统计：[反例结果](/tmp/audit3-final/synthetic-counterexample-result.json)。合成评分仅验证算法，不作为文学实验数据。

## 界面验证

环境：隔离服务 `http://127.0.0.1:18089`，临时SQLite、确定性Scenario网关；Chromium，1280×900与390×844。Browser plugin not available，使用仓库现有Playwright；没有新增依赖版本。运行时Java26.0.1，编译目标17；未另外验证Java17运行时。

路径：章节页→让Agent修改→输入要求但不生成→刷新→重开；恢复原有空格、换行和中文，未自动恢复候选应用权限。

| 界面检查 | 结果 |
|---|---|
| 页面身份及非空内容 | 通过：目标章节URL、标题和正文可见。 |
| 框架错误覆盖层 | 无。 |
| 控制台/页面异常 | 桌面与窄屏正常恢复路径无error；故障注入路径的503/旧候选500属于刻意验证的失败响应。 |
| 交互与截图 | 通过：输入、取消、恢复、明确替换、成功及失败应用；窄屏输入框和按钮可用。 |

旧裁决QA曾要求为虚构issue创建成功，全套回归因此首次37通过/1失败；已迁移为400、无新增裁决且reviewSetHash不变。真实问题裁决创建/查询/批准正向路径由 `ReviewDispositionApiTest` 持续覆盖，未把拒绝虚构问题当作正向创建验收。

## 回放与文学实验边界

| 题材 | 已写章节 | 任务状态 | evaluation | 基线及诊断 |
|---|---:|---|---|---|
| mystery | 2 | PAUSED：第2章待人工复核 | null | 基线PASS；0章节失败、0调用错误。 |
| wuxia | 1 | PAUSED：第1章待人工复核 | null | 基线PASS；0章节失败、0调用错误。 |

使用原有 `mystery-20260916-p40-06` / `wuxia-20260916-p40-06` 语料，仅replay。未录制、未bless、未改prompt、未代替作者批准。基线最小章数为1且evaluation为空时跳过评分比较，因此其PASS仅能说明现有基线断言满足；不能证明完整流程或文学质量。诊断中的成本为回放token估算，非本轮实际付费调用。

[悬疑报告](/tmp/audit3-final/mystery-report.json) · [武侠报告](/tmp/audit3-final/wuxia-report.json) · [悬疑诊断](/tmp/audit3-final/mystery-diagnose.md) · [武侠诊断](/tmp/audit3-final/wuxia-diagnose.md)

真实CSV原样保留，严格重评缺 `severeContinuityErrors` → INCOMPLETE。p40-05/p40-06恢复deferred；旧声明与完成记录[原样归档](../harness/archive/2026-09-18-audit3-superseded-study.json)。后续需真实人工逐章错误计数和人工复核，不能默认零或用脚本合成数据替代。

## 提交与回执

| 特性 | 内容 | 实现提交（已在main） | 证据 |
|---|---|---|---|
| p41-01 | 批准事务绑定稿件身份与意见修订 | `682ad01` | [verified](../harness/evidence/2026-09-17-p41-01.json) |
| p41-02 | 裁决写入校验真实问题和稿件 | `51b6898` | [verified](../harness/evidence/2026-09-17-p41-02.json) |
| p41-03 | 计划原子提交的 SPI 契约 | `65d8d56` | [verified](../harness/evidence/2026-09-17-p41-03.json) |
| p41-04 | 计划确认跨对象事务及故障重试 | `7ee72a6` | [verified](../harness/evidence/2026-09-17-p41-04.json) |
| p41-05 | 人物旧调用测试迁移到预览确认 | `64e7245` | [verified](../harness/evidence/2026-09-17-p41-05.json) |
| p41-06 | 关闭人物无预览写入旁路 | `02dc11d` | [verified](../harness/evidence/2026-09-17-p41-06.json) |
| p41-07 | 事实证据验证对应历史稿 | `a9455dd` | [verified](../harness/evidence/2026-09-17-p41-07.json) |
| p41-08 | 实验严格门槛与可复现统计 | `05c3993` | [verified](../harness/evidence/2026-09-18-p41-08.json) |
| p41-09 | Agent 修改要求独立草稿恢复 | `fb9e033` | [verified](../harness/evidence/2026-09-18-p41-09.json) |

## 后续与环境清理

独立发现：[p41-10过期候选HTTP状态](../specs/2026-09-18-stale-candidate-http-status.md)。来源变化后的候选没有覆盖正文，但返回500，需独立修复为409；当前not-started。

本轮隔离服务及回放服务已关闭。主工作目录原有两处未跟踪内容保留；业务源码与状态记录均已提交。工作目录 `/tmp/writing-agent-audit3-design` 保留，便于查看本轮生成产物。

## 截图

![桌面刷新后恢复输入](/tmp/audit3-final/draft-1280.png)
![窄屏刷新后恢复输入](/tmp/audit3-final/draft-390.png)
