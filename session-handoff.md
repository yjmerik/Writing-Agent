# 协作修复实施交接（p36-02/03/04/07 已完成） — 2026-09-13

Last Updated: 2026-09-13

## Current

p36-02/03/04/07/01/05/12/13 共八项均已实现、验证并合入本地 main（最新 0f67a31）。feature_list 319 项 = 269 completed + 50 not-started，active_feature=null。

后端版本化批准与修订失效链路完整：身份模型/审计表 → 单连接事务 approveChapter（幂等/守卫/比较提交）→ HTTP review-context/review-decisions → 修订事务内屏障重绑+批准失效+历史（doEdit 事务后吞异常 rebind 已删除）。

## Files

- 分支各自独立并 ff 合入 main。
- 证据：docs/harness/evidence/p36-0{1,2,3,4,5,7}-p36.json 与 p36-1{2,3}-p36.json；完成记录 completions.json 对应 verified 记录。
- 执行入口 docs/superpowers/plans/2026-09-12-collaboration-repair-entry.md；总 spec 与 p36–p40 分计划不变。

## Verification

- 后端四项 RED→GREEN→合并后复跑：全量 757/0/0/1skip；专项 4/4、8/8、4/4、5/5。
- p36-01：失败路径（未设URL/错误端口/空白页）非零退出；happy path 3 passed exit 0（场景模式服务 + 临时独立库）；前端构建通过。
- p36-05：RED 2 failed → GREEN review-version.spec 2 passed（双窗口竞态 + 390px 等价）。
- p36-12：基线 14/0 → 专项 17/17；并发严格 200/409、旧身份 409 新稿待审。
- p36-13：基线 9/0 → 专项 9/9；屏障恢复/工作台批准走版本化接口，批准记录 identity 与被读章节一致。
- 各项均无 prompt/上下文/生成输入改动，未跑 e2e/baseline，未 bless。

## Blockers

UI 统一批准已完成；旧入口关闭（p36-06）依赖 p36-12/13 测试迁移。reviewSetHash 的 issues/directives/dispositions 集合待 p37/p38。不得把后端测试当作端到端修复证据。真实评分/模型预算/bless 属执行时授权事项。

## Next Steps

1. 下一特性 p36-06（关闭无版本批准的兼容旁路）：改 ContentController/CheckpointController + 新增 LegacyApprovalGateTest（RED：旧入口当前仍 200）。逐一向所有旧入口真实 POST 无 token 不得改变章/屏障/checkpoint；初始设定/卷计划审批保留独立流程；新版审批全量通过。
2. p36-06 完成即安全闭环（p36-02→06 链条齐备）。其余 UI 项 p36-08/09/10/11 依赖已满足。
3. 保留工作区原有未跟踪文件，不推送远端。
