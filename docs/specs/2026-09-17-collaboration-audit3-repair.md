# Spec: 协作系统第三轮复验修复

状态：用户于2026-09-17明确“确认实施”，设计与实施均已授权。日期：2026-09-17。

## 背景与动机

基线为 main@1b06d01421f13e7b07cda708783b6610a4f61fe7。复验发现六类未达标行为；已有测试全绿仅能证明旧断言成立。原始报告见 docs/reports/2026-09-17-collaboration-audit3/verification-report.md；原始机器证据保留在 /tmp/collab-audit3-20260917/。本方案已逐项对照源码与既有测试，尚未在本轮重新运行六个业务复现。

## 目标

1. 批准、计划确认和人物保存具有明确、可失败且不留下半套状态的提交边界。
2. 历史稿事实证据按真实来源验证，未生成候选的作者输入也能恢复。
3. 实验结果严格遵守原验收阈值；区分软件回归、人工复核暂停与真实文学实验结论。

## 非目标

不调整 Agent、prompt、生成参数或 golden，不绕过人工检查点；不伪造人工评分、严重错误计数或耗时；不推送、部署或修改用户现有小说数据。没有证据支持修复的其他问题只登记，不顺带扩展。

## 方案比较

推荐在现有领域及仓储边界修复：批准在同一事务中校验证据，计划在单个连接上原子写入，前端为修改要求单独暂存。这样保留现有架构并可以逐项故障注入验收。

仅补控制器校验或调整补偿顺序改动较小，但拦不住库中既有错误证据，也无法在持续 SQL 故障中可靠回滚；不采用。引入通用工作流/事务框架会扩大改动、迁移成本与回归面；本轮不采用。

## 修复设计

### 1. 批准证据的版本绑定

根因：selectUnsatisfiedDirectives 未要求 e.directive_revision=d.revision；selectBlockingHighIssues 仅用 issue_id 和 action 匹配裁决。已有 hasValidDisposition 的精确身份语义可作为参考，但不能从批准事务另开连接查询。

批准事务采用当前 projectId/chapterIndex/version/contentHash 和当前意见 revision；项目、章号、稿号、hash、意见修订全部匹配才可解除对应阻挡。历史错误裁决保留供审计，但失去当前批准效力。裁决入口验证问题归属和实际稿件身份；当前请求携带的旧身份返回冲突。HTTP入口校验之外，事务内批准守卫仍须独立拒绝错误存量记录。

先写失败测试：从真实 HIGH 问题写入 version=999 的旧错误裁决，再真实提交批准应 409；意见更新为 revision=2 后旧落实证据应阻挡；用匹配新证据验证正向路径。

### 2. 计划确认原子化

根因：commitPlanChange 的事务只覆盖决定与 CONFIRMED；偏好/大纲在独立连接中更新，后续补偿可能再次触发相同故障，而且不能恢复被裁剪大纲。

先增加未接线的领域 SPI 原子确认契约（默认 fail closed），随后一次性实现仓储事务并切换服务调用。业务服务仍只依赖 NovelRepository。事务中核验凭证状态和来源快照，然后写入决定、偏好、大纲与 CONFIRMED；复用接收 Connection 的大纲保存路径，偏好保存也使用同一连接，任何异常都 rollback。禁止嵌套调用会新开连接的仓储方法。来源校验必须在提交边界覆盖偏好、梗概、已写边界、承诺，不能只比较客户端 hash 和凭证保存值。

移除服务的补偿链。失败保留 PENDING 和原始数据，同一 previewId/requestKey/decision IDs 在故障解除后可重试成功。已成功的重复确认沿用当前 409 语义且无重复决定，不新增幂等响应协议。故障测试覆盖每一个写入点，特别包括偏好 upsert、部分大纲写入、最后状态更新，并比较完整大纲内容而非只比章数。

### 3. 人物修改入口

根因：省略 confirm 会进入直接保存分支，CharacterImpactPreviewApiTest 还显式维护了这一旁路。先迁移其他成功编辑测试至真实预览确认流程，保留其原业务断言，再关闭旁路。

只接受 JSON 布尔 confirm；缺失、null、字符串/数值等模糊输入返回 400，false 只预览，true 要求有效 previewToken。确认校验在项目工作锁内执行，核对项目归属和当前人物来源；来源已变返回 409。界面现有预览/取消/确认交互保留。此项明确改变旧 API 行为，必须检查所有调用者，不用放宽测试维持旧路径。

### 4. 历史稿事实证据

根因：FactEvidenceController 只读当前章节正文。若指定版本等于当前稿，使用当前标题/正文；否则按 projectId/chapterIndex/version 查询 ChapterVersion，并用该快照计算 hash 和验证引文。缺失历史稿不能退回当前稿冒充。事实归属与章节归属仍验证，未提供引文继续 UNKNOWN。只写证据，不修改稿件或批准状态。

### 5. 实验统计协议

恢复 p40-04 原计划阈值：质量均差（协作减对照）>= -0.2；作者审阅时间中位数下降 >=20%；严重连续性错误不增加。不可用四舍五入后的展示值做阈值判定。

按题材和章配对，每个条件每章至少三个不同的有效读者；重复读者记录不能加权抬高样本量。overall 先在章内对读者取均值，再对配对章求均差；作者耗时每章每条件只算一次，同一章重复填写的耗时必须一致。报告各题材和总体统计，不能隐去一题材的退化；原验收总体阈值作为总门禁，并明确各题材结果。

严重连续性错误采用逐章、逐条件、经人工确认的非负整数计数（新列 severeContinuityErrors），同章重复记录须一致；缺列/缺值判数据不足，不默认零。对每一题材与总体报告去重后总数并检查不增加。

以题材分层、章为配对抽样单元，固定 seed=20260917、10000 次 bootstrap 输出质量均差、时间降幅及严重错误差的 95% percentile CI、有效抽样数和估计方法。区间用于展示不确定性，不擅自增加“整个区间达标”这一原协议没有的门槛。基准耗时中位数为0时无法计算降幅，判数据不足。数值必须有限、范围合法。

三态结果：数据缺失/无效 INCOMPLETE；数据完整而门槛失败 FAILED；全部通过 COMPLETE。--require-complete 仅在 COMPLETE 时退出0。保留完整失败原因，不因首项失败掩盖另外两项。合成数据只做算法测试。现有 CSV 缺少严重错误字段，重评应如实 INCOMPLETE；不修改既有评分来制造通过，不继续沿用旧 COMPLETE 结论。

### 6. 修改要求草稿

使用独立 hook 按 projectId、targetType、targetId、chapterIndex、passageIndex 分隔 localStorage 键。输入事件同步暂存，打开时恢复，避免首个 effect 的空字符串覆盖已有草稿。取消保留草稿；清空输入视为主动清除；应用成功清除对应草稿，生成/应用失败保留。跨对象请求沿用 requestSequence 防串写。

草稿只保存作者文字与存储格式版本，不自动恢复旧候选的应用权限。候选由服务器单独恢复；选择旧候选时，若存在不同的新草稿须保留新草稿并给出选择，不能静默覆盖。存储不可用时保留内存输入，提示“修改要求尚未暂存，刷新可能丢失”。暂存状态文案与“未写入正文”的文案分开。

## 验收标准

- 每个 bug 先提交/记录在修复前确实失败的自动化测试，再改实现；保留 RED/GREEN 命令和结果。契约准备与测试迁移不冒充 bug 修复。
- 每个 feature 独立分支，依赖合入 main 后才启动；按下表 verify_cmd 和 mvn compile && mvn test 验证、提交、合并，再在干净 main 检出复验，最后登记 verified。
- p41-09 另运行前端构建与全部 qa/collaboration 回归，隔离测试服务监听127.0.0.1:18089，测试结束停止服务。专属命令执行前先启动该隔离服务且安装 QA 依赖，不指向用户数据库。
- 最终重跑六类问题对应的真实 API/浏览器路径；SQL 故障注入仅限临时库，保留状态前后对比。
- 两题材 replay 分别记录实际章数、项目状态、evaluation 和基线检查结果。PAUSED 是等待人工复核，不能包装成三章完成或长篇实验通过。失败按 diagnose.sh 分类；不自动批准需要判断的章节。
- 脚本修复后重评现有人工数据，将数据不足/阈值失败作为实验阻塞记录；脚本功能修复可通过其合成契约测试，文学实验另列待补证。

## 影响面

- **涉及模块**：批准仓储、计划服务与 SPI、人物/事实 REST、实验脚本、Agent 修改对话框和对应测试。
- **涉及 prompt 模板**：无。
- **数据库 schema 变更**：预计无，使用已有 revision、章节快照、裁决与计划表。
- **质量基线影响**：不改变生成输入，不重录 fixture、不 bless。若实现调查发现必须改变注入内容，重新记录范围并按 AGENTS.md 的录制/双回放/人工确认门禁执行。

## Feature 拆分

以下每项业务/测试文件为1–3个；Harness 状态、证据为必要配套文档。p41-03/p41-05为先行契约与调用测试迁移，其余为bug修复。

| id | name | 依赖 | acceptance | verify_cmd |
|----|------|------|------------|------------|
| p41-01 | 批准事务绑定稿件身份与意见修订 | p36-04, p37-07, p38-05 | 错误项目/章号/版本/hash 的既有裁决不解除 HIGH 阻挡；revision=1 证据不满足 revision=2 意见；匹配证据可批准；失败不写批准记录、不解除屏障。 | `mvn test -Dtest=ApprovalEvidenceIdentityTest,VersionedApprovalRepositoryTest,DispositionApprovalGateTest` |
| p41-02 | 裁决写入校验真实问题和稿件 | p41-01 | 不存在的问题或稿件、跨项目/错章和伪造 hash 返回 400/409 且无新增裁决；真实当前稿裁决可保存并经批准事务核验。 | `mvn test -Dtest=ReviewDispositionApiTest,DispositionApprovalGateApiTest` |
| p41-03 | 计划原子提交的 SPI 契约 | p38-02, p38-03 | 新增领域级原子确认操作，默认实现明确拒绝且无写入；参数含预览身份、目标、大纲/偏好与逐项决定；不暴露 JDBC、不改变现有调用行为。 | `mvn test -Dtest=PlanCommitContractTest` |
| p41-04 | 计划确认跨对象事务及故障重试 | p41-03 | 对决定、偏好、大纲、确认状态各写入点注入 SQLite 失败后，四类数据全部维持原值；移除故障后同一请求成功一次；已确认请求不重复写入。 | `mvn test -Dtest=PlanConfirmationAtomicityTest,PlanEditionTest,PlanChangeRepositoryTest,Audit2FixTest` |
| p41-05 | 人物旧调用测试迁移到预览确认 | p39-04, p39-09 | 既有成功编辑测试先取真实 previewToken 后确认；原人物字段、真典及屏障断言保留且通过，不把拒绝旧路径误当成功覆盖。 | `mvn test -Dtest=CanonWriteApiTest,WorldCharacterFullEditTest` |
| p41-06 | 关闭人物无预览写入旁路 | p41-05 | confirm 缺失/null/非布尔拒绝且库不变；false 只预览；true 无凭证拒绝、过期凭证 409、有效凭证才保存；跨项目不写入。 | `mvn test -Dtest=CharacterImpactPreviewApiTest,CanonWriteApiTest,WorldCharacterFullEditTest` |
| p41-07 | 事实证据验证对应历史稿 | p39-03 | 经正式编辑产生 v3 后，真实历史 v2 的 hash/引文返回 200；不存在版本、错 hash、仅新稿含有的引文、跨项目来源拒绝；UNKNOWN 引文语义保留。 | `mvn test -Dtest=HistoricalFactEvidenceApiTest,AuditP14EndpointsTest,Audit2FixTest` |
| p41-08 | 实验严格门槛与可复现统计 | p40-04 | 240 行评分下降1分且耗时增长50%的反例不再 COMPLETE 且 require-complete 非零；-0.2/20%边界、严重错误增加/缺失、重复评委/非有限数字被正确判定；固定种子的置信区间可复现。 | `python3 scripts/test_evaluate_collaboration_study.py` |
| p41-09 | Agent 修改要求独立草稿恢复 | p37-04 | 未生成候选的输入刷新重开后逐字恢复；项目/对象/段落隔离；取消、生成失败、应用失败不丢输入；成功应用清理对应草稿；存储异常显示未暂存而不崩溃。 | `QA_BASE_URL=http://127.0.0.1:18089 npx --prefix qa/collaboration playwright test -c qa/collaboration/playwright.config.ts revision-instruction-draft.spec.ts` |

### 文件边界

- p41-01：`src/main/java/com/writing/novel/persistence/sqlite/SqliteNovelRepository.java`；`src/test/java/com/writing/novel/persistence/ApprovalEvidenceIdentityTest.java`
- p41-02：`src/main/java/com/writing/novel/web/DispositionController.java`；`src/test/java/com/writing/novel/web/ReviewDispositionApiTest.java`
- p41-03：`src/main/java/com/writing/novel/spi/NovelRepository.java`；`src/test/java/com/writing/novel/persistence/PlanCommitContractTest.java`
- p41-04：`src/main/java/com/writing/novel/persistence/sqlite/SqliteNovelRepository.java`；`src/main/java/com/writing/novel/service/PlanEditionService.java`；`src/test/java/com/writing/novel/web/PlanConfirmationAtomicityTest.java`
- p41-05：`src/test/java/com/writing/novel/web/CanonWriteApiTest.java`；`src/test/java/com/writing/novel/web/WorldCharacterFullEditTest.java`
- p41-06：`src/main/java/com/writing/novel/web/ContentController.java`；`src/test/java/com/writing/novel/web/CharacterImpactPreviewApiTest.java`
- p41-07：`src/main/java/com/writing/novel/web/FactEvidenceController.java`；`src/test/java/com/writing/novel/web/HistoricalFactEvidenceApiTest.java`
- p41-08：`scripts/evaluate-collaboration-study.py`；`scripts/test_evaluate_collaboration_study.py`；`docs/experiments/collaboration-quality-protocol.md`
- p41-09：`frontend/src/components/AgentRevisionDialog.tsx`；`frontend/src/hooks/useRevisionInstructionDraft.ts`；`qa/collaboration/revision-instruction-draft.spec.ts`

## 风险与备选方案

独立新检出首次执行 init.sh 时，后端静态首页测试依赖前端生成产物；先在 frontend 执行 npm run build 再跑 init.sh。此为本轮基线准备要求，不在本轮顺带修改初始化脚本。

计划事务最容易因内部方法新开连接而退化为假原子，必须用持久 SQL 故障注入而非仅 mock 异常验证。身份修复可能使旧“已解决”事项重新阻挡，这是旧证据与新稿不匹配的预期结果，不能自动给新稿补裁决。

localStorage 仅保证本浏览器同源恢复，不承诺跨设备同步。统计设计的新增错误计数可能揭示真实实验缺证，必须保留此结论并等待人工资料。原完成记录保持历史证据，当前进度明确由本轮复验推翻“全部验收通过”的推论。

各功能有独立提交可回退；回退时仅撤销本轮提交，不能恢复有风险的旧入口后宣称验收通过。设计审批是当前唯一实施前门禁；审批后依序 p41-01 至 p41-09 实施，不再逐项请求常规提交或本地合并确认。

## 实施中的必要回归迁移（2026-09-18）

p41-09 全套协作回归首次得到37通过/1失败：`qa/collaboration/review-dispositions.spec.ts` 的旧断言仍要求不存在的 issue-fp-1 创建成功。作为最终受影响检查的必要修正，本分支额外迁移该测试为真实 HTTP 拒绝、裁决列表为空且 reviewSetHash 不变；保留 recheck 的200/409与其他拒绝断言。真实存在问题的裁决创建、查询、批准正向流程由 p41-02 的 ReviewDispositionApiTest 在全量门禁覆盖。此为原批准修复的测试适配，未扩展业务范围；较初始1–3文件拆分增加一个受影响QA文件。
