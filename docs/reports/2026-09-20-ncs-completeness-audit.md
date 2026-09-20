# 原方案完整性核查

日期：2026-09-20；代码：本地 main `b89cf58`。本次为审计，未修改业务代码或完成状态。

## 结论

**不能认定 HTML/ZIP 原方案已完整实现。** NCS-001～030 均有 completed/verified 记录且实现提交归属 main，证明已登记实施任务的交付记录齐备；但原方案→实施 spec 的需求映射有遗漏，部分功能只有接口/界面，没有进入正常写作链路，还有运行边界仍沿用原方案明确要求替换的旧实现。

以下为代码核查确认的缺口，不把静态推导的故障场景冒充本次实际发生的运行事故。也不据此否定已经交付的整组正文事务、候选比较、固定版本导出等功能。

## 输入与方法

- HTML 与 ZIP 中 `novel-complete-solution/index.html` 逐字节一致；SHA256：`4f1cc628c396c4ebf406ceeebddde7b968fa934d12805177bfb7dcd1799e9f45`。
- 使用 ZIP 的统一正文 `design.md`（22章）为要求来源，核对本地实施 spec、30项 feature、完成索引、验证摘要和 T01–T11 报告，再跟踪关键业务调用链。
- 审计范围是当前实现对目标方案的覆盖，不以当前 main 与 main 的空 diff 代替审计，不套用要求先提供比较分支的 diff-review 流程。
- 未重新执行浏览器 T01–T11、真实模型实验或 E2E；已有成绩明确标为历史证据。本次编译、全量回归、前端与 harness 验证见末尾。

## 具体缺口

### A01 / P1：创作约定没有进入规划和写作链

原方案 §05、§08、§10 要求原旨、视角、语言样本与限制成为创作契约，贯穿生成和核查。现在 AuthorIntentController 的保存只追加 author_intents；该仓储的生产消费者仅此 controller，没有被 ContextAssembler、ChapterProduction、Plotter 或写作 Agent 读取。IntentView 的保存也只调用 saveIntent，未同步到旧的写作配置/指令系统。

因此作者在创作约定中更改视角或语言样本，不会通过这条数据链影响后续生成。界面还承诺“Agent 会向它校准”。已接入的 AestheticProfile 与 AuthorDirective 是另外两套数据，不能证明 AuthorIntent 已接通。

证据：[AuthorIntentController.java](/Users/erik/ZCodeProject/WritingAgent/src/main/java/com/writing/novel/web/AuthorIntentController.java:36)、[IntentView.tsx](/Users/erik/ZCodeProject/WritingAgent/frontend/src/components/IntentView.tsx:32)。对应 NCS-003/004。

### A02 / P1：取消门禁没有固定到实际执行的旧任务身份

原方案 §14 要求 ownerRunId、epoch、expiresAt，且每次提交在事务内核对原执行者的租约与基准。ChapterWriterAgent 在模型返回后重新查询“当前 RUNNING 任务”判断 epoch，没有携带发起请求时捕获的任务身份；当前没有 RUNNING 任务时直接放行。ChapterPostProcessor 也仅在整个后处理入口检查一次，其后模型调用与多阶段写入没有逐次核验。

这无法建立“旧任务晚返回永远不能写入”的保证：旧任务结束或被新任务替换后，查询结果可能为 null 或新任务。现有 WriteLeaseEpochTest 只保留旧 RUNNING 行来测试 epoch 不同，未覆盖这些边界。数据库租约表实现也只有 epoch/reason/updated_at，没有所有者和到期字段。

证据：[ChapterWriterAgent.java](/Users/erik/ZCodeProject/WritingAgent/src/main/java/com/writing/novel/agent/ChapterWriterAgent.java:279)、[ChapterPostProcessor.java](/Users/erik/ZCodeProject/WritingAgent/src/main/java/com/writing/novel/orchestrator/ChapterPostProcessor.java:120)、[WriteLeaseService.java](/Users/erik/ZCodeProject/WritingAgent/src/main/java/com/writing/novel/service/WriteLeaseService.java:43)。对应 NCS-001、R08/F18/F22；本次为代码推导，未新增并发故障测试。

### A03 / P1：纠偏候选仍在接受前替换当前向量索引

原方案 §04、§09、§14 明确要求候选隔离、先建新投影再切换，拒绝候选和 embedding 失败不能污染当前索引。rewriteForCorrection 在返回候选正文之前调用 reindexChapter；indexChapterInChunks 先 removeByIdPrefix 删除旧块，随后才生成并写新块。拒收后再重建旧索引的补偿代码仍在，异常还会被吞掉。

这正是原审查 F05 要消除的路径，不能认为新增修订工作区已覆盖所有旧入口。可能存在候选提前可检索、失败后索引空缺和补偿失败等窗口。

证据：[ChapterWriterAgent.java](/Users/erik/ZCodeProject/WritingAgent/src/main/java/com/writing/novel/agent/ChapterWriterAgent.java:268)、[删除旧索引](/Users/erik/ZCodeProject/WritingAgent/src/main/java/com/writing/novel/agent/ChapterWriterAgent.java:343)、[拒收后的补偿](/Users/erik/ZCodeProject/WritingAgent/src/main/java/com/writing/novel/orchestrator/ChapterPostProcessor.java:250)。对应 F05、R06。

### A04 / P1：正文接受与故事记录就绪之间缺少持久恢复边界

原方案 §04、§09、§11、§14 要求正文、裁决语义、审阅引用与 outbox 同事务，投影按来源 manifest 重建，通过后才推进 readyHead。

现有整组正文/版本/候选/收据的事务已实现；但 GroupAcceptanceService 提交后同步逐章 refresh，没有同事务登记可恢复的派生任务。刷新失败只对 Java Chapter 对象设置 derivedRefreshPending 并写日志；没有持久化该字段的仓储路径，也没有在自动续写准入读取该字段。未找到等价的工作快照 draftHead/readyHead 分离和来源绑定就绪凭证。

因此“正文已采纳但派生记录失败/进程崩溃”尚未形成原方案要求的恢复与阻断闭环。已有 post_process_ledger 不能自动等同于修订事务的 outbox。

证据：[GroupAcceptanceService.java](/Users/erik/ZCodeProject/WritingAgent/src/main/java/com/writing/novel/service/GroupAcceptanceService.java:92)、[ChapterRevisionService.java](/Users/erik/ZCodeProject/WritingAgent/src/main/java/com/writing/novel/service/ChapterRevisionService.java:169)。对应 NCS-016、F06/F07、R06/R07/R14。

### A05 / P1：跨章修订仍是词面影响筛选，未完成依赖补查与接受门禁

原方案 §12/§21 要求语义差异、依赖闭包、隐含后果补查、未知保留并影响接受条件。当前 ImpactAnalysisService 依据主体名、实体名和已知关系扫描；没有命中的章节标 UNAFFECTED_WITH_REASON，同时理由自己声明“不证明不受影响”。RevisionRepairService 只处理 MUST_REPAIR，跳过 RECHECK；GroupAcceptanceService 接受时校验已有候选基准和保护项，未要求 RECHECK/未知项得到有效处置，也未检查必需检查覆盖闭包。

所以现有“第8章焚信→修后文”证明了列表、候选和整组事务，不证明所有依赖都修复。验收报告也明确承认合成模型普通改写仅改标题、未证明真正写出“告密失败”。

证据：[ImpactAnalysisService.java](/Users/erik/ZCodeProject/WritingAgent/src/main/java/com/writing/novel/service/ImpactAnalysisService.java:148)、[RevisionRepairService.java](/Users/erik/ZCodeProject/WritingAgent/src/main/java/com/writing/novel/service/RevisionRepairService.java:56)、[GroupAcceptanceService.java](/Users/erik/ZCodeProject/WritingAgent/src/main/java/com/writing/novel/service/GroupAcceptanceService.java:61)。对应 NCS-014～017、F01、R04。

### A06 / P1：场景合同、偏离分析、卷末回读未形成自动主线

原方案 §06/§11 及实施 spec NCS-019/020 要求每章偏离记录、卷末生成回读并在下一卷前处理。现状：场景合同生成、偏离分析、卷回读生成均通过 controller 按需触发；正常 ChapterPostProcessor 没有偏离分析调用，NovelOrchestrator 没有回读 generate 调用。卷过渡只检查已存在的 PENDING_REVIEW 报告；未生成报告时 hasPending=false，可以直接继续。

此外 VolumeReadback 只含 projectId/volumeIndex/report/status/时间，没有来源章稿集合或 manifest 绑定；报告不符合“绑定卷与稿版本”的验收描述。默认 E2E 因没有合同/档案输入而保持旧指纹，不能证明新增主线已集成。

证据：[PlanDeviationController.java](/Users/erik/ZCodeProject/WritingAgent/src/main/java/com/writing/novel/web/PlanDeviationController.java:27)、[VolumeReadbackController.java](/Users/erik/ZCodeProject/WritingAgent/src/main/java/com/writing/novel/web/VolumeReadbackController.java:36)、[卷过渡](/Users/erik/ZCodeProject/WritingAgent/src/main/java/com/writing/novel/orchestrator/NovelOrchestrator.java:2061)、[VolumeReadback.java](/Users/erik/ZCodeProject/WritingAgent/src/main/java/com/writing/novel/domain/VolumeReadback.java:17)。对应 NCS-018～021。

### A07 / P2：事实支持传播仅实现了直接来源失效

原方案 §09 要求区分 OR 来源组与 AND 推断前提组，沿依赖传播撤回、禁止循环自证，且断言具有不可变修订和快照归属。

SupportWithdrawalService 只处理修改章节直接关联的 fact_id：存在任一有效证据就保留，否则修改该事实的全局 status。未实现 AND 支持组、推导消费者传播或工作空间隔离的断言有效状态。注释“循环支持不能自证”不是这些机制存在的证据。

证据：[SupportWithdrawalService.java](/Users/erik/ZCodeProject/WritingAgent/src/main/java/com/writing/novel/service/SupportWithdrawalService.java:30)。对应 NCS-008/009、F03/F04、R03。

### A08 / P2：持久预算与预约结算没有落地

原方案 §14、F20 要求项目/Run/Step 分层预算，调用前预约，返回后结算，响应丢失保留待核费用，重启可恢复。

CostBudgetMonitor 仍用 ConcurrentHashMap 统计项目费用，返回后累加，重启清空；WritingTaskExecutor 用这个累计数判断超限。没有上述预约/结算/不确定费用账本。不能因为任务列表有费用字段，就认为 F20 已闭环。30项 feature 的拆分没有覆盖完整持久预算要求。

证据：[CostBudgetMonitor.java](/Users/erik/ZCodeProject/WritingAgent/src/main/java/com/writing/novel/telemetry/CostBudgetMonitor.java:20)、[WritingTaskExecutor.java](/Users/erik/ZCodeProject/WritingAgent/src/main/java/com/writing/novel/service/WritingTaskExecutor.java:258)。对应原方案 F20、R07；当前拆分遗漏。

### A09 / 验收缺口：原方案四层证据未齐

原方案 §20 明确要求运行、检索语义、真实模型长篇、真人阅读四层；T12、检索标注集与消融、两题材20章后扩展100–200章及百万字阶段均不能由三章 replay 替代。还要求实际作者任务、键盘/读屏/中文输入法/窄屏关键路径验证。

实施 spec 把真实长篇/真人盲评列为本轮非目标；这是实施范围缩减，不能据此说它们是“原方案非目标”。progress.md 对此的表述不准确。已有 NCS-030 报告明确只证明确定性功能旅程，且部分数据由 API 准备，部分故障交给专项测试；这些边界应保留。

证据：[实施 spec 非目标](/Users/erik/ZCodeProject/WritingAgent/docs/specs/2026-09-19-novel-complete-solution.md:114)、[Journey 证据边界](/Users/erik/ZCodeProject/WritingAgent/docs/reports/2026-09-19-ncs-journey/README.md:75)、[当前完成声明](/Users/erik/ZCodeProject/WritingAgent/progress.md:7)。

## 22章覆盖判断

这是完整性判断，不是每章全部细节已验证的证明。概览和追溯章节不单独计算“实现百分比”。

| 原方案章节 | 判断 | 主要依据 |
|---|---|---|
| 01 总架构 | 部分 | 运行/版本/就绪边界缺口 A02–04 |
| 02 J01–J11 用户动线 | 已有确定性旅程证据，非完整语义证明 | A01、A05、A06；历史 T01–T11 |
| 03 工作台 | 主要工作区已交付，全部对象定位/可用性未全面复验 | 当前 UI 与完成记录 |
| 04 保存/采纳/就绪 | 部分 | A03、A04 |
| 05 起步与意图 | 部分 | A01；指令和审美档案另有实现 |
| 06 长程叙事 | 部分 | A06 |
| 07 人物与世界 | 已有按章节知情视图与决策记录；完整多时间/快照语义未获充分证明 | NCS-010～012、历史 T06 |
| 08 Agent 合同与权限 | 部分 | A01、A02、A03 |
| 09 数据与支持链 | 部分 | A04、A07 |
| 10 上下文与模型 | 已有窗口核算和条件注入；完整契约覆盖未证明 | A01、A03、A09 |
| 11 正常写作闭环 | 部分 | A03、A04、A06 |
| 12 统一修订 | 部分 | A05 |
| 13 质量与艺术 | 有分层审阅/组合采纳/审美档案；艺术收益未验证 | A05、A09 |
| 14 持久执行与预算 | 部分 | A02、A04、A08 |
| 15 命令与统一读取 | 有聚合接口；同一快照读取保证未证明 | WorkspaceViewService 分次查询且无读取事务 |
| 16 成书与导出 | 固定 manifest 校验/批准/导出已有实现与历史证据 | NCS-027/028、T11；不等于全书质量证明 |
| 17 恢复与组件 | 多数功能状态有旅程证据；完整多设备/辅助技术验收缺证 | A09 |
| 18 迁移与兼容 | 已有兼容加列/建表；目标快照与支持链迁移仍不完整 | A04、A07 |
| 19 里程碑 | NCS 30项记录完成，不等价于全部原要求 | A01–09 |
| 20 联合验收 | 部分 | A09 |
| 21 焚信贯穿案例 | 操作与事务已演练；完整因果修复未证明 | A05 |
| 22 决策与追溯 | F05/F20 等目标仍存在缺口 | A03、A08 |

## 已确认的交付

- NCS-001～030 均为 completed，关联 verified 完成记录；harness --check-git 通过（361项，359 completed，2 deferred）。
- 已有持久候选、旧基准冲突、组合候选重新检查、整组正文事务和重复采纳收据、人物章节时点展示、单次要求版本证据、历史恢复、固定整书记录与实际下载证据。
- 这些实现值得保留；建议补集成与边界，不重做整个系统。

## 修复优先次序

1. 接通 AuthorIntent 与正常规划/写作；将场景合同、偏离分析、卷回读接入持久任务流程。
2. 固定实际执行者租约，隔离纠偏候选索引，完成接受事务→持久派生任务→就绪准入闭环。
3. 补依赖补查门禁、支持组和持久预算。
4. 重新建立原方案→需求→代码→专项测试→运行证据的矩阵；将 T12 等标“未验收/另期”，不能写成原方案不要求。

## 本次验证

- `python3 scripts/check-harness.py --check-git`：PASS，361项，359 completed / 2 deferred；既有历史证据限制产生一条 WARN。
- Java 21 下 `./init.sh`：PASS，包含 `mvn compile`、`mvn test`、harness 检查与前端构建；1093 tests / 0 failures / 0 errors / 1 skipped。
- 独立 `npm --prefix frontend run build`：PASS；既有大包警告仍在。
- 首次直接 Maven 被环境选择为 Java 26 且未加载配置，出现397个环境错误；随后 Java 21 沙箱测试因无法绑定本机端口失败，结束该进程后在允许本机测试服务的环境完成上述通过复验。未把这些环境错误记成业务回归。
- 最终验证日志：`/tmp/ncs-audit-init-unrestricted.log`；harness 日志：`/tmp/ncs-audit-harness.log`。
- 本次没有重跑浏览器旅程、E2E 或真实模型长篇评测；报告引用它们时均为仓库已有证据。
- 全量测试通过仅证明已有断言通过，未覆盖的 A01–A09 不能因此算完成。
- 未改生产代码、未新增 backlog 或提交；原工作区两个未跟踪路径原样保留。
