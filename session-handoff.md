# 协作修复实施交接（p36-02/03/04/07 已完成） — 2026-09-13

Last Updated: 2026-09-13

## Current

p36-02（51d417a）、p36-03（28d1de9）、p36-04（76dcfd0）、p36-07（222d887）、p36-01（57e6d5a）均已实现、验证并合入本地 main。入口计划优先链 p36-02→03→04→07 与测试设施 p36-01 完成。feature_list 319 项 = 266 completed + 53 not-started，active_feature=null。

后端版本化批准与修订失效链路完整：身份模型/审计表 → 单连接事务 approveChapter（幂等/守卫/比较提交）→ HTTP review-context/review-decisions → 修订事务内屏障重绑+批准失效+历史（doEdit 事务后吞异常 rebind 已删除）。

## Files

- 分支 codex/p36-02/03/04/07/01 各自独立并 ff 合入 main。
- 证据：docs/harness/evidence/p36-0{1,2,3,4,7}-p36.json；完成记录 completions.json 对应 verified 记录。
- 执行入口 docs/superpowers/plans/2026-09-12-collaboration-repair-entry.md；总 spec 与 p36–p40 分计划不变。

## Verification

- 后端四项 RED→GREEN→合并后复跑：全量 757/0/0/1skip；专项 4/4、8/8、4/4、5/5。
- p36-01：失败路径（未设URL/错误端口/空白页）非零退出；happy path 3 passed exit 0（场景模式服务 + 临时独立库）；前端构建通过。
- 五项均无 prompt/上下文/生成输入改动，未跑 e2e/baseline，未 bless。

## Blockers

UI 侧验证与旧入口关闭未完成：p36-05 依赖已满足（p36-01+04）；p36-06 依赖 p36-05/12/13。reviewSetHash 的 issues/directives/dispositions 集合待 p37/p38。不得把后端测试当作端到端修复证据。真实评分/模型预算/bless 属执行时授权事项。

## Next Steps

1. 下一特性 p36-05（所有章节审阅按钮使用同一已读版本）：修改 frontend/src/api/client.ts + InteractiveReview.tsx，新增 qa/collaboration/review-version.spec.ts；QA 工具用 `npm --prefix qa/collaboration ci` 复用锁文件（勿再 install）。双真实窗口 A显示v2/B保存v3→A批准409且不能续写→显式刷新后可批；409保留草稿强制重读；重试复用idempotencyKey；390px 等价。
2. 之后 p36-12/13 迁移既有测试 → p36-06 关闭旧入口，完成安全闭环。
3. 保留工作区原有未跟踪文件（.zcode/plans、docs/reports/2026-09-05-ui-experience），不推送远端。
