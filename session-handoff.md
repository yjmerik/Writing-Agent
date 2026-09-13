# 协作修复实施交接（p36-02/03/04/07 已完成） — 2026-09-13

Last Updated: 2026-09-13

## Current

Phase36 安全闭环九项（p36-01/02/03/04/05/06/07/12/13）均已实现、验证并合入本地 main（最新 c247ec2）。feature_list 319 项 = 270 completed + 49 not-started，active_feature=null。

后端版本化批准与修订失效链路完整：身份模型/审计表 → 单连接事务 approveChapter（幂等/守卫/比较提交）→ HTTP review-context/review-decisions → 修订事务内屏障重绑+批准失效+历史（doEdit 事务后吞异常 rebind 已删除）。

## Files

- 分支各自独立并 ff 合入 main。
- 证据：docs/harness/evidence/p36-0{1,2,3,4,5,6,7}-p36.json 与 p36-1{2,3}-p36.json；完成记录 completions.json 对应 verified 记录。
- 执行入口 docs/superpowers/plans/2026-09-12-collaboration-repair-entry.md；总 spec 与 p36–p40 分计划不变。

## Verification

- 后端四项 RED→GREEN→合并后复跑：全量 757/0/0/1skip；专项 4/4、8/8、4/4、5/5。
- p36-01：失败路径（未设URL/错误端口/空白页）非零退出；happy path 3 passed exit 0（场景模式服务 + 临时独立库）；前端构建通过。
- p36-05：RED 2 failed → GREEN review-version.spec 2 passed（双窗口竞态 + 390px 等价）。
- p36-12：基线 14/0 → 专项 17/17；并发严格 200/409、旧身份 409 新稿待审。
- p36-13：基线 9/0 → 专项 9/9；屏障恢复/工作台批准走版本化接口。
- p36-06：RED 4 failures（三类旁路实证）→ 专项 6/6 → 全量 766/0/0/1skip；resolve-review 与章节类 checkpoint approve/modify 旁路已关闭，modify 保留重写回到待审，初始设定/卷计划独立流程保留。
- 各项均无 prompt/上下文/生成输入改动，未跑 e2e/baseline，未 bless。

## Blockers

安全闭环成立：版本化批准是唯一章节放行路径。遗留观察项：OrchestratorController /resume 不在 p36-06 契约清单内（resumeFromCheckpoint 直接批准最新 pending checkpoint），如需收敛应另立 feature。真实评分/模型预算/bless 属执行时授权事项。reviewSetHash 的 issues/directives/dispositions 集合待 p37/p38。不得把后端测试当作端到端修复证据。真实评分/模型预算/bless 属执行时授权事项。

## Next Steps

1. 下一批特性：Phase36 剩余 UI 项 p36-08（目录/深链接/页签同步）、p36-09（草稿恢复/冲突保留）、p36-10（目标章数预览失效）、p36-11（成书清单诚实状态），依赖 p36-01 均已满足，逐个独立闭环。
2. 其后 p37 持久候选与意见证据按 dependencies 拓扑推进。
3. 保留工作区原有未跟踪文件，不推送远端。
