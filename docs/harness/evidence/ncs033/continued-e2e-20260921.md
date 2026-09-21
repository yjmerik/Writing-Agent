# NCS-033 继续 E2E 验收 — 2026-09-21

## 结论

一次真实录制及两次离线回放已完成，三次均 **完整验收 FAIL（exit 1）**。三章正文、版本、状态、完整归一化报告一致：3章 / 9209字 / 60次调用 / 1个复核屏障 / 1章待复核 / 4项当前稿未解决 HIGH。NCS-033 保持 in-progress，未 bless、未人工放行。基线数值比较 PASS 不代表小说质量通过。

本轮只运行验收并登记诊断，没有修改生产代码。代码来自 main `9aa15db` 的干净导出，已有业务修复提交 `3ce8cd6` 已在 main；验收文档分支 `codex/docs-ncs033-e2e-20260921`。文档提交 `62b6cd2` 已快进合 main；[合并后验证回执](continued-main-validation-20260921.json)记录实际检查结果。

## 范围与复现

用户明确要求“继续E2E验收”。本轮使用同一 author-intent.json、mystery 题材、既定 Token Plan 端点与 qwen3.8-flash、embedding stub；新样本不替换旧失败样本。

隔离目录 `/tmp/ncs033-acceptance-20260921`，端口18103。设置 `E2E_FIXTURE_DIR=data/e2e-fixtures/ncs033/mystery-20260921-acceptance`，通过 `run-intent-e2e.py --mode record --chapters 3 --port 18103` 录制一次，再以 `--mode replay` 连续运行两次。包装器只注入既有测试创作约定和固定测试环境，其内部执行 scripts/e2e.sh。每次结束先保存独立报告和数据库。

- [完整机器结果](continued-e2e-20260921.json)：三个不同 projectId、三次报告、正文 SHA-256、检查命令和结果。
- [60条语料清单及哈希](continued-fixtures-20260921.json)。5条写作/纠偏请求均包含全部8项创作约定值。
- 稳定原始证据：`data/e2e-evidence/ncs033/acceptance-20260921/`，含三次数据库、事件、日志、诊断、数值基线对比及语料。
- 可继续回放的语料：`data/e2e-fixtures/ncs033/mystery-20260921-acceptance/`。
- 旧样本保留在 `data/e2e-evidence/ncs033/quality-closure-20260921/`；旧失败记录仍有效。

## 新旧结果

| 项目 | 上轮样本 | 本轮 record / replay1 / replay2 |
|---|---|---|
| 正文章数／字数 | 2 / 7606 | 3 / 9209，三次相同 |
| 调用次数／最终调用错误 | 44 / 0 | 60 / 0，三次相同 |
| 复核屏障 | 第2章，逐项复验返回不完整 | 第3章，守卫复查仍有 HIGH |
| 当前稿未解决 HIGH | 0（复验缺证仍阻断） | 4 |
| 完整验收 | FAIL | 三次 FAIL |
| 数值基线对比 | PASS | 三次 PASS |

第1章2713字、v2、DONE；第2章3467字、v3、DONE；第3章3029字、v3、NEEDS_MANUAL_REVIEW。第2章原5项 HIGH 均逐项复验 RESOLVED；第3章原12项 HIGH 经一次纠偏后，守卫仍报3项 HIGH，未继续原问题逐项复验；卷末检查另产生1项“19条未回收伏笔”汇总 HIGH。

任务计数达到3章，报告 taskStatus 为 COMPLETED；同时存在 WRITING_PAUSED 事件、当前稿复核屏障和未解决问题，严格完整验收正确判 FAIL。不能据任务计数或0调用错误判断质量通过。

## 已准确定位的问题

### 1. 守卫上下文遗漏，至少一项确定误报

[逐项证据](continued-guard-diagnosis-20260921.json)。第3章守卫称“会票从船板缝取回／盐霜没有来源”，但第2章正文已有取票与检验场景；“义庄旧册画指未出现”的断言也与第2章结尾冲突。对应会票、盐霜和旧册画指事实已在 ACTIVE 真典中，均未进入检查参考上下文。

`ConsistencyGuard.check` 仅注入角色能力、`relevantFacts(projectId, cast, 15)` 选取的事实和本章正文，未传本章编号、前章正文或按本章关键道具检索的证据。`RelevanceGateway` 按 cast 收窄并让角色／关系等必选事实占据容量，遗漏了对象事实。此处是可用真实输入复现的上下文缺陷，不能把“未召回”当成“前文未发生”。仍需单独核验残账新刻字等判断，未直接解除任何屏障。

### 2. 真典无效输出被记为完成（NCS-063，仅登记）

[诊断](continued-fact-harvest-diagnosis-20260921.json)。第3章事实提取返回单字符 `[`；FactHarvesterAgent.merge 在缺少闭合括号时返回零结果、解析异常也被吞掉。后处理将 FACT_HARVEST 标为 COMPLETED，未新增第3章事实，也没有错误事件。三次运行均复现。

[修复规格](../../../specs/2026-09-21-fact-harvest-validity.md)要求先 failing test，再区分合法 `[]` 与无效输出，保留可恢复的未完成状态。

### 3. 实体提取错误结果被忽略（NCS-064，仅登记）

[诊断](continued-entity-diagnosis-20260921.json)。三章实体响应均为截断 JSON。EntityExtractorAgent 返回带 error 的结果，但 ChapterPostProcessor.stageEntityGraph 忽略返回值，账本三次均 COMPLETED。实体表与关系表均为0；三次运行均复现。

[修复规格](../../../specs/2026-09-21-entity-extraction-validity.md)要求检查错误结果、阻止伪完成并验证恢复不重复。

### 4. 卷末伏笔仍未闭合

另1项 HIGH 汇总19条未回收伏笔，包含明确指向后续卷的线索，以及“埋第1章→预计第3章回收）”这类时间信息碎片。需要结合已登记 NCS-061/062 和实际大纲语料继续核验，不能把全部19条直接认定为应在本卷结束的遗漏。本轮保留原报告，未改台账。

## 验证

- 本轮 init：编译通过；1141 tests / 0 failures / 0 errors / 1 skipped；前端构建通过。
- NCS-033 verify_cmd：47 tests / 0 failures / 0 errors。
- 首次受限环境不能绑定测试端口，已在授权环境复跑通过，未作为业务回归。
- 三次 E2E 完整验收均 FAIL；三次离线数值基线比较均 PASS；三次 diagnose 均识别复核屏障／写作暂停。
- 录制期间3次流式中断经客户端重试成功，最终传输调用错误为0；以上4次无效业务响应不计入此指标。
- 合并后 `62b6cd2` 的独立干净导出自行构建前端，编译、1141项全量测试（0F/0E/1S）和47项专项通过；另一次离线E2E、完整报告及正文哈希仍与录制相同，质量验收 FAIL。初次未构建前端时首页测试因无静态资源失败，补齐同源构建产物后全量复跑通过。

## 后续

保留 NCS-033 为唯一 active，先用本轮样本为守卫上下文遗漏写 failing test，再修复；独立的 NCS-063/064 按单特性分支协议逐项处理。伏笔时间线与回收范围需核验。人工文学质量确认及重新验收通过前不更新 golden。本轮不再追加真实采样。

本轮正文与旧样本阅读文件：`/Users/erik/.codex/visualizations/2026/09/20/01a0bc26-4a49-7ab0-a686-e8baa470bc97/ncs033-continued-e2e/`，包含“本轮三章.md”“上轮失败样本.md”“第3章纠偏对照.md”。
