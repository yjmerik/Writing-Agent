# p36-07 修订原子失效批准已完成 — 2026-09-13

Last Updated: 2026-09-13

## Current

协作修复程序推进中：p36-02/03/04/07/01 共五项按分支协议合入本地 main（p36-01 主分支提交 57e6d5a）。feature_list 319 项 = 266 completed + 53 not-started，active_feature=null。入口计划优先链 p36-02→03→04→07 与 p36-01 均完成；p36-05 的依赖（p36-01+04）已满足。

p36-07 交付：saveChapterRevision 同一事务内完成正文更新、快照插入、屏障旧身份审计（REVISED 历史）、ACTIVE 屏障重绑到新稿号；被替换稿已有作者批准时创建绑定新稿号的新屏障并把正文标记 NEEDS_MANUAL_REVIEW（批准失效按 identity 判定，历史批准行永不改写）。doEdit 事务后吞异常的 rebind 已删除；编辑/回退（RESTORE）统一经此边界。

## Files

- src/main/java/com/writing/novel/persistence/sqlite/SqliteNovelRepository.java：事务内重绑/失效实现。
- src/main/java/com/writing/novel/service/ChapterRevisionService.java：删除事务后吞异常 rebind。
- src/test/java/com/writing/novel/service/RevisionBarrierAtomicityTest.java：5 项契约测试（新增）。
- docs/harness/evidence/p36-02/03/04/07-p36.json：四特性证据；完成记录 completions.json → p36-0{2,3,4,7}-p36。

## Verification

- p36-07 RED（起始 4815b68，测试提交 f60ce4b）：4 failures + 1 基线守卫通过；GREEN 专项 5/5，全量 757/0/0/1skip。
- p36-01：qa/collaboration 独立包（@playwright/test 锁定 1.63.0）；失败路径（未设URL/错误端口/空白页）均非零退出；场景模式服务 happy path 3 passed exit 0；合并后复跑验证通过。
- 两项均无 prompt/上下文/生成输入改动，按 quality_gate 未跑 e2e/baseline；未 bless。

## Blockers

安全闭环剩余项：p36-05（前端）→ p36-12/13（测试迁移）→ p36-06（关闭旧入口）。p36-07 的候选路径接入属 p37-02/14。

## Next Steps

1. 下一特性 p36-05（所有章节审阅按钮使用同一已读版本，依赖 p36-01+04 已完成）：前端 api.getReviewContext/approveChapterRevision + InteractiveReview 统一「批准当前稿」handler + qa/collaboration/review-version.spec.ts（双窗口 v2/v3 竞态、409 刷新、390px 等价）。
2. 后续 p36-12/13 测试迁移后 p36-06 关闭旧入口，安全闭环成型。
3. 桌面 QA / 真实模型录制 / bless 等仍需执行时单独授权。
