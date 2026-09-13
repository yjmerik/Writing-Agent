# p36-04 版本化审阅接口已完成 — 2026-09-13

Last Updated: 2026-09-13

## Current

协作修复程序推进中：p36-02/03/04 均已按分支协议合入本地 main（p36-04 主分支提交 76dcfd0）。feature_list 319 项 = 264 completed + 55 not-started，active_feature=null。

p36-04 交付：`ReviewDecisionController` 提供 GET `/api/projects/{id}/chapters/{n}/review-context`（已读身份、reviewSetHash、屏障/章节类检查点、报告、既有批准的一致快照）与 POST `/review-decisions`（identity+reviewSetHash+idempotencyKey+reason → ChapterApproval.Result）。缺字段 400（服务端不补填版本）、跨项目身份 400、ReviewConflictException 映射 409 并返回当前身份/指纹供刷新；批准前获取项目工作权，WORK_OWNERSHIP_CONFLICT 沿用现有码。

## Files

- src/main/java/com/writing/novel/web/ReviewDecisionController.java：版本化审阅接口（新增）。
- src/test/java/com/writing/novel/web/VersionedReviewDecisionTest.java：4 项 MockMvc 契约测试（新增）。
- docs/harness/evidence/p36-02/03/04-p36.json：三特性证据；完成记录 completions.json → p36-0{2,3,4}-p36。

## Verification

- p36-04 RED（起始 b451f51，测试提交 11bc122）：4/4 失败（端点缺失，非编译失败）。
- p36-04 GREEN：专项 4/4（v2 读取→PUT 产生 v3→提交 v2 409 且 v3 待审、屏障 ACTIVE；缺字段 400；跨项目 400；同请求重试幂等 replayed=true）；全量 752 tests / 0 failures / 0 errors / 1 skipped。
- 合并（ff-only）后干净 main 复跑全量与专项均通过；`git merge-base --is-ancestor` 通过。
- 无 prompt/上下文/前端改动，按 quality_gate 未跑 e2e/baseline；未 bless。

## Blockers

安全闭环仍未完成：前端统一版本按钮与 409 刷新属 p36-05，旧无版本端点（resolve-review、章节类 checkpoint approve/modify）关闭属 p36-06；测试迁移 p36-12/13 之后才允许关闭旧入口。

## Next Steps

1. 入口计划顺序建议 p36-07（正式修订原子失效批准并记录屏障历史，依赖 p36-03 已完成）或转 p36-01 浏览器 QA 设施；二者与 p36-05 的依赖为 p36-01+p36-04。
2. p36-07 入口：先写 RevisionBarrierAtomicityTest RED（修订事务中屏障更新失败全回滚；v2 批准后编辑 v3，v2 批准仍可查询但不算 v3 批准；回退也生成新稿待审；无变更保存不新增历史），再改 SqliteNovelRepository.saveChapterRevision 与 ChapterRevisionService.doEdit（删除事务后吞异常的 rebind）。
3. 桌面 QA / 真实模型录制 / bless 等仍需执行时单独授权。
