# p36-07 修订原子失效批准已完成 — 2026-09-13

Last Updated: 2026-09-13

## Current

协作修复程序推进中：p36-02/03/04/07 均已按分支协议合入本地 main（p36-07 主分支提交 222d887）。feature_list 319 项 = 265 completed + 54 not-started，active_feature=null。入口计划的优先链 p36-02→03→04→07 已全部完成。

p36-07 交付：saveChapterRevision 同一事务内完成正文更新、快照插入、屏障旧身份审计（REVISED 历史）、ACTIVE 屏障重绑到新稿号；被替换稿已有作者批准时创建绑定新稿号的新屏障并把正文标记 NEEDS_MANUAL_REVIEW（批准失效按 identity 判定，历史批准行永不改写）。doEdit 事务后吞异常的 rebind 已删除；编辑/回退（RESTORE）统一经此边界。

## Files

- src/main/java/com/writing/novel/persistence/sqlite/SqliteNovelRepository.java：事务内重绑/失效实现。
- src/main/java/com/writing/novel/service/ChapterRevisionService.java：删除事务后吞异常 rebind。
- src/test/java/com/writing/novel/service/RevisionBarrierAtomicityTest.java：5 项契约测试（新增）。
- docs/harness/evidence/p36-02/03/04/07-p36.json：四特性证据；完成记录 completions.json → p36-0{2,3,4,7}-p36。

## Verification

- p36-07 RED（起始 4815b68，测试提交 f60ce4b）：4 failures（批准后修订无重审屏障、注入失败不回滚、重绑无历史、回退不生成待审新稿）+ 1 基线守卫通过（幂等空操作）。
- p36-07 GREEN：专项 5/5；全量 757 tests / 0 failures / 0 errors / 1 skipped（含既有 p32/p34 rebind 行为测试全兼容）。
- 合并（ff-only）后干净 main 222d887 复跑全量与专项均通过；`git merge-base --is-ancestor` 通过。
- 无 prompt/上下文/前端改动，按 quality_gate 未跑 e2e/baseline；未 bless。

## Blockers

安全闭环剩余项：p36-01（浏览器 QA 设施）→ p36-05（前端统一已读版本按钮，依赖 p36-01+04 均已完成）→ p36-12/13（测试迁移）→ p36-06（关闭旧入口）。p36-07 的候选路径接入属 p37-02/14。

## Next Steps

1. 建议下一特性 p36-01（建立真实浏览器回归入口，无依赖）：qa/collaboration 独立 npm 包 + @playwright/test 锁定精确版本 + smoke.spec.ts（QA_BASE_URL 仅允许 localhost，错误端口/空白页/页面异常非零退出，产物写 QA_ARTIFACT_DIR）。浏览器安装仅发生在此特性。
2. p36-01 完成后 p36-05 依赖即满足（p36-01+04），可做前后端闭环。
3. 桌面 QA / 真实模型录制 / bless 等仍需执行时单独授权。
