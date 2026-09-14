# p36-07 修订原子失效批准已完成 — 2026-09-13

Last Updated: 2026-09-13

## Current

Phase36 全部 13 项完成；Phase37 已启动：p37-01（持久修订候选模型与迁移）合入本地 main（bfdabf7）。feature_list 319 项 = 275 completed + 44 not-started，active_feature=null。

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
- p36-13：基线 9/0 → 迁移专项 9/9（屏障恢复/工作台批准走版本化接口，批准记录 identity 与被读章节一致）。
- p36-06：RED 4 failures → GREEN 专项 6/6 → 全量 766/0/0/1skip；两个既有测试按'现有测试适配'条款适配并记录。
- p36-08：RED 6 failed → GREEN workspace-navigation.spec 6 passed；合并后复跑通过。
- p36-09：RED 2 failed → GREEN draft-recovery.spec 4 passed；合并后复跑通过。
- p36-10：RED → GREEN plan-preview.spec 2 passed（预览绑定/乱序丢弃/确认后裁剪不复活）；fixture 经真实构思流程。
- p36-11：RED 2 failed → GREEN book-state.spec 2 passed；合并后复跑通过。
- p37-01：RED 3 errors（no such table: revision_proposals）→ GREEN 专项 5/5（重复迁移无损、嵌套JSON/换行逐字节恢复、跨项目 requestKey 隔离、APPLIED 终态守卫）；RevisionProposal 领域模型 + revision_proposals 表（UNIQUE(project_id, request_key)，无 TTL）。合并后 main 全量 771/0/0/1skip。
- 各项均无 prompt/上下文/生成输入改动，按 quality_gate 未跑 e2e/baseline；未 bless。

## Blockers

Phase36 收官：版本化批准为唯一章节放行路径，UI 导航/草稿/预览/成书状态诚实化完成。遗留观察项：OrchestratorController /resume 不在 p36-06 契约清单内。真实评分/模型预算/bless 属执行时授权事项。

## Next Steps

1. Phase36 剩余 UI 项 p36-08/09/10/11（依赖 p36-01 已满足）按序推进；其后 p37 起按 dependencies 拓扑。
2. 桌面 QA / 真实模型录制 / bless 等仍需执行时单独授权。
