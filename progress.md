# p36-07 修订原子失效批准已完成 — 2026-09-13

Last Updated: 2026-09-13

## Current

协作修复程序推进中：Phase36 安全闭环九项（p36-01/02/03/04/05/06/07/12/13）全部按分支协议合入本地 main（p36-06 主分支提交 c247ec2）。feature_list 319 项 = 270 completed + 49 not-started，active_feature=null。

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
- p36-06：RED 4 failures（resolve-review/approve 实测200、modify 默认批准、CONTINUITY modify 200）→ GREEN 专项 6/6（旧入口409零变更、modify保留重写回到待审、初始设定独立流程保留、版本化入口全量可用）→ 全量 766/0/0/1skip；两个既有测试按'现有测试适配'条款适配并记录。
- 各项均无 prompt/上下文/生成输入改动，按 quality_gate 未跑 e2e/baseline；未 bless。

## Blockers

旧窗口/旧端点误批准新稿的漏洞已闭环：版本化批准为唯一章节放行路径（resolve-review 与章节类 checkpoint approve/modify 旁路已关闭）。OrchestratorController /resume 直接批准最新 pending checkpoint 不在本项契约清单内，如需收敛另立项。p36-07 的候选路径接入属 p37-02/14。

## Next Steps

1. Phase36 剩余 UI 项 p36-08/09/10/11（依赖 p36-01 已满足）按序推进；其后 p37 起按 dependencies 拓扑。
2. 桌面 QA / 真实模型录制 / bless 等仍需执行时单独授权。
