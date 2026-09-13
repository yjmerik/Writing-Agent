# 协作修复实施交接（p36-02/03/04 已完成） — 2026-09-13

Last Updated: 2026-09-13

## Current

p36-02（不可变审阅身份与批准审计表，51d417a）、p36-03（章节批准与屏障的原子比较提交，28d1de9）、p36-04（携带版本的审阅上下文与批准接口，76dcfd0）均已实现、验证并合入本地 main。feature_list 319 项 = 264 completed + 55 not-started，active_feature=null。

后端版本化批准链路已就绪：ChapterApproval 身份模型 + chapter_approvals/review_barrier_history 表 + 单连接事务 approveChapter（幂等重放/双重批准守卫/身份与 reviewSetHash 比较/屏障按稿号解决并记历史/同章检查点/停点退出）+ GET review-context / POST review-decisions 接口（缺字段 400、跨项目 400、冲突 409 带当前权威状态、工作权沿用 WORK_OWNERSHIP_CONFLICT）。

## Files

- 分支 codex/p36-02-approval-schema（f7b36c2→51d417a）、codex/p36-03-atomic-approval（1fc90b7→28d1de9）、codex/p36-04-versioned-review-api（11bc122→76dcfd0），均已 ff 合入 main。
- 证据：docs/harness/evidence/p36-02/03/04-p36.json；完成记录 completions.json → p36-0{2,3,4}-p36（verified）。
- 执行入口 docs/superpowers/plans/2026-09-12-collaboration-repair-entry.md；总 spec 与 p36–p40 分计划不变。

## Verification

- 三项均 RED→GREEN→合并后干净 main 复跑：全量 752 tests / 0 failures / 0 errors / 1 skipped；专项 p36-02 4/4、p36-03 8/8（真并发单赢家、注入失败全回滚）、p36-04 4/4。
- 三项均无 prompt/上下文/前端改动，未跑 e2e/baseline，未 bless（quality_gate：无生成输入变更）。

## Blockers

旧窗口误批漏洞的前端侧仍未闭环：p36-05 前端统一已读版本按钮（依赖 p36-01 QA 设施 + p36-04）；旧入口关闭在 p36-06（依赖 p36-05/12/13）；p36-07 修订原子失效批准（依赖 p36-03，已满足）。reviewSetHash 的 issues/directives/dispositions 集合待 p37/p38。不得把后端接口测试当作端到端修复证据。真实评分/模型预算/bless 属执行时授权事项。

## Next Steps

1. 建议下一特性 p36-07（正式修订原子失效批准并记录屏障历史，依赖已满足）：先写 RevisionBarrierAtomicityTest RED——修订事务中屏障更新失败全回滚；v2 批准后编辑 v3，v2 批准仍可查询但不算 v3 批准；回退也生成新稿待审；无变更保存不新增历史；再改 SqliteNovelRepository.saveChapterRevision 与 ChapterRevisionService.doEdit（删除事务后吞异常的 rebind）。
2. 或转 p36-01（浏览器 QA 设施，独立），为 p36-05/08/09/10/11 做准备。
3. 保留工作区原有未跟踪文件（.zcode/plans、docs/reports/2026-09-05-ui-experience），不推送远端。
