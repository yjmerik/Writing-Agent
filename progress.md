# p36-07 修订原子失效批准已完成 — 2026-09-13

Last Updated: 2026-09-13

## Current

协作修复程序推进中：p36-02/03/04/07/01/05/12/13 共八项按分支协议合入本地 main（p36-13 主分支提交 0f67a31）。feature_list 319 项 = 269 completed + 50 not-started，active_feature=null。

p36-07 交付：saveChapterRevision 同一事务内完成正文更新、快照插入、屏障旧身份审计（REVISED 历史）、ACTIVE 屏障重绑到新稿号；被替换稿已有作者批准时创建绑定新稿号的新屏障并把正文标记 NEEDS_MANUAL_REVIEW（批准失效按 identity 判定，历史批准行永不改写）。doEdit 事务后吞异常的 rebind 已删除；编辑/回退（RESTORE）统一经此边界。

## Files

- src/main/java/com/writing/novel/persistence/sqlite/SqliteNovelRepository.java：事务内重绑/失效实现。
- src/main/java/com/writing/novel/service/ChapterRevisionService.java：删除事务后吞异常 rebind。
- src/test/java/com/writing/novel/service/RevisionBarrierAtomicityTest.java：5 项契约测试（新增）。
- docs/harness/evidence/p36-02/03/04/07-p36.json：四特性证据；完成记录 completions.json → p36-0{2,3,4,7}-p36。

## Verification

- p36-07 RED 4 failures + 1 基线守卫；GREEN 5/5，全量 757/0/0/1skip。
- p36-01：失败路径非零退出 + happy path 3 passed exit 0。
- p36-05：RED（改动前 bundle）2 failed 无统一批准按钮；GREEN review-version.spec 2 passed——双真实窗口 v2/v3 竞态（A 批准409→不能续写→强制重读→重新批准200→续写解锁）在 1280x900 与 390x844 均通过，fixture 走真实作者路径。
- p36-12：基线 14/0 → 迁移专项 17/17 → 全量 760/0/0/1skip。
- p36-13：基线 9/0 → 迁移专项 9/9（屏障恢复/工作台批准走版本化接口，批准记录 identity 与被读章节一致，屏障夹具显式落盘）→ 全量 760/0/0/1skip。
- 各项均无 prompt/上下文/生成输入改动，按 quality_gate 未跑 e2e/baseline；未 bless。

## Blockers

安全闭环仅剩 p36-06（关闭旧无版本端点）。测试迁移（p36-12/13）已完成，关闭后全量回归依然有效。p36-07 的候选路径接入属 p37-02/14。

## Next Steps

1. 下一特性 p36-06（关闭无版本批准的兼容旁路；依赖 p36-05/12/13 至此全部完成）——安全闭环成型。其余 UI 项（p36-08/09/10/11）依赖已满足可按序推进。
2. 桌面 QA / 真实模型录制 / bless 等仍需执行时单独授权。
