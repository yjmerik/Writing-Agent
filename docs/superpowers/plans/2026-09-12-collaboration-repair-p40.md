# Phase40 运行可靠性与完整验收 Implementation Plan

> **For agentic workers:** When implementation is authorized, select an allowed execution method: use superpowers:subagent-driven-development when delegation is permitted and useful for independent tasks; otherwise use superpowers:executing-plans. Steps use checkbox (`- [ ]`) syntax for tracking. A plan-only request does not authorize implementation.

**Goal:** 完成运行可靠性与完整验收的可验证修复，不扩展解释已有切片的完成记录。

**Architecture:** 沿用 Spring Boot 编排、SPI 持久化和 React UI。以明确稿件身份和持久审计连接各操作，后端事务保证一致性，浏览器回归验证作者路径。

**Tech Stack:** Java 17 / Spring Boot 3 / SQLite / React 18 / TypeScript / Ant Design / JUnit + MockMvc / Playwright（新增独立 QA 包）。

## 全局执行约束

- 本文件是设计，不代表业务已实现。执行业务修复需用户授权；本轮只登记 not-started。
- 从仓库根运行；完整入口与契约见 [执行入口](2026-09-12-collaboration-repair-entry.md)，规范见 [总规格](../../specs/2026-09-12-collaboration-repair-program.md)。
- 每次只选一个依赖已在 main 完成的 feature，新建 `codex/<feature-id>-<slug>`。编号不代表执行顺序，以 dependencies 为准。
- 下列每项列出 1–3 个计划实现/测试文件；状态文件属于必要交接。若现有测试适配导致实际范围超过3文件，先将超出部分拆成有独立验收的子项并更新依赖，禁止无记录扩大范围。不得用删测试、skip、减弱断言凑绿。
- bug 必须先 RED，再修改实现；用真实行为测试，不能以字符串存在代替。RED 必须是目标行为失败，不是编译失败、依赖缺失、Mockito/端口环境失败。
- 每项必须 `mvn compile && mvn test`、本项 verify_cmd；改前端再 `npm --prefix frontend run build`。合并 main 后在干净检出重跑，验证通过后才 completed 并写 verified 记录。
- 业务层只依赖 SPI；同事务必须同连接，不能在外层事务调用自己另开连接的 public 仓储方法；SQLite 单进程，不用多实例共享一个实际作者库。
- 修改正文/批准/候选请求分别有身份比较；后端缺版本即拒绝。客户端不能以“最新版本”替换作者未读的版本后自动批准。
- 模型与外部调用不在 SQLite 写事务内；提交后派生任务携原稿身份。只读失败不得显示空列表冒充无问题。
- 影响生成上下文/Agent/prompt 的项须执行原项目质量门禁；新语料记录、两次回放、质量比较、人工确认后 bless，不把此设计当作真实模型调用或 bless 的新授权。

<a id="p40-01"></a>
### p40-01 — 不可取消慢模型与接手晚写入专项

- **类型 / 来源**：feature；原报告未测慢任务/重启
- **依赖**：p36-07, p37-03
- **文件范围**：
  - 新增 `src/test/java/com/writing/novel/service/TakeoverLateCommitTest.java`
  - 新增 `src/test/java/com/writing/novel/service/ProposalRestartRecoveryTest.java`

**输入/输出契约**：使用CountDownLatch控制本地StubLlmGateway返回，不sleep赌时序；模拟stop请求、旧worker、作者保存、旧结果晚到、服务重启候选恢复。

- [ ] **1. 复现/契约测试**：旧结果绝不覆盖作者新稿；旧任务释放不能释放新owner；重启后READY可恢复，APPLIED不重复；报告各断点期望数据库最终状态。
- [ ] **2. 运行 RED**：执行下列 verify_cmd，保存失败断言与起始 commit。已有业务缺陷必须在未改实现前看到对应行为失败；新接口可先提交最小编译契约再验证其行为失败，不将编译错误计 RED。

```bash
mvn test -Dtest=TakeoverLateCommitTest,ProposalRestartRecoveryTest
```

- [ ] **3. 最小实现**：只写故障注入测试与可重放证据；当前安全策略不可取消模型时保持工作权并说明等待，不能提前允许编辑却接受旧worker覆盖。若复现新缺陷先登记bug并独立修复，不在测试特性夹带未知业务修改。
- [ ] **4. GREEN 与回归**：再次执行同一 verify_cmd，逐项核对上面的失败场景变为预期结果；运行全量编译/测试。本项不得顺带改变 prompt 或质量基线。
- [ ] **5. 独立提交和合并**：按 AGENTS.md 明确暂存文件、检查 staged diff、提交信息含 `p40-01`，检查 `main..HEAD` 仅含本项；本地合并后重跑验证，再登记 verified 完成证据。未得到实现授权时停在计划，不能替外部工具预记完成。

**机器验收结果**：verify_cmd 退出0，并满足：旧结果绝不覆盖作者新稿；旧任务释放不能释放新owner；重启后READY可恢复，APPLIED不重复；报告各断点期望数据库最终状态。

**执行检查表输入**（这是计划契约，尚不是实现或测试证据）：

```json
{
  "feature": "p40-01",
  "expected_exit": 0,
  "assertions": "旧结果绝不覆盖作者新稿；旧任务释放不能释放新owner；重启后READY可恢复，APPLIED不重复；报告各断点期望数据库最终状态。",
  "failure_is_not_success": true
}
```

<a id="p40-02"></a>
### p40-02 — 运行详情端点说明实际步骤与失败位置

- **类型 / 来源**：feature；运行详情与失败定位
- **依赖**：无；可从当前 main 启动
- **文件范围**：
  - 新增 `src/main/java/com/writing/novel/web/RunDetailsController.java`
  - 新增 `src/main/java/com/writing/novel/service/RunDetailsService.java`
  - 新增 `src/test/java/com/writing/novel/web/RunDetailsApiTest.java`

**输入/输出契约**：GET /api/projects/{id}/run-details?taskId= 返回taskId/status/currentStep/startedAt/elapsedMs/steps[]/lastError/eventCoverage；仅提供已存在事件证据。

- [ ] **1. 复现/契约测试**：构思模型调用前NPE也能从任务失败状态显示；阶段运行/重试/失败/成功区别；无事件覆盖UNKNOWN；错误脱敏断言。
- [ ] **2. 运行 RED**：执行下列 verify_cmd，保存失败断言与起始 commit。已有业务缺陷必须在未改实现前看到对应行为失败；新接口可先提交最小编译契约再验证其行为失败，不将编译错误计 RED。

```bash
mvn test -Dtest=RunDetailsApiTest
```

- [ ] **3. 最小实现**：读取现有任务与EventLog事件投影；无事件返回UNKNOWN并显式覆盖不足，不能空列表视为成功。耗时按同任务起止配对，重试attempt独立；不返回prompt、正文、密钥或provider原始异常。
- [ ] **4. GREEN 与回归**：再次执行同一 verify_cmd，逐项核对上面的失败场景变为预期结果；运行全量编译/测试。本项不得顺带改变 prompt 或质量基线。
- [ ] **5. 独立提交和合并**：按 AGENTS.md 明确暂存文件、检查 staged diff、提交信息含 `p40-02`，检查 `main..HEAD` 仅含本项；本地合并后重跑验证，再登记 verified 完成证据。未得到实现授权时停在计划，不能替外部工具预记完成。

**机器验收结果**：verify_cmd 退出0，并满足：构思模型调用前NPE也能从任务失败状态显示；阶段运行/重试/失败/成功区别；无事件覆盖UNKNOWN；错误脱敏断言。

**执行检查表输入**（这是计划契约，尚不是实现或测试证据）：

```json
{
  "feature": "p40-02",
  "expected_exit": 0,
  "assertions": "构思模型调用前NPE也能从任务失败状态显示；阶段运行/重试/失败/成功区别；无事件覆盖UNKNOWN；错误脱敏断言。",
  "failure_is_not_success": true
}
```

<a id="p40-03"></a>
### p40-03 — 运行进度、重试与作者术语界面

- **类型 / 来源**：feature；运行状态与错误恢复
- **依赖**：p40-02, p36-01
- **文件范围**：
  - 修改 `frontend/src/api/client.ts`
  - 修改 `frontend/src/components/CollaborationBar.tsx`
  - 新增 `qa/collaboration/run-details.spec.ts`

**输入/输出契约**：状态栏展开运行详情显示真实步骤/耗时/错误原因/可用动作；失败重试是明确用户操作，不能后台反复消耗模型。

- [ ] **1. 复现/契约测试**：断网/恢复、失败重试、不可取消任务等待、无事件四场景可操作；没有状态无依据地跳成功；390px及键盘可读。
- [ ] **2. 运行 RED**：执行下列 verify_cmd，保存失败断言与起始 commit。已有业务缺陷必须在未改实现前看到对应行为失败；新接口可先提交最小编译契约再验证其行为失败，不将编译错误计 RED。

```bash
npm --prefix qa/collaboration test -- run-details.spec.ts
```

- [ ] **3. 最小实现**：刷新失败保留旧数据并标更新时间/重试；当前步骤未知如实显示；接手等待写明不可取消请求尚未结束。审核节奏、Agent等内部术语转换为作者语言并保留必要技术详情折叠。
- [ ] **4. GREEN 与回归**：再次执行同一 verify_cmd，逐项核对上面的失败场景变为预期结果；运行全量编译/测试与前端构建。本项不得顺带改变 prompt 或质量基线。
- [ ] **5. 独立提交和合并**：按 AGENTS.md 明确暂存文件、检查 staged diff、提交信息含 `p40-03`，检查 `main..HEAD` 仅含本项；本地合并后重跑验证，再登记 verified 完成证据。未得到实现授权时停在计划，不能替外部工具预记完成。

**机器验收结果**：verify_cmd 退出0，并满足：断网/恢复、失败重试、不可取消任务等待、无事件四场景可操作；没有状态无依据地跳成功；390px及键盘可读。

**执行检查表输入**（这是计划契约，尚不是实现或测试证据）：

```json
{
  "feature": "p40-03",
  "expected_exit": 0,
  "assertions": "断网/恢复、失败重试、不可取消任务等待、无事件四场景可操作；没有状态无依据地跳成功；390px及键盘可读。",
  "failure_is_not_success": true
}
```

<a id="p40-04"></a>
### p40-04 — 两题材盲评与作者负担实验协议

- **类型 / 来源**：feature；P4文学实验未验证
- **依赖**：无；可从当前 main 启动
- **文件范围**：
  - 新增 `docs/experiments/collaboration-quality-protocol.md`
  - 新增 `scripts/evaluate-collaboration-study.py`
  - 新增 `scripts/test_evaluate_collaboration_study.py`

**输入/输出契约**：实验CSV固定字段pairId/chapterIndex/genre/conditionBlind/reviewerId/dimension/score/reviewSeconds/actions/issuePrecision/seriousContinuityErrors；模型配置与baselineCommit另存脱敏manifest，映射揭盲前冻结。

- [ ] **1. 复现/契约测试**：脚本对缺章/少题材/重复评委/越界分数拒绝给PASS；合成CSV只验证计算，不能当文学结论。预注册目标：质量均差不低于-0.2、审阅时间中位数下降≥20%、严重连续性错误不增加，另展示CI不隐去不确定性。
- [ ] **2. 运行 RED**：执行下列 verify_cmd，保存失败断言与起始 commit。已有业务缺陷必须在未改实现前看到对应行为失败；新接口可先提交最小编译契约再验证其行为失败，不将编译错误计 RED。

```bash
python3 scripts/test_evaluate_collaboration_study.py
```

- [ ] **3. 最小实现**：两题材各20章，基线与修复稿成对随机编号；至少3位独立读者，1-5分评价连贯性/人物/伏笔兑现/可读性，报告配对差值、bootstrap95%CI；记录作者每千字审阅时间与动作数。缺数据给INCOMPLETE，不可用scenario或单测替代。
- [ ] **4. GREEN 与回归**：再次执行同一 verify_cmd，逐项核对上面的失败场景变为预期结果；运行全量编译/测试。本项不得顺带改变 prompt 或质量基线。
- [ ] **5. 独立提交和合并**：按 AGENTS.md 明确暂存文件、检查 staged diff、提交信息含 `p40-04`，检查 `main..HEAD` 仅含本项；本地合并后重跑验证，再登记 verified 完成证据。未得到实现授权时停在计划，不能替外部工具预记完成。

**机器验收结果**：verify_cmd 退出0，并满足：脚本对缺章/少题材/重复评委/越界分数拒绝给PASS；合成CSV只验证计算，不能当文学结论。预注册目标：质量均差不低于-0.2、审阅时间中位数下降≥20%、严重连续性错误不增加，另展示CI不隐去不确定性。

**执行检查表输入**（这是计划契约，尚不是实现或测试证据）：

```json
{
  "feature": "p40-04",
  "expected_exit": 0,
  "assertions": "脚本对缺章/少题材/重复评委/越界分数拒绝给PASS；合成CSV只验证计算，不能当文学结论。预注册目标：质量均差不低于-0.2、审阅时间中位数下降≥20%、严重连续性错误不增加，另展示CI不隐去不确定性。",
  "failure_is_not_success": true
}
```

<a id="p40-05"></a>
### p40-05 — 全链路独立验收与覆盖报告

- **类型 / 来源**：feature；原报告完整验收
- **依赖**：p36-06, p36-07, p36-08, p36-09, p37-13, p38-04, p38-08, p38-12, p39-05, p39-06, p40-01, p40-03, p40-04, p40-06
- **文件范围**：
  - 新增 `qa/collaboration/collaboration-acceptance.spec.ts`
  - 新增 `scripts/verify-collaboration-acceptance.sh`
  - 新增 `docs/reports/collaboration-acceptance-checklist.md`

**输入/输出契约**：验收状态逐项PASS|FAIL|NOT_RUN；输出所验commit/环境/viewport/fixture/每用例动作结果/下载hash/限制；程序通过不自动把文学实验设PASS。

- [ ] **1. 复现/契约测试**：任一关键功能未跑或失败退出非零；两窗口旧稿批准必需409；导出每章hash完全匹配manifest；所有原报告缺口映射到具体结果；文学缺证据单列INCOMPLETE。
- [ ] **2. 运行 RED**：执行下列 verify_cmd，保存失败断言与起始 commit。已有业务缺陷必须在未改实现前看到对应行为失败；新接口可先提交最小编译契约再验证其行为失败，不将编译错误计 RED。

```bash
bash scripts/verify-collaboration-acceptance.sh
```

- [ ] **3. 最小实现**：独立临时数据库和本地scenario；完整走创建→设定审阅→写章→目录草稿恢复→三意见候选→应用/确认→多窗口版本批准→事实来源→24→20→成书批准导出；重启与390px重复关键环节。逐项执行入口文档中的E2E-01…E2E-13，包括既有候选线索提升/拒绝回归，不靠源码字符串匹配判通过。
- [ ] **4. GREEN 与回归**：再次执行同一 verify_cmd，逐项核对上面的失败场景变为预期结果；运行全量编译/测试与前端构建。本项不得顺带改变 prompt 或质量基线。
- [ ] **5. 独立提交和合并**：按 AGENTS.md 明确暂存文件、检查 staged diff、提交信息含 `p40-05`，检查 `main..HEAD` 仅含本项；本地合并后重跑验证，再登记 verified 完成证据。未得到实现授权时停在计划，不能替外部工具预记完成。

**机器验收结果**：verify_cmd 退出0，并满足：任一关键功能未跑或失败退出非零；两窗口旧稿批准必需409；导出每章hash完全匹配manifest；所有原报告缺口映射到具体结果；文学缺证据单列INCOMPLETE。

**执行检查表输入**（这是计划契约，尚不是实现或测试证据）：

```json
{
  "feature": "p40-05",
  "expected_exit": 0,
  "assertions": "任一关键功能未跑或失败退出非零；两窗口旧稿批准必需409；导出每章hash完全匹配manifest；所有原报告缺口映射到具体结果；文学缺证据单列INCOMPLETE。",
  "failure_is_not_success": true
}
```

<a id="p40-06"></a>
### p40-06 — 实际执行两题材20章盲评和审阅负担实验

- **类型 / 来源**：feature；P4实际实验
- **依赖**：p40-04, p37-16, p38-08, p39-06, p40-03
- **文件范围**：
  - 新增 `docs/experiments/collaboration-study-manifest.json`
  - 新增 `docs/experiments/collaboration-study-ratings.csv`
  - 新增 `docs/reports/collaboration-study-results.md`

**输入/输出契约**：按p40-04冻结协议后运行真实基线/修复版本，匿名成对样本给3位独立读者；manifest记录两个commit、模型配置摘要、样本hash、随机种子与揭盲时间。

- [ ] **1. 复现/契约测试**：每题材两条件各20章与每章至少3份独立有效评分完整；分析脚本质量不劣阈值、耗时改善和严重错误约束均通过才PASS；失败登记新问题，不能bless覆盖。
- [ ] **2. 运行 RED**：执行下列 verify_cmd，保存失败断言与起始 commit。已有业务缺陷必须在未改实现前看到对应行为失败；新接口可先提交最小编译契约再验证其行为失败，不将编译错误计 RED。

```bash
python3 scripts/evaluate-collaboration-study.py --manifest docs/experiments/collaboration-study-manifest.json --ratings docs/experiments/collaboration-study-ratings.csv --require-complete
```

- [ ] **3. 最小实现**：先确认真实模型调用与文本发送授权；使用隔离数据库、每题材20章；盲评和作者操作计时由真实参与者完成。只提交脱敏聚合评分与材料hash，原稿留本地受控路径；禁止自动编造评分、身份或用scenario代替。缺参与者/授权/数据保持未完成。
- [ ] **4. GREEN 与回归**：再次执行同一 verify_cmd，逐项核对上面的失败场景变为预期结果；运行全量编译/测试。本项不得顺带改变 prompt 或质量基线。
- [ ] **5. 独立提交和合并**：按 AGENTS.md 明确暂存文件、检查 staged diff、提交信息含 `p40-06`，检查 `main..HEAD` 仅含本项；本地合并后重跑验证，再登记 verified 完成证据。未得到实现授权时停在计划，不能替外部工具预记完成。

**机器验收结果**：verify_cmd 退出0，并满足：每题材两条件各20章与每章至少3份独立有效评分完整；分析脚本质量不劣阈值、耗时改善和严重错误约束均通过才PASS；失败登记新问题，不能bless覆盖。

**执行检查表输入**（这是计划契约，尚不是实现或测试证据）：

```json
{
  "feature": "p40-06",
  "expected_exit": 0,
  "assertions": "每题材两条件各20章与每章至少3份独立有效评分完整；分析脚本质量不劣阈值、耗时改善和严重错误约束均通过才PASS；失败登记新问题，不能bless覆盖。",
  "failure_is_not_success": true
}
```
