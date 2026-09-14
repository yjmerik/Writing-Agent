# 协作修复实施交接（Phase36 完成，Phase37 已启动） — 2026-09-14

Last Updated: 2026-09-14

## Current

Phase36 全部 13 项完成；Phase37 推进中（p37-01/02 合入 main，最新 c3b06d2）。feature_list 319 项 = 276 completed + 43 not-started，active_feature=null。

后端版本化批准与修订失效链路完整：身份模型/审计表 → 单连接事务 approveChapter（幂等/守卫/比较提交）→ HTTP review-context/review-decisions → 修订事务内屏障重绑+批准失效+历史（doEdit 事务后吞异常 rebind 已删除）。

## Files

- 分支各自独立并 ff 合入 main。
- 证据：docs/harness/evidence/p36-*.json 与 p37-0{1,2}-p37.json；完成记录 completions.json 对应 verified 记录。
- 执行入口 docs/superpowers/plans/2026-09-12-collaboration-repair-entry.md；总 spec 与 p36–p40 分计划不变。

## Verification

- 后端四项 RED→GREEN→合并后复跑：全量 757/0/0/1skip；专项 4/4、8/8、4/4、5/5。
- p36-01：失败路径（未设URL/错误端口/空白页）非零退出；happy path 3 passed exit 0（场景模式服务 + 临时独立库）；前端构建通过。
- p36-05：RED 2 failed → GREEN review-version.spec 2 passed（双窗口竞态 + 390px 等价）。
- p36-12：基线 14/0 → 专项 17/17；并发严格 200/409、旧身份 409 新稿待审。
- p36-13：基线 9/0 → 专项 9/9；屏障恢复/工作台批准走版本化接口。
- p36-06：RED 4 failures → 专项 6/6；旁路关闭，modify 保留重写回到待审。
- p36-08：RED 6 failed → GREEN 6 passed；tab 入 URL、章路由权威、非法章号提示回退、历史深链接指纹校验。
- p36-09：RED 2 failed → GREEN 4 passed；冲突副本并排+复制+放弃、409 保留输入、离开保护。
- p36-10：RED → GREEN plan-preview.spec 2 passed；预览绑定目标数字、序号丢弃乱序、确认后不复活裁剪章。
- p36-11：RED 2 failed → GREEN 2 passed；成书清单诚实状态。
- p37-01：RED 3 errors → GREEN 5/5；RevisionProposal + revision_proposals 表。
- p37-02：RED 6 errors → GREEN 6/6；saveProposal/findProposal/listProposals/cancelProposal（CAS）+ commitProposalChapter（单事务：双重应用守卫重放、来源比较、p36-07 修订语义、APPLIED resultJson；冲突独立短事务标 STALE 不改正文；故障回滚 READY）；doSaveChapterRevision 为两者共享路径。开发中修正：测试助手 revised 需复用现有章节行 id（saveChapterRevision 行内 CAS 语义）。
- 各项均无 prompt/上下文/生成输入改动，未跑 e2e/baseline，未 bless。

## Blockers

安全闭环成立：版本化批准是唯一章节放行路径。遗留观察项：OrchestratorController /resume 不在 p36-06 契约清单内（resumeFromCheckpoint 直接批准最新 pending checkpoint），如需收敛应另立 feature。真实评分/模型预算/bless 属执行时授权事项。reviewSetHash 的 issues/directives/dispositions 集合待 p37/p38。不得把后端测试当作端到端修复证据。真实评分/模型预算/bless 属执行时授权事项。

## Next Steps

1. 下一项 p37-14（统一修订边界接入候选原子提交，依赖 p37-02 已完成）：AgentRevisionService/ChapterRevisionService 的生成与重写路径接入 commitProposalChapter——候选生成落盘（生成中非 READY）、来源指纹记录、应用走候选协议。verify_cmd mvn test -Dtest=ProposalRevisionBoundaryTest。
2. 其后 p37-03（修订服务使用持久候选和恢复查询）依赖 p37-14。p37-09 无依赖可穿插。
2. 复用既有模式：RED 用 git stash 旧 bundle 或纯后端契约测试；QA 走 npm --prefix qa/collaboration ci。
2. 其后 p37 持久候选与意见证据按 dependencies 拓扑推进。
3. 保留工作区原有未跟踪文件，不推送远端。
