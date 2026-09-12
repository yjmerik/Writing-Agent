# Spec: 协作系统复验问题修复与完整交付

> 依据：`docs/reports/2026-09-12-collaboration-ui-acceptance.md` 与本仓库 `docs/reports/2026-09-12-collaboration-ui-reverification.md`。设计基点 main `b1c8289`。本文件是唯一共享设计契约，分阶段计划负责执行细节。

## 背景与动机

Phase34/35 的登记与构建通过，不等于原界面验收完整通过。2026-09-12 实测仍有：旧窗口显示v2却能批准v3、普通目录刷新回第1章、标题草稿丢失、候选及其意见刷新丢失；源码与接口还缺少意见实际注入/逐项落实、人物影响、事实时点、成书固定版本。目标是使作者每次决定都有明确对象、实际状态、持久结果和可追溯证据。

A1空标签构思与A3旧稿警示的核心症状已通过，不重复重做；保留为全链路回归。历史完成记录不回写为不存在，也不扩展为“原方案已全部交付”。新缺陷另记bug，其他缺口以feature登记。

## 目标

1. 作者操作不会丢草稿、丢候选或误批准未读新稿，刷新、并发、重启、断线重试后保持一致。
2. 从意见登记到生成/候选证据、作者确认，从计划裁剪到承诺处置，从事实来源到人物影响，以及从覆盖检查到成书批准导出形成可操作闭环。
3. 所有原验收矩阵项映射到自动化/人工证据；不得以统计completed或测试数量替代功能和文学验收。

## 非目标

- 不在本轮实施业务代码、调用真实模型、填写盲评或更新golden。
- 不改变Java17/React18技术栈、不全面重构单体、不引入多写进程SQLite部署。
- 不自动修订所有被影响历史章节；先呈影响证据与重检建议，由作者选择。
- 不自动推送、部署；不把设计文档视为远端操作授权。

## 核心不变量与决策

### C1 — 身份与幂等

章节身份为 `(projectId, chapterIndex, version, contentHash)`，全hash按标题/正文UTF-8字节及长度前缀计算。长度是字节数；编码固定；不做去空白/时间戳归一化。旧12位报告hash保留兼容，不作为新批准身份。

`reviewSetHash` 为同事务读取的有序集合：当前报告身份、待审屏障、章节类checkpoint、有效问题裁决、适用意见revision及证据身份。集合按稳定ID排序，JSON键排序后SHA256。新增问题或撤销要求等都会使旧决定失效。

`idempotencyKey` 是一次作者动作生成的UUID；相同key+相同请求恢复原结果；相同key+不同语义409。不同key的双击不算两次有效批准。同key重试仅返回原identity，不借机批准新稿。

### C2 — 审阅原子事务

```text
BEGIN (single connection)
  read chapter + review set + existing idempotent result
  if key exists: compare original request; return original result or conflict
  require exact requested identity == current chapter identity
  require exact reviewSetHash == current review set hash
  require all current blocking items have valid resolution
  insert immutable chapter approval
  mark exact chapter version flow DONE
  resolve only barriers bound to that identity
  process corresponding chapter checkpoints
  recompute project checkpoint pause state from remaining pending items
COMMIT
```

任何一步失败回滚所有写入。不能混用多连接公共仓储函数；worker所有权只解决进程内并发，不替代数据库比较/事务。正文编辑同样在事务内保留旧版本、审计屏障旧身份并失效“当前有效批准”；已批准历史记录永不改成新稿批准。

初始设定/卷计划审批仍保留既有流程；本轮修补的是报告明确暴露的章节批准语义。修订新稿不能隐式获得作者批准。后端迁移先新增版本接口，前端全接入后关闭旧入口，整个安全闭环只有p36-06与p36-07均完成后成立。

### C3 — 草稿与候选

本地草稿保存标题+正文+基准身份；冲突是可读可复制的状态，禁止静默隐藏或下次输入覆盖冲突副本。存储失败提示作者，不装作已暂存。

修订候选是DB资源，不是弹窗内存。READY/STALE/APPLIED/CANCELLED持久保存。生成过程的来源变化必须使候选STALE。应用先比来源，正文/版本历史/候选消费/落实证据一起提交；断线后查询或同key重试。非章节候选同样需要对象+真典/结构+候选消费一致；由p39-07/08补全，前序切片不得宣传全类型故障恢复已交付。

### C4 — 作者意见与证据

意见启用与某章落实分离。全书HARD被某章确认后仍对后续有效。范围为[fromChapter,toChapter]闭区间，to=null无限，旧chapterIndex作为兼容输入转换。ONCE确认后不再当待修改意见；PREFERENCE/HARD持续直到显式撤销。撤销/内容修改递增revision，原证据不删除。

证据必须包含directiveId/revision+章节身份+原文quote/anchor或UNMET原因。预览自述不等于正文已落实；应用/实际生成落稿后核对才是APPLIED_SYSTEM；作者只确认当前匹配证据。系统不能设置CONFIRMED。核对器失败/漏项为未验证，不是通过。

### C5 — 计划与成书

计划确认只消费持久previewId/sourcePlanHash，不接受改一个目标数字就沿用旧预览。影响基于计划+已写边界+承诺版本；任何变化重新预览。迁移承诺不代表已兑现；接受开放结局不代表已回收；mustResolve无显式有效豁免仍阻塞。自动尾声逻辑必须采用相同决定，避免又自动写21章。

成书版在一致事务中物化每章正文、身份、报告/批准与计划/处置清单。Project.bookSnapshot只是长记忆摘要，不能冒充成书版。批准绑定manifestHash且重读当前覆盖资格。导出固定edition，不再读动态chapters拼接；当前稿导出仍可保留但明示草稿。

### C6 — 事实、视图与运行诚实性

来源总是某章某稿，quote未核实时UNKNOWN。时间线按叙事生效区间与替代链查询，不仅筛当前ACTIVE；知识边界只显示有来源的知情信息，未记录不是“全员知道”也不是“明确不知道”。人物影响分确定引用/可能引用/覆盖不足，不承诺自动修复历史正文。

桌面与390px使用同一审查组件和后端决定。DONE只描述生成流程；成功/空结果/未知/过期/失败分开。运行步骤耗时来自有覆盖的事件，缺事件不能等同运行成功。

## 数据迁移与兼容

| 数据 | 迁移 | 旧数据处理 |
|---|---|---|
| chapter_approvals / review_barrier_history | 新表+项目幂等键唯一约束 | 不从DONE回填批准；屏障旧身份追加历史 |
| revision_proposals | 新表，before/after/instructions/result JSON | 原内存候选无法恢复，告知升级前候选需重建；不假造迁移 |
| author_directives / directive_evidence | 新列revision/enabled/from/to + 分章证据表 | chapterIndex映射范围；旧缺hash证据仅历史未验证 |
| plan_changes / promise_decisions | 新表及来源hash | 现有未回收承诺保持原义，开放结局单独审计 |
| book_editions / book_edition_chapters | 不可变物化快照新表 | 不把book_summary/book_snapshot迁为批准版 |
| review_dispositions | 新表，问题身份与稿号 | 不自动继承旧稿裁决 |
| fact_evidence | 新表，来源锚点/有效区间/知情者 | 缺来源标UNKNOWN，不用字符串猜证据 |

所有迁移必须连续启动两次幂等、新旧库各测，正文/历史/既有真典审计条数与hash保持。迁移前用SQLite backup备份；回退只能对独立副本演练。正式回退旧应用若会绕过版本批准，应停止写入而非带新库继续运行旧不安全入口。计划采用前向修复，不能删除历史表“恢复干净”。

## 接口总览（均在 /api/projects/{id} 下）

| 方法 | 路径 | 必需语义 |
|---|---|---|
| GET | /chapters/{n}/review-context | 已读正文身份、reviewSetHash、报告/待办/裁决/批准 |
| POST | /chapters/{n}/review-decisions | identity/reviewSetHash/idempotencyKey/reason |
| POST | /chapters/{n}/review-dispositions | identity/reviewSetHash/issueId/action/reason/deferUntilChapter |
| POST | /chapters/{n}/recheck | identity；异步产物不得冒用更新稿 |
| POST/GET | /revisions/preview、/revisions、/revisions/{previewId} | source身份、requestKey、可恢复候选 |
| POST | /revisions/{previewId}/apply、/cancel | 持久幂等结果或版本冲突 |
| POST/PATCH/GET | /directives、/directives/{directiveId} | enabled/revision/kind/fromChapter/toChapter |
| POST | /directives/{id}/evidence/{evidenceId}/confirm | 当前identity+意见revision，不准直接确认OPEN |
| POST | /directives/{id}/revoke | reason + expectedRevision，含HARD显式撤销 |
| POST | /plan/target-chapters | 预览返回token；确认需token/hash/decisions/key |
| POST/GET | /book-editions | 创建一致快照或列表 |
| GET/POST/GET | /book-editions/{editionId}、/{editionId}/approve、/{editionId}/export | 精确manifest、批准、固定字节下载 |
| GET/POST | /facts/evidence、/facts/{factId}/evidence | 截至章号/知情者筛选及来源校验 |
| GET | /run-details?taskId= | 覆盖状态、阶段耗时、脱敏失败位置 |

新接口缺字段400，内容/版本/待审集合失配409；状态不确定不返回成功。前端409保留作者输入，只刷新权威状态，不自动批准。跨项目资源不允许通过ID访问。`WORK_OWNERSHIP_CONFLICT`保持既有区分。

## 影响面

- 涉及模块：web/service/orchestrator/memory/agent/domain/SPI/SQLite、React工作区与审查组件、独立QA包及实验脚本。
- prompt：现有content-revision.st保留变量名；新增directive-evidence.st；ContextAssembler实际注入意见将改变输入。逐项quality_gate在backlog标记。
- 数据库：按上表增量迁移，禁止重建或删除作者数据库。
- 质量基线：生成/检查调用变化均走QG；仅UI、只读存取、文档不自动bless。成本/语料漂移是已知影响，证据要记录。

## 验收标准

- [ ] p36安全闭环：旧窗口/旧端点/晚到操作不能批准或覆盖未读新稿；事务回滚无半套状态。
- [ ] 作者草稿与候选：目录/页签/标题正文/候选/意见刷新恢复；冲突和配额失败不会丢数据。
- [ ] 三意见：输入分别登记→实际送入生成→逐项落实或明确UNMET→当前稿作者确认；持续HARD下一章仍有效。
- [ ] 计划24→20：预览来源一致、承诺逐项处置、无已写内容丢失、不被旧大纲保存复活，不凭开放结局自动追加尾声。
- [ ] 成书清单：所有当前稿检查/作者决定/承诺处置可定位；批准版导出与manifest逐章逐字一致且后续修改不影响历史下载。
- [ ] 事实/人物：可回到真实历史来源、按N章/人物知情筛选，设定修改先预览且不静默改历史正文。
- [ ] 桌面与390px键盘路径等价，问题裁决和错误重试可达；无原始JSON主界面。
- [ ] 慢模型/重启/断线专项通过；实际两题材20章盲评和作者负担实验通过。未运行标NOT_RUN，不能complete。
- [ ] 每feature verify_cmd、mvn compile/test、受影响前端/Agent/QG门禁通过，合并后验证与证据完整。

## Feature 拆分

下表验收标准为verify_cmd退出0且该项计划列出的所有具体断言成立；各条详细acceptance同时写入feature_list.json。状态一律not-started。

| id | name | 依赖 | verify_cmd |
|---|---|---|---|
| p36-01 | 建立真实浏览器回归入口 | — | `npm --prefix qa/collaboration test -- smoke.spec.ts` |
| p36-02 | 定义不可变审阅身份与批准审计表 | — | `mvn test -Dtest=ApprovalSchemaTest` |
| p36-03 | 实现章节批准与屏障的原子比较提交 | p36-02 | `mvn test -Dtest=VersionedApprovalRepositoryTest` |
| p36-04 | 暴露携带版本的审阅上下文与批准接口 | p36-03 | `mvn test -Dtest=VersionedReviewDecisionTest` |
| p36-05 | 所有章节审阅按钮使用同一已读版本 | p36-01, p36-04 | `npm --prefix qa/collaboration test -- review-version.spec.ts` |
| p36-06 | 关闭无版本批准的兼容旁路 | p36-05, p36-12, p36-13 | `mvn test -Dtest=LegacyApprovalGateTest` |
| p36-07 | 正式修订原子失效批准并记录屏障历史 | p36-03 | `mvn test -Dtest=RevisionBarrierAtomicityTest` |
| p36-08 | 同步目录、深链接、页签与浏览器历史 | p36-01 | `npm --prefix qa/collaboration test -- workspace-navigation.spec.ts` |
| p36-09 | 标题与正文草稿恢复、冲突保留及存储失败提示 | p36-01 | `npm --prefix qa/collaboration test -- draft-recovery.spec.ts` |
| p36-10 | 目标章数改变失效旧预览并刷新大纲 | p36-01 | `npm --prefix qa/collaboration test -- plan-preview.spec.ts` |
| p36-11 | 成书清单区分流程完成与作者批准 | p36-01 | `npm --prefix qa/collaboration test -- book-state.spec.ts` |
| p36-12 | 迁移批准守卫和真实并发回归 | p36-04, p36-07 | `mvn test -Dtest=ManualReviewResolutionTest,ResolveReviewConcurrencyTest,ChapterDirectEditTest` |
| p36-13 | 迁移屏障恢复与工作台批准回归 | p36-04, p36-07 | `mvn test -Dtest=HitlCanonForeshadowReviewTest,ReviewBarrierGateTest,WorkspaceStatusApiTest` |
| p37-01 | 持久修订候选模型与迁移 | p36-02 | `mvn test -Dtest=ProposalSchemaTest` |
| p37-02 | 候选持久存取与章节原子应用协议 | p37-01, p36-07 | `mvn test -Dtest=ProposalRepositoryTest` |
| p37-03 | 修订服务使用持久候选和恢复查询 | p37-02, p37-14 | `mvn test -Dtest=PersistentRevisionApiTest` |
| p37-04 | 候选恢复入口与未提交意见草稿 | p36-01, p37-03, p36-09 | `npm --prefix qa/collaboration test -- candidate-recovery.spec.ts` |
| p37-05 | 作者可读的正文与字段差异对比 | p37-04 | `npm --prefix qa/collaboration test -- revision-diff.spec.ts` |
| p37-06 | 意见范围与逐章证据模型迁移 | p37-01 | `mvn test -Dtest=DirectiveScopeMigrationTest` |
| p37-07 | 意见与证据版本化仓储 | p37-06 | `mvn test -Dtest=DirectiveEvidenceRepositoryTest` |
| p37-08 | 意见接口呈现范围、证据与显式撤销 | p37-07 | `mvn test -Dtest=DirectiveVersionApiTest` |
| p37-09 | 写作上下文容纳不可静默裁剪的作者要求 | — | `mvn test -Dtest=DirectiveContextSectionTest` |
| p37-10 | 生成上下文实际消费有效作者要求 | p37-07, p37-09 | `mvn test -Dtest=DirectiveContextInjectionTest` |
| p37-11 | 候选模型返回逐项落实与未落实原因 | p37-06 | `mvn test -Dtest=RevisionEvidenceContractTest` |
| p37-12 | 候选生成与应用落实证据联动 | p37-03, p37-07, p37-10, p37-11 | `mvn test -Dtest=DirectiveProposalIntegrationTest` |
| p37-13 | 意见范围编辑与证据逐项作者确认 | p37-08, p37-12, p36-05, p37-16 | `npm --prefix qa/collaboration test -- directive-loop.spec.ts` |
| p37-14 | 统一修订边界接入候选原子提交 | p37-02 | `mvn test -Dtest=ProposalRevisionBoundaryTest` |
| p37-15 | 生成后作者要求核对器契约 | p37-06 | `mvn test -Dtest=DirectiveEvidenceAgentTest` |
| p37-16 | 章节生成完成后落库意见落实证据 | p37-10, p37-15, p36-07 | `mvn test -Dtest=GeneratedDirectiveEvidenceTest` |
| p38-01 | 计划变更凭证与承诺处置模型 | p36-02 | `mvn test -Dtest=PlanChangeSchemaTest` |
| p38-02 | 篇幅与承诺处置的原子提交 | p38-01 | `mvn test -Dtest=PlanChangeRepositoryTest` |
| p38-03 | 计划变更服务使用持久预览凭证 | p38-02 | `mvn test -Dtest=PlanPreviewCommitApiTest` |
| p38-04 | 篇幅收尾的承诺逐项处置界面 | p38-03, p36-10 | `npm --prefix qa/collaboration test -- plan-resolution.spec.ts` |
| p38-05 | 不可变成书版本模型与存储结构 | p36-02 | `mvn test -Dtest=BookEditionSchemaTest` |
| p38-06 | 成书快照创建与批准原子性 | p38-05, p36-03, p38-02, p38-10 | `mvn test -Dtest=BookEditionRepositoryTest` |
| p38-07 | 成书覆盖、批准与指定版本导出接口 | p38-06, p38-10, p38-13 | `mvn test -Dtest=BookEditionExportTest` |
| p38-08 | 成书版本选择、覆盖阻塞与下载界面 | p38-07, p36-11 | `npm --prefix qa/collaboration test -- edition-export.spec.ts` |
| p38-09 | 版本绑定的问题裁决与豁免模型 | p36-02, p37-06 | `mvn test -Dtest=DispositionSchemaTest` |
| p38-10 | 问题裁决存取与批准门禁统一计算 | p38-09, p37-07, p36-03 | `mvn test -Dtest=DispositionApprovalGateTest` |
| p38-11 | 问题裁决接口与按当前稿重新检查 | p38-10, p36-04 | `mvn test -Dtest=ReviewDispositionApiTest` |
| p38-12 | 桌面与窄屏共享完整审查操作 | p38-11, p37-13, p36-05 | `npm --prefix qa/collaboration test -- review-dispositions.spec.ts` |
| p38-13 | 成书门禁与承诺处置采用同一规则 | p38-02, p38-10 | `mvn test -Dtest=EndingDecisionIntegrationTest` |
| p39-01 | 事实来源锚点与知情区间模型 | p36-02 | `mvn test -Dtest=FactEvidenceSchemaTest` |
| p39-02 | 事实证据仓储与历史时点查询 | p39-01, p39-10 | `mvn test -Dtest=FactEvidenceRepositoryTest` |
| p39-03 | 收割实际来源与人审补证据接口 | p39-02 | `mvn test -Dtest=FactEvidenceCaptureTest` |
| p39-04 | 人物与设定修改影响预览及安全提交 | p37-03, p39-02, p39-07, p39-08 | `mvn test -Dtest=CharacterImpactPreviewTest` |
| p39-05 | 来源跳转、时间线与人物知识界面 | p39-03, p36-08 | `npm --prefix qa/collaboration test -- fact-evidence.spec.ts` |
| p39-06 | 人物修改影响确认界面 | p39-04, p37-05, p39-09 | `npm --prefix qa/collaboration test -- character-impact.spec.ts` |
| p39-07 | 世界人物候选与真典原子幂等提交 | p37-02, p39-02 | `mvn test -Dtest=CanonProposalCommitTest` |
| p39-08 | 卷候选与结构来源比较提交 | p37-02 | `mvn test -Dtest=VolumeProposalCommitTest` |
| p39-09 | 设定影响预览接口与前端请求契约 | p39-04 | `mvn test -Dtest=CanonImpactApiTest` |
| p39-10 | 真典持久层暴露同连接事务辅助方法 | p39-01 | `mvn test -Dtest=StoryCanonConnectionTest` |
| p40-01 | 不可取消慢模型与接手晚写入专项 | p36-07, p37-03 | `mvn test -Dtest=TakeoverLateCommitTest,ProposalRestartRecoveryTest` |
| p40-02 | 运行详情端点说明实际步骤与失败位置 | — | `mvn test -Dtest=RunDetailsApiTest` |
| p40-03 | 运行进度、重试与作者术语界面 | p40-02, p36-01 | `npm --prefix qa/collaboration test -- run-details.spec.ts` |
| p40-04 | 两题材盲评与作者负担实验协议 | — | `python3 scripts/test_evaluate_collaboration_study.py` |
| p40-05 | 全链路独立验收与覆盖报告 | p36-06, p36-07, p36-08, p36-09, p37-13, p38-04, p38-08, p38-12, p39-05, p39-06, p40-01, p40-03, p40-04, p40-06 | `bash scripts/verify-collaboration-acceptance.sh` |
| p40-06 | 实际执行两题材20章盲评和审阅负担实验 | p40-04, p37-16, p38-08, p39-06, p40-03 | `python3 scripts/evaluate-collaboration-study.py --manifest docs/experiments/collaboration-study-manifest.json --ratings docs/experiments/collaboration-study-ratings.csv --require-complete` |

## 风险与备选方案

- 1–3文件限制会增加切片数量；为避免长期破损分支，schema/仓储/API/UI分阶段加入兼容契约，安全旁路关闭作为独立验收，不提前声称安全闭环完成。
- 仅加前端禁用不能修复旧请求/竞态；仅加锁不能持久恢复；仅存previewId到localStorage不能解决服务重启。均不作为最终替代方案。
- 复用既有SPI和数据库连接设施，不能让业务层通过类型强转调用Sqlite专属事务。
- 新证据核对器只能产生系统判断；无法证明文学质量或代替人类确认。硬性要求不满足时保留待办，不假造证据。
- 原文锚点随稿改变而过期；历史定位失败须有可见说明，禁止退回当前正文冒用来源。
- 文学实验需要真实模型预算和真实参与者，程序不能代填；数据或授权缺失是未完成状态，不应把评分门槛改低以通过。
