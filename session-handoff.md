# 协作修复实施交接（p36-02/03/04/07 已完成） — 2026-09-13

Last Updated: 2026-09-13

## Current

Phase36 已完成十二项（安全闭环九项 + p36-08/09/10 UI 项），均合入本地 main（最新 5f96ad6）。feature_list 319 项 = 273 completed + 46 not-started，active_feature=null。

后端版本化批准与修订失效链路完整：身份模型/审计表 → 单连接事务 approveChapter（幂等/守卫/比较提交）→ HTTP review-context/review-decisions → 修订事务内屏障重绑+批准失效+历史（doEdit 事务后吞异常 rebind 已删除）。

## Files

- 分支各自独立并 ff 合入 main。
- 证据：docs/harness/evidence/p36-0{1..10}-p36.json 与 p36-1{2,3}-p36.json；完成记录 completions.json 对应 verified 记录。
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
- p36-10：RED（确认未绑定目标）→ GREEN plan-preview.spec 2 passed；预览绑定目标数字、序号丢弃乱序、确认后清 draft 重读大纲（保存总纲不复活裁剪章）、aria-label 补齐；fixture 走真实构思流程（协作start→批准初始设定→大纲落库→PUT 裁 24 章）。
- 各项均无 prompt/上下文/生成输入改动，未跑 e2e/baseline，未 bless。

## Blockers

安全闭环成立：版本化批准是唯一章节放行路径。遗留观察项：OrchestratorController /resume 不在 p36-06 契约清单内（resumeFromCheckpoint 直接批准最新 pending checkpoint），如需收敛应另立 feature。真实评分/模型预算/bless 属执行时授权事项。reviewSetHash 的 issues/directives/dispositions 集合待 p37/p38。不得把后端测试当作端到端修复证据。真实评分/模型预算/bless 属执行时授权事项。

## Next Steps

1. 最后一项：p36-11（成书清单区分流程完成与作者批准；改 frontend/src/components/BookReviewView.tsx + 新增 book-state.spec.ts；DONE 仅显示'正文流程完成'；无当前身份匹配批准记录时显示'尚未作者批准'；自动DONE/手工DONE/旧报告/缺报告四种响应分别渲染正确；不以 doneChapters===totalChapters 宣称覆盖完成；完整成书批准在 p38 接入）。
2. 其后 p37 持久候选与意见证据按 dependencies 拓扑推进。
3. 保留工作区原有未跟踪文件，不推送远端。
