# Spec: 第二轮武侠旅程（《沉钟记》）缺陷修复

> 来源：docs/reports/2026-09-25-ui-wuxia-journey-2/体验报告.md（2026-09-25/26 ego-browser 全流程旅程）。
> 现场实例：localhost:18100（data/ui-wuxia-0925-journey，《沉钟记》停在 NEEDS_REVISION 死锁现场，保留作人工验收用）。

## 背景与动机

第二轮 UI 全流程回归验证了 NCS-077..086 的修复（宣告完结、常驻 Alert、过期 HIGH 不阻断、回收卷确认制等全部生效），但暴露出新的问题，其中最严重的是**终局死锁变体（A-8）**：一本 12 章、全部作者批准、55,750 字的成书无法通过任何 UI 路径到达 FINAL。三个机制叠加：

1. 成书门禁硬条件 `unknownCount > 0` 即 FAIL（必查检查"尚未查清"）；
2. 「重新检查」只重跑 continuity 检查器，清不掉 consistency-guard / correction-verification 的 UNKNOWN 信封（A-2）；
3. NCS-082 追加的尾声回收卷在 NEEDS_REVISION 态被禁止写作，回收章一个字都写不了。

《沉钟记》12 章自然积累了 5 个 UNKNOWN（3×consistency-guard、2×correction-verification）+ 72 条必须回收伏笔，宣告完结与重审均 FAIL，且每个出口都被上述机制堵死。其余发现：人物层修改意见被静默丢弃（A-1）、修订预览失控长输出与超时错配（A-4）、recheck/checkpoint 遥测缺归属（A-3）、篇幅压缩不收缩承诺账本（A-5）、章节字数逐章递减无预警（A-6 之一）。

A-7（SPA 深链 500）经复验为 jar 热替换损坏 JVM 期间的环境误报，撤案（运营教训：禁止重建运行中实例引用的 fat jar，记入本 spec 非目标节的运维备注）。

## 目标

1. **任何项目都能经 UI 从"门禁失败"走到 FINAL 或明确的作者决策出口**：未查清项可重检或由作者担责豁免；回收卷追加后可立即写作。
2. **作者提交的框架修改意见不再被静默丢弃**：人物层与世界观同属 AFTER_INITIAL_SETUP 修订范围。
3. **长耗时操作（修订预览/重检）有可靠的完成反馈**：超时分层、截断 fail-fast、异步任务化。

## 非目标

- 不改 28 个 `.st` 模板的变量名与语义（NCS-090 人物层修订若需新 prompt，走新增模板文件）。
- 不改成书门禁的硬性标准本身（mustResolve>3 阻断、unknownCount>0 阻断均保留——本 spec 只补齐"让作者有能力清零/豁免"的通道）。
- 不做伏笔候选线索的自动取舍（仍由作者裁决）；不改回收卷的有界一次约束。
- 不在本轮实现工作流引擎级的通用任务系统；异步任务（NCS-094）限定 preview 与 recheck 两类。
- 运维备注（不立项）：init.sh / AGENTS.md 增补"重建 jar 前先停实例"提示，随下一个 docs 特性顺带处理。

## 缺陷根因与修复设计

### A-2 → NCS-087：recheck 按检查器路由

**根因**：`NovelOrchestrator.recheckChapter`（NovelOrchestrator.java:3171）只调 `continuityChecker.check`；而 UNKNOWN 信封由三方写入：`ContinuityCheckerAgent`（continuity）、`ConsistencyGuard.check`（consistency-guard）、`ConsistencyGuard.verifyCorrections`（correction-verification）。信封按 (chapter, checker) 取最新行（CheckResultRepository），重检 continuity 永远覆盖不了别的键。

**设计**：
- `POST /chapters/{n}/recheck` body 增加可选 `checkers: ["continuity"|"consistency-guard"]`，缺省 `["continuity"]`（向后兼容）。
- `recheckChapter` 按 checker 路由：
  - `continuity` → 现行逻辑（ContinuityCheckerAgent）；
  - `consistency-guard` → `ConsistencyGuard.check(projectId, n, content, cast)`（cast 取本章场景合同/大纲人物，可为 null——guard 内部有主角保底）；异常时 guard 自身会落 UNKNOWN 信封并抛错，前端如实提示，不伪装成功；
  - `correction-verification` → **不支持重跑**（复验需要当时的 originalHigh findings，不可重建），返回 409 明示"该类未查清请经作者担责豁免（NCS-088）"。
- identity 校验、正文变更丢弃逻辑对所有 checker 复用。
- 前端 `ReviewClassificationView`：UNKNOWN_CHECK 卡片按 `c.checker` 传参；correction-verification 卡片不显示「重新检查」，改显示「担责豁免」（NCS-088 的按钮）。
- 顺带修旅程附证缺陷：首次点击无服务端动作且前端永远「重检中」——重检请求改为显式 loading 态绑定响应 promise（catch 中恢复），与 NCS-093 的超时分层同文件处理。

### A-8① → NCS-088：未查清信封的作者担责豁免（AUTHOR_WAIVED）

**根因**：版本化批准语义是"作者已读并担责放行"（屏障按钮文案即「确认已复核并批准」），但只豁免了 HIGH 问题（裁决台账），不覆盖未查清信封；信封无任何作者出口。

**设计**：
- `CheckResultEnvelope.Status` 新增 `AUTHOR_WAIVED`；`unknownCount`/`ReviewClassificationService`/门禁对 AUTHOR_WAIVED 天然不计（latestStatuses 取最新行，新行覆盖旧 UNKNOWN）。
- **批准时自动豁免**：`ReviewDecisionController` 在 `repository.approveChapter` 成功后（含幂等恢复路径），对该章每个最新状态为 UNKNOWN/INVALID 的信封追加一行 `AUTHOR_WAIVED`，reason 记 `approvalId=<id> 作者确认已复核并批准（担责放行）`，content_fingerprint 绑定批准的稿身份。
- **手动豁免（存量项目出口）**：新端点 `POST /chapters/{n}/check-waiver`，body `{identity, checker, reason}`（reason 必填，同裁决台账约束）；写入 AUTHOR_WAIVED 行。前端 UNKNOWN_CHECK 卡片增加「作为创作例外保留（担责）」按钮（correction-verification 卡片的主出口；consistency-guard 卡片与「重新检查」并列）。
- 审计：豁免行不可变追加，reason 强制带理由/批准号，成书审查页校验报告把 AUTHOR_WAIVED 显示为「作者担责豁免」而非「已检查」。

### A-8② → NCS-089：追加回收卷解除 NEEDS_REVISION 禁写

**根因**：`ProjectActionPolicy` 中 NEEDS_REVISION 只允许 FINAL_AUDIT/FINISH；`appendForeshadowingRecoveryVolume` 追加大纲后不改状态——作者被引导追加回收卷，却没有写作权限。

**设计**：
- `appendForeshadowingRecoveryVolumeInternal` 成功追加且 `state == NEEDS_REVISION` 时置 `WRITING`（发事件 `RECOVERY_RESUMED`）。WRITING + 有大纲 + 无待审检查点 → WRITE_CHAPTER/WRITE_ALL 自动可用；协作「继续协作」即可写第 13 章起。
- 前端 RunConsole 回收卷确认弹窗文案补一句「追加后将回到写作态，可继续写作回收章」。
- 不改 VOLUME_DONE 路径（写作循环入口已有 WRITING 转换）。

### A-1 → NCS-090：框架审阅修订纳入人物层

**根因**：`reviseInitialSetup`（NovelOrchestrator.java:848）只调 `worldBuilder.propose`→`replaceEntries`；CharacterDesigner 不在路径上；UI 未提示范围。

**设计**：
- `reviseInitialSetup` 在世界观重写后增加**人物层同步**（确定性规则 + LLM 设计，不靠模型自觉）：
  1. 解析新大纲全部章纲文本，得到"章纲出场名集合"（现有 8 张卡姓名 + 卷梗概列出的新配角名做字符串匹配）；
  2. **补卡**：章纲出场但无卡的名字 → 逐个 `CharacterDesignerAgent.design`（requirement 由修改意见 + 该名字在各章纲的出现句拼接），新卡落库；
  3. **撤废卡**：有卡但章纲与卷梗概零出场的名字 → 标记 retired（不物理删，保留审计）；确认与修改意见冲突时以意见明示名单优先；
  4. 修订结果 `CheckpointRevisionResult` 记录人物层 before/after 摘要（卡数、增、撤），前端审阅卡可见。
- 幂等与失败语义：人物层失败不回滚世界观（分别独立提交），结果里如实标注部分成功；修订为空（无增无撤）不视为失败。
- 新增 prompt `character-sync-revise.st`（若 design 现有模板变量不够用），不改既有模板变量名。

### A-3 → NCS-091：recheck / checkpoint 修订遥测归属

**设计**：照 NCS-086 模式——`CheckpointController.withRevisionWork` 与 `ReviewDecisionController.recheck` 入口 `EventContext.setProjectId(projectId)`，finally `clear()`。新测试镜像 `RevisionTelemetryProjectIdTest`（录制型 EventLog 断言两条路径的 LLM_CALL 携带 projectId）。

### A-5 → NCS-092：压缩确认强制处置"无回收位的关键承诺"

**根因**：`PlanEditionService.analyze()`（PlanEditionService.java:253-264）只把 `expectedResolveChapter > newTarget` 的承诺列为受影响；旅程中 8 条 HIGH"必须回收"承诺的回收章号只写在 description（expectedResolveChapter=null），全部漏网——确认无处置即生效，账本与篇幅脱钩，债务延迟到成书门禁才引爆（72 条必须回收）。

**设计**：受影响判定扩为——未回收 && `(expectedResolveChapter != null && > newTarget)`（既有）`|| ((mustResolve || priority==HIGH) && (expectedResolveChapter == null || expectedResolveChapter > newTarget))`。命中者必须逐项 MIGRATE（目标 ≤ 新目标）或 OPEN_END（理由必填），现有校验管线无需改动。前端影响预览列表自然展示新增受影响项（NCS-085 已保证重复预览不清处置）。

### A-4 一期 → NCS-093：超时分层 + 截断 fail-fast

**设计**：
- `client.ts` 调用点显式超时：`recheckChapter`、agent 修订 preview/apply 由默认 300s 提至 900s（`request` 已支持 timeoutMs 参数）。
- `LlmResponse` 暴露 `truncated` 标志（OpenAiCompatibleClient 流式读头部 `finish_reason=length` 时置位，与既有 LLM_TRUNCATED 事件同源）；`ContentRevisionAgent` 收到 truncated 或 JSON 解析失败时抛出带行动指引的错误（"输出被截断：请缩小修改范围或分段修改"），不再把 52k token 的失控输出拖到 9 分钟才报模糊解析错误。
- 前端弹窗错误文案透传该指引。

### A-4 二期 → NCS-094：preview/recheck 异步任务化

**设计**：新增 `LongTaskService`（内存 job 注册表：`submit(projectId, kind, label, supplier)` → jobId；状态 RUNNING/DONE/FAILED + 结果/错误；job 内部 `writingTaskExecutor.beginWork/endWork` 占工作权，完成后可经协作栏取消释放）：
- `POST /agent-revisions/preview-async` → `{jobId}`；`POST /chapters/{n}/recheck-async`（body 同步版 + checkers）→ `{jobId}`；`GET /projects/{id}/long-tasks/{jobId}` 轮询（3s 间隔）。
- 同步端点保留（脚本/测试兼容），前端 AgentRevisionDialog 与 ReviewClassificationView 切换到异步 + 轮询，天然解决前端 5 分钟超时与代理掐断（浏览器请求秒回）。
- job 内存态重启即失——重启后前端轮询 404 时提示重试，不阻塞。

### A-6 → NCS-095（P3）：章节字数趋势预警

**设计**：审阅卡与章节质量报告增加只读提示——本章字数 < 前 3 章均值 50% 时显示「篇幅骤降」标签（后端 `WorkspaceViewService`/质量报告计算，前端展示）。不阻断、不自动干预。

## 验收标准

- [ ] `mvn compile && mvn test` 全绿（各 feature 先写复现 failing test 再修——Bugfix gate）
- [ ] NCS-087：`mvn test -Dtest=RecheckCheckerRoutingTest`——recheck(checkers=["consistency-guard"]) 调用 ConsistencyGuard 且信封落 consistency-guard 键；correction-verification 返回 409 指引豁免
- [ ] NCS-088：`mvn test -Dtest=AuthorWaiverCheckEnvelopeTest`——版本化批准后该章 UNKNOWN 信封最新行变为 AUTHOR_WAIVED 且 unknownCount=0；check-waiver 端点 reason 必填；门禁对豁免后项目不再因 unknown FAIL
- [ ] NCS-089：`mvn test -Dtest=RecoveryVolumeResumesWritingTest`——NEEDS_REVISION 下追加回收卷后 state=WRITING 且 WRITE_CHAPTER allowed
- [ ] NCS-090：`mvn test -Dtest=InitialSetupRevisionCharacterTest`——提交"为 X 补卡/撤 Y 卡"意见后人物集合按规则变化，结果含人物层摘要
- [ ] NCS-091：`mvn test -Dtest=RecheckTelemetryProjectIdTest`——两入口 LLM_CALL 事件携带 projectId
- [ ] NCS-092：`mvn test -Dtest=PlanCompressionPromiseLedgerTest`——mustResolve/HIGH 且无有效回收位的承诺不出处置时确认被拒；MIGRATE 后台账 expectedResolveChapter 更新
- [ ] NCS-093：`mvn test -Dtest=RevisionPreviewTruncationGuardTest`——truncated 响应抛行动指引错误；前端 build 通过
- [ ] NCS-094：`mvn test -Dtest=LongRunningTaskJobTest` + `cd frontend && npm run build`——异步 job 生命周期/工作权占用/轮询端点；前端切换后 build 通过
- [ ] NCS-095：`mvn test -Dtest=ChapterWordTrendWarningTest`
- [ ] **人工验收（用保留现场）**：在 18100《沉钟记》项目上：重检 3 个 consistency-guard 未查清 → 担责豁免 2 个 correction-verification → 追加回收卷已存在的场景改走"继续协作写回收章"（NCS-089 解禁）→ 逐章回收 mustResolve 至 ≤3 → 重审通过 → FINAL。全程仅用 UI 操作。

## 影响面

- **涉及模块**: orchestrator（recheckChapter/reviseInitialSetup/回收卷）、web（ReviewDecision/Checkpoint/Orchestrator controller）、agent（CheckResultEnvelope/ConsistencyGuard/ContentRevisionAgent/CharacterDesignerAgent）、service（PlanEditionService/AgentRevisionService/新增 LongTaskService/CheckWaiverService）、persistence（CheckResultRepository 无 schema 变更——AUTHOR_WAIVED 只是 status 枚举新值）、frontend（ReviewClassificationView/AgentRevisionDialog/RunConsole/审阅卡）
- **涉及 prompt 模板**: 新增 character-sync-revise.st（NCS-090，如需要）；既有模板变量名零改动
- **数据库 schema 变更**: 无（check_results.status 为文本列，AUTHOR_WAIVED 直接可存）
- **质量基线影响**: NCS-087..089、091..095 不改注入上下文与生成 prompt，不需 bless；NCS-090 若新增人物修订 prompt 且影响构思期产物，需跑 `./scripts/e2e.sh` + `compare-baseline.py` 对比（不注入正文上下文则仅记录新旧对比）

## Feature 拆分

| id | name | 依赖 | priority | acceptance（摘要） | verify_cmd |
|----|------|------|----------|--------------------|------------|
| NCS-087 | recheck 按检查器路由 | [] | P1 | recheck 支持指定 checker 并正确路由/落信封；correction-verification 明示走豁免 | `mvn test -Dtest=RecheckCheckerRoutingTest` |
| NCS-088 | 未查清信封作者担责豁免 | [] | P0 | 批准自动豁免 + 手动豁免端点 + 前端按钮；unknownCount 清零路径成立 | `mvn test -Dtest=AuthorWaiverCheckEnvelopeTest` |
| NCS-089 | 回收卷追加解除禁写 | [] | P0 | NEEDS_REVISION 追加回收卷后回到 WRITING，写作动作可用 | `mvn test -Dtest=RecoveryVolumeResumesWritingTest` |
| NCS-090 | 框架审阅修订纳入人物层 | [] | P1 | 人物卡按章纲同步（补卡/撤卡），修订结果含人物层摘要 | `mvn test -Dtest=InitialSetupRevisionCharacterTest` |
| NCS-091 | recheck/checkpoint 遥测归属 | [] | P2 | 两条入口 LLM_CALL 携带 projectId | `mvn test -Dtest=RecheckTelemetryProjectIdTest` |
| NCS-092 | 压缩强制处置关键承诺 | [] | P2 | 无回收位的 mustResolve/HIGH 承诺必须 MIGRATE/OPEN_END 才能确认 | `mvn test -Dtest=PlanCompressionPromiseLedgerTest` |
| NCS-093 | 修订预览超时分层+截断守护 | [] | P1 | 前端调用点 900s；truncated 抛行动指引错误 | `mvn test -Dtest=RevisionPreviewTruncationGuardTest` |
| NCS-094 | preview/recheck 异步任务化 | [NCS-093] | P1 | 异步 job + 轮询端点 + 前端切换，同步端点保留 | `mvn test -Dtest=LongRunningTaskJobTest && cd frontend && npm run build` |
| NCS-095 | 章节字数趋势预警 | [] | P3 | 篇幅骤降标签出现在审阅卡/质量报告 | `mvn test -Dtest=ChapterWordTrendWarningTest` |

实施顺序建议：NCS-088 → 087 → 089（三者合成 A-8 解锁，先恢复"能到 FINAL"），随后 093 → 094（作者操作体验），090 / 091 / 092 / 095 穿插。**同一时间只推进一个 feature**（AGENTS.md 工作规则）。

## 风险与备选方案

- **批准自动豁免的语义风险**：作者可能未意识到批准即豁免未查清项。缓解：豁免行 reason 与审阅卡/校验报告可见展示"作者担责豁免"；备选（被否决）：仅手动豁免——但存量已批章节将永久卡门禁，且屏障按钮文案本就是担责语义。
- **NCS-090 撤废卡误伤**：零出场判定基于章纲字符串匹配，人名异写（"柳三爷/柳拂水之父"）可能误判。缓解：撤卡名单写入修订结果供作者复核；被否决方案：全自动重写人物层（成本高、不可控）。
- **NCS-094 内存 job 重启丢失**：接受（重试提示）；被否决：落库 job 表——preview/recheck 均为分钟级操作，持久化收益低。
- **NCS-092 收紧后老项目压缩变繁**：受影响项多时逐项处置成本高。缓解：前端 MIGRATE 默认值可批量填"新目标末章"；仍拒绝则维持现状的账本脱钩（已知更糟）。
- **回滚**：全部 feature 独立分支独立合并；AUTHOR_WAIVED 行为追加式，回滚代码后旧信封仍按 UNKNOWN 计（回到现状，无数据损坏）。
