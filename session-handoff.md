# 协作修复实施交接（p36-02/03/04/07 已完成） — 2026-09-13

Last Updated: 2026-09-13

## Current

Phase36 全部 13 项完成；Phase37 已启动（p37-01 合入 main，bfdabf7）。feature_list 319 项 = 275 completed + 44 not-started，active_feature=null。

后端版本化批准与修订失效链路完整：身份模型/审计表 → 单连接事务 approveChapter（幂等/守卫/比较提交）→ HTTP review-context/review-decisions → 修订事务内屏障重绑+批准失效+历史（doEdit 事务后吞异常 rebind 已删除）。

## Files

- 分支各自独立并 ff 合入 main。
- 证据：docs/harness/evidence/p36-0{1..11}-p36.json、p36-1{2,3}-p36.json、p37-01-p37.json；完成记录 completions.json 对应 verified 记录。
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
- p37-01：RED 3 errors（no such table）→ GREEN 专项 5/5；RevisionProposal（APPLIED 终态守卫、chapterSourceHash 与 p36 同构、canonicalJsonSha256）+ revision_proposals 表（UNIQUE(project_id, request_key)，无 TTL 自动删除）。合并后 main 全量 771/0/0/1skip。
- 各项均无 prompt/上下文/生成输入改动，未跑 e2e/baseline，未 bless。

## Blockers

安全闭环成立：版本化批准是唯一章节放行路径。遗留观察项：OrchestratorController /resume 不在 p36-06 契约清单内（resumeFromCheckpoint 直接批准最新 pending checkpoint），如需收敛应另立 feature。真实评分/模型预算/bless 属执行时授权事项。reviewSetHash 的 issues/directives/dispositions 集合待 p37/p38。不得把后端测试当作端到端修复证据。真实评分/模型预算/bless 属执行时授权事项。

## Next Steps

1. 下一项 p37-02（候选持久存取与章节原子应用协议；依赖 p37-01+p36-07 均已完成）：NovelRepository 增 saveRevisionProposal/findRevisionProposals/findRevisionProposal/applyRevisionProposal 等（缺省抛 UnsupportedOperationException 保持 stub 可编译）；applyRevisionProposal 单连接事务：来源比较（sourceHash vs 当前稿身份）→ 双重应用守卫 → 经 ChapterRevision 边界原子推进正文 → 候选 APPLIED+resultJson → 意见消费证据留待 p37-12。先写 ProposalRepositoryTest RED：来源变化候选 STALE、同 key 重试幂等、双 apply 仅一次正文推进、注入失败全回滚。
2. 复用既有模式：RED 用 git stash 旧 bundle 或纯后端契约测试；QA 走 npm --prefix qa/collaboration ci。
2. 其后 p37 持久候选与意见证据按 dependencies 拓扑推进。
3. 保留工作区原有未跟踪文件，不推送远端。
