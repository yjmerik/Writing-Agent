# p36-02 不可变审阅身份与批准审计表已完成 — 2026-09-13

Last Updated: 2026-09-13

## Current

协作修复程序进入实施阶段：p36-02（定义不可变审阅身份与批准审计表）已按分支协议完成并合入本地 main（51d417a），feature_list 319 项 = 262 completed + 57 not-started，active_feature=null。

新增 `ChapterApproval`（Identity/Request/Result/ReviewContext 内嵌记录 + contentHash：SHA-256/UTF-8 标题与正文的字节长度前缀哈希，64位小写hex，不含状态/updatedAt）；SQLite 新增 `chapter_approvals`（UNIQUE(project_id, idempotency_key)，DONE 不回填）与 `review_barrier_history`（追加式屏障身份历史）两表及索引。

## Files

- src/main/java/com/writing/novel/domain/ChapterApproval.java：不可变审阅身份与批准审计模型（新增）。
- src/main/java/com/writing/novel/persistence/sqlite/SqliteSchemaInitializer.java：两新表 DDL 与索引。
- src/test/java/com/writing/novel/persistence/ApprovalSchemaTest.java：4 项契约测试（新增）。
- docs/harness/evidence/p36-02-p36.json：RED/GREEN/合并后验证证据。

## Verification

- RED（main 8e1e715 起，契约提交 f7b36c2）：`mvn test -Dtest=ApprovalSchemaTest` 3 errors（no such table: chapter_approvals），行为性失败非编译失败。
- GREEN：同命令 4/4 通过；`mvn compile && mvn test` 740 tests / 0 failures / 0 errors / 1 skipped。
- 合并（ff-only）后在干净 main 51d417a 复跑全量与专项均通过；`git merge-base --is-ancestor` 通过。
- 无 prompt/上下文/前端改动，按 quality_gate 未跑 e2e/baseline；未 bless。

## Blockers

无。旧窗口批准新稿的漏洞在产品上仍未修复——批准的消费路径（readReviewContext/approveChapter）属 p36-03，安全闭环需 p36-03→04→05→06 完成，勿将本项测试当作漏洞修复证据。

## Next Steps

1. 下一特性按入口计划优先级选 p36-03（实现章节批准与屏障的原子比较提交，依赖 p36-02 已完成），从 main 新建 codex/p36-03-* 分支。
2. 若转做 p36-01（浏览器 QA 设施）无业务依赖冲突；两者仍须逐个闭环。
3. 桌面 QA / 真实模型录制 / bless 等仍需执行时单独授权。
