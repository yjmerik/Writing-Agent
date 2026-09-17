# 协作修复第三轮复验

日期：2026-09-17。检出：main，1b06d01421f13e7b07cda708783b6610a4f61fe7。

结论：本轮修复有效，但仍不能支持 p36–p40 共 58 项全部完成的结论。以下问题均由本次运行复现，没有修改业务代码。

## 尚未通过的验收

1. **P1：批准使用了不匹配版本的依据。** 对真实 HIGH 问题提交 version=999、虚构 contentHash 的 EXCEPTION 裁决，接口返回 200，随后原先被阻止的章节批准也返回 200。另以隔离数据库构造 revision=1 的已落实证据，再经真实接口将 HARD 意见改为 revision=2，旧证据仍让当前批准返回 200。SqliteNovelRepository.java:1493–1539 的筛选没有核对意见 revision，也没有核对裁决的章节身份。证据：[deeper-probes.json](/tmp/collab-audit3-20260917/deeper-probes.json)。

2. **P1：计划确认不是跨对象原子提交。** 临时数据库中对当前项目的偏好写入注入 SQL 失败，目标仍是原来的 24 章，但计划预览已是 CONFIRMED；解除故障后，用原请求重试仍返回 409“该预览凭证已确认”。PlanEditionService.java:168–205 先提交确认状态，后写偏好；补偿也可能被同一故障阻断。注入的触发器已删除。证据：[deeper-probes.json](/tmp/collab-audit3-20260917/deeper-probes.json)。

3. **P1：人物修改仍存在无凭证入口。** 显式 confirm=false 已能正确预览，但完全省略 confirm/previewToken 的 PUT 仍返回 200 并改变人物动机。ContentController.java:95–118 仅在显式 confirm=true 时校验凭证。p39-09 的计划明确要求移除无预览直接写入旁路。证据：[previous-probes.log](/tmp/collab-audit3-20260917/previous-probes.log)。

4. **P2：真实历史稿事实来源被拒绝。** 保存 v2 的真实身份和正文，引发一次正常编辑生成 v3，再提交与历史 v2 匹配的事实证据，返回 400。FactEvidenceController.java:78–95 只查询当前稿；p39-03 要求验证对应历史稿。不存在的章节和跨项目来源现已正确被拒绝。证据：[deeper-probes.json](/tmp/collab-audit3-20260917/deeper-probes.json)。

5. **P1：实验脚本仍未执行原验收标准。** 原计划要求质量均差不低于 -0.2、审阅时间中位数下降至少 20%，严重连续性错误不增加并展示 CI。当前脚本允许质量下降 1.5 分、平均耗时增加到两倍。240 行合成反例（协作评分 7 对照 8，耗时 60 对照 40）仍返回 COMPLETE、退出 0。合成数据只用于验证脚本，不作为文学质量结论。本轮未评判现有人工评分数据的真实性。证据：[study-threshold-probe.log](/tmp/collab-audit3-20260917/study-threshold-probe.log)、[spec-failing-study.csv](/tmp/collab-audit3-20260917/spec-failing-study.csv)。

6. **P2：未生成候选的修改要求草稿刷新丢失。** 浏览器中输入新的修改要求但不生成候选，刷新后输入为空。AgentRevisionDialog.tsx:91–99 重置该状态。已经生成的候选、修改要求和可读预览恢复正常。证据：[ui-probes.log](/tmp/collab-audit3-20260917/ui-probes.log)。

## 已确认修复

- 新 HARD 意见、未解决 HIGH 问题的直接批准已返回 409。
- 旧成书快照不能借用新稿批准，保持 DRAFT。
- 不存在章节、跨项目事实证据被拒绝。
- 计划无凭证确认被拒绝；梗概变更使旧预览失效。
- 承诺决定写入失败时，目标不变且预览仍为 PENDING。
- 验收脚本能正确传播 Maven 失败退出码。
- 人物编辑界面在 1280px 和 390px 视口下，预览和取消不写入，确认后才保存。这不消除上文的 API 旁路。
- 已生成候选刷新后可以恢复修改要求和可读预览。

## 本次执行结果

- ./init.sh：PASS；882 个后端测试，0 failures / 0 errors / 1 skipped；前端构建通过。最初受沙箱端口限制失败，获准本机监听后重新全量运行通过。
- check-harness.py --check-git：PASS；p36–p40 的 58 项均登记 completed。此检查只证明记录一致性。
- Playwright 现有回归：29 passed；另执行上述独立浏览器复验。
- 评分脚本既有 5 个场景通过；当前评分 CSV 返回 COMPLETE，但验收算法仍存在上述反例。
- 悬疑 replay：写入 2 章后 PAUSED，等待人工复核；基线检查 PASS。
- 武侠 replay：写入 1 章后 PAUSED，等待人工复核；基线检查 PASS。
- 两份 replay 报告均 evaluation=null。不能据此宣称三章写作全部结束、长篇实验完成或文学质量已验证。

## 环境与证据

全部 API 写操作、SQL 故障注入和浏览器操作使用 /tmp 下的隔离数据库；回放使用已有 fixture，未调用真实模型、未更新 golden。已停止本轮测试服务。Git 工作区保留原有两个未跟踪路径，无新增跟踪文件变更。

元数据：[verification-metadata.json](/tmp/collab-audit3-20260917/verification-metadata.json)。回放：[mystery-report.json](/tmp/collab-audit3-20260917/mystery-report.json)、[wuxia-report.json](/tmp/collab-audit3-20260917/wuxia-report.json)。界面确认：[character-ui.json](/tmp/collab-audit3-20260917/character-ui.json)。现有浏览器回归：[playwright.log](/tmp/collab-audit3-20260917/playwright.log)。
