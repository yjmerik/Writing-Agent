# Spec: 原方案未完成能力补实施

## 背景与动机

用户于2026-09-20要求“对照方案，继续未完成的部分，实现方案设计”。设计输入沿用2026-09-15完整方案v2.0的22章HTML/ZIP；用户对原方案的实施授权不改变设计。审计见 docs/reports/2026-09-20-ncs-completeness-audit.md。NCS-001..030的历史完成证据保留，但不再等同于原方案全部交付。

## 目标

1. 约定真正进入构思、规划、写作、修订、审校；场景合同、偏离分析和卷回读进入正常流程。
2. 实现原执行者租约、候选/投影隔离、持久outbox和就绪快照、依赖补查与事实支持传播、预算预约结算。
3. 原方案运行、检索语义、真实模型、真人阅读四层证据分开登记；未完成不得以非目标或合成数据替代。

## 非目标

不新增原方案以外的业务；不拆微服务、不改技术栈、不推送远端或发布。真人评阅需要真实作者参与，不能由Agent捏造；仍保留为待验收项目。既有p40-05/06不自动冒充本轮完成。

## 验收标准

- 各项先失败后修复，专属命令通过；逐项独立分支、提交、合main、干净检出compile/test复验、完成回执。
- 改写作上下文/Agent逻辑时重跑E2E；改变注入prompt后record建立匹配语料、连续两次replay、展示质量对比，经人工确认再bless。
- 原方案R01–R18、T01–T12均有明确证据或诚实未验收状态。

## 影响面

涉及SPI、上下文、orchestrator、Agent、SQLite、投影、预算与相应UI。SQL使用兼容迁移；不破坏旧正文、候选、证据和固定导出。
创作约定采用外置author-intent.st渲染，版本与原始值完整保留；空约定保持兼容；预算不足明确阻断，不截掉约束。自由文本自主程度不能提高已有委托权限。
恢复与写入采用原方案单体SQLite事务；正文已保存但派生失败时可读稿、不可偷偷用旧依据续写。

## Feature 拆分

每项1–3个实现/测试文件，必要状态与证据文件不计业务范围；若实现检查发现需更多文件，先进一步拆分，不用单一大提交规避协议。

| id | name | 依赖 | acceptance | verify_cmd |
|---|---|---|---|---|
| NCS-031 | 创作约定运行时读取 | NCS-003 | SPI读取空约定、最新版本与项目隔离；重建仓储后仍读取相同版本 | `mvn test -Dtest=IntentRuntimeReadTest` |
| NCS-032 | 版本化创作约定渲染 | NCS-031 | 外置模板完整渲染八字段与修订号；空约定不追加；预算不足阻塞且不截断 | `mvn test -Dtest=AuthorIntentContextTest` |
| NCS-033 | 写作上下文接入创作约定 | NCS-032 | 作者修改视角和样本后实际写作请求包含新版本；旧缓存不污染；空契约兼容 | `mvn test -Dtest=AuthorIntentWritingTest` |
| NCS-034 | 规划与构思接入创作约定 | NCS-032 | 世界人物和全书卷计划请求带精确契约版本及内容 | `mvn test -Dtest=AuthorIntentPlanningTest` |
| NCS-035 | 修订审校接入创作约定 | NCS-032 | 生成修订和核查遵从约定并记录所用修订；不以自由文本提升委托权限 | `mvn test -Dtest=AuthorIntentRevisionTest` |
| NCS-036 | 固定执行任务租约身份 | NCS-001 | 模型调用期间取消并结束旧任务或启新任务，旧结果仍被拒 | `mvn test -Dtest=WriteLeaseIdentityTest` |
| NCS-037 | 持久租约所有者与有效期 | NCS-036 | owner和到期时间跨重启有效；原子校验失效租约不得写入 | `mvn test -Dtest=WriteLeaseOwnershipTest` |
| NCS-038 | 后处理逐阶段租约门禁 | NCS-037 | 后处理模型晚返回不写真典、摘要或投影；不能借用新任务身份 | `mvn test -Dtest=PostProcessLeaseTest` |
| NCS-039 | 纠偏候选索引隔离 | NCS-001 | 拒收候选不改变当前检索；接受后才建索引 | `mvn test -Dtest=CorrectionIndexIsolationTest` |
| NCS-040 | 向量索引先构建后替换 | NCS-039 | embedding失败时原索引完整；成功后原子替换匹配来源的投影 | `mvn test -Dtest=AtomicVectorProjectionTest` |
| NCS-041 | 派生任务持久模型与迁移 | NCS-016 | 来源章稿与投影类型唯一；重启保持待执行项；旧数据迁移可重复 | `mvn test -Dtest=ProjectionOutboxTest` |
| NCS-042 | 正文提交原子登记派生任务 | NCS-041 | 单章和整组事务同时写正文及outbox；任一失败一起回滚 | `mvn test -Dtest=RevisionOutboxCommitTest` |
| NCS-043 | 派生任务重试与就绪证书 | NCS-042 | 失败持久待重试；旧来源不得完成新稿；重启能重建全部必需投影 | `mvn test -Dtest=ProjectionReadinessTest` |
| NCS-044 | 快照就绪阻断续写与读取 | NCS-043 | 正文领先就绪时阻断受影响续写；工作台返回同源身份和原因 | `mvn test -Dtest=ReadySnapshotAdmissionTest` |
| NCS-045 | 影响证据与未知持久化 | NCS-014 | 未命中归未知而非无影响；分析绑定基准版本并持久保存检查范围 | `mvn test -Dtest=ImpactEvidenceTest` |
| NCS-046 | 依赖补查与修复闭包 | NCS-045 | 隐含后果和关系链进入补查；修复产生新影响时扩展闭包，预算停止保留未知 | `mvn test -Dtest=ImpactClosureTest` |
| NCS-047 | 整组采纳检查覆盖门禁 | NCS-046 | 缺必需检查或未处置UNKNOWN禁止接受；报告绑定精确候选hash | `mvn test -Dtest=GroupCoverageGateTest` |
| NCS-048 | 正常规划生成场景合同 | NCS-018 | 近期规划完成前生成合同；无效输出不伪装就绪；重复调度不重建有效合同 | `mvn test -Dtest=SceneContractPipelineTest` |
| NCS-049 | 章后自动偏离分析 | NCS-019 | 章后自动分析且结果绑定版本；失败可恢复；旧分析不覆盖新稿 | `mvn test -Dtest=PlanDeviationPipelineTest` |
| NCS-050 | 卷末报告来源版本绑定 | NCS-020 | 回读绑定全部来源章稿；改任一来源使旧批准失效 | `mvn test -Dtest=VolumeReadbackManifestTest` |
| NCS-051 | 卷末自动回读和准入 | NCS-050 | 到卷末自动生成回读；缺失失败或未确认均不得自动进入下卷 | `mvn test -Dtest=VolumeReadbackPipelineTest` |
| NCS-052 | 事实支持组与断言修订存储 | NCS-009 | 保存AND前提OR来源及快照归属；支持历史不可变 | `mvn test -Dtest=AssertionSupportGroupTest` |
| NCS-053 | 来源撤回的依赖传播 | NCS-052 | AND缺一失效OR余一保留；无外根循环不自证；传播至全部依赖者 | `mvn test -Dtest=SupportPropagationTest` |
| NCS-054 | 持久预算预约和结算 | NCS-030 | 并发预约不越上限；未知费用不清零；重启保持项目运行步骤账本 | `mvn test -Dtest=BudgetReservationTest` |
| NCS-055 | 模型调用预算准入与对账 | NCS-054 | 调用前预约返回后结算；失败未知费用保留；取消不再派发收费请求 | `mvn test -Dtest=LlmBudgetAdmissionTest` |
| NCS-056 | 原方案联合功能复验 | NCS-035,NCS-038,NCS-040,NCS-044,NCS-047,NCS-048,NCS-049,NCS-051,NCS-053,NCS-055 | R01-R18真实SQLite故障注入通过，T01-T11浏览器证据绑定当前提交，键盘中文输入法窄屏与读屏关键路径逐项记录 | `mvn test -Dtest=NcsRuntimeAcceptanceTest` |
| NCS-057 | 检索语义标注评测 | NCS-056 | 分题材标注集报告召回正确版本引用定位UNKNOWN和修订保留率，报告样本数置信区间失败例 | `mvn test -Dtest=RetrievalSemanticEvaluationTest` |
| NCS-058 | 真实长篇与真人艺术验收 | NCS-057 | 两题材20章对照后100-200章及百万字分阶段运行；收集真实读者盲评费用耗时和消融，缺真人评价不标完成 | `mvn test -Dtest=LongFormExperimentTest` |

## 风险与备选方案

不在网关根据ThreadLocal隐式注入作者约定：这会遗漏无上下文调用且污染非创作任务；使用SPI读取与明确调用链集成。不把预览候选写入正式索引；使用独立构建成功后切换。不以“没有命中”判定不受影响。运行期异常需停在可恢复状态。
真实模型与人工质量确认是交付所需证据，不能通过只测空约定让旧fixture不分叉来宣称新能力已验证。

## NCS-033 真实验收回流（2026-09-20）

用户要求先修复真实样本的质量问题。根因与修复范围见NCS-033实施计划的验收回流章节：纠偏必须含当前完整原稿和版本，缺稿/超预算拒绝请求；纠偏上下文明确区分计划目标和违规动作、当前能力和未来潜力、语言样本与故事事实。verify_cmd仍为`mvn test -Dtest=AuthorIntentWritingTest`，语义改善另以新录制和人工评阅确认，不能用单元测试代替。独立的E2E/诊断误报仅登记NCS-059/060，不夹带实施。


## NCS-033 质量验收修复（2026-09-20）

诊断证据：第二轮4470字首章10项后处理已完成；任务计数0仅因循环结束才更新，整轮由助手主动中断。旧语料回放显示纠偏/复检异常被吞，带当前稿HIGH仍推进下一章。

本特性验收修复范围：检查未知或异常须保存草稿并建立版本化人审屏障；纠偏返回明确成功/失败；重写预算耗尽仍有HIGH不得放行；原HIGH问题进入独立逐项复验（稳定编号、当前稿号、正文原文证据及理由），缺项/重复/未知/版本不符/证据不在正文均不得关闭原问题；新稿全量复检和读者分数同时有效才通过。离线失败测试后实现，完整编译/测试，再按用户“先修复问题，再重录”的授权执行同端点同模型3章一次录制、双回放及质量对比。人工确认前不bless。

NCS-059/060已独立提交合并与完成，当前仅恢复NCS-033；任务实时计数优化暂不扩展，报告以持久化章节与任务终态共同判断。
