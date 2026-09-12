# 协作修复实施交接（p36-02 已完成） — 2026-09-13

Last Updated: 2026-09-13

## Current

p36-02（定义不可变审阅身份与批准审计表）已实现、验证并合入本地 main（51d417a，fast-forward）。feature_list 319 项 = 262 completed + 57 not-started，active_feature=null。

交付内容：`ChapterApproval` 不可变批准审计模型（Identity/Request/Result/ReviewContext 内嵌记录；contentHash=SHA-256/UTF-8 标题与正文字节长度前缀哈希，64位小写hex）；SQLite `chapter_approvals`（UNIQUE(project_id, idempotency_key)，DONE 不回填）与 `review_barrier_history`（追加式）两表及索引。

## Files

- 分支 codex/p36-02-approval-schema：f7b36c2（契约+RED 测试）→ 51d417a（schema 实现），已合入 main。
- 证据：docs/harness/evidence/p36-02-p36.json；完成记录 completions.json → p36-02-p36（verified）。
- 执行入口仍是 docs/superpowers/plans/2026-09-12-collaboration-repair-entry.md；总 spec 与 p36–p40 分计划不变。

## Verification

- RED（起始 8e1e715）：`mvn test -Dtest=ApprovalSchemaTest` 3 errors（no such table: chapter_approvals），行为性失败。
- GREEN：专项 4/4；`mvn compile && mvn test` 740 tests / 0 failures / 0 errors / 1 skipped。
- 合并后干净 main 51d417a 复跑全量与专项通过；`git merge-base --is-ancestor 51d417a main` 通过。
- 无 prompt/上下文/前端改动，未跑 e2e/baseline，未 bless（quality_gate：无生成输入变更）。

## Blockers

旧窗口正文v2可放行另一窗口v3的产品漏洞仍然存在：p36-02 只提供身份与审计存储，批准消费路径在 p36-03/04，旧入口关闭在 p36-06；不得把本项测试当作漏洞修复证据。临时测试服务已关闭，原稿/原服务保留。真实评分/模型预算/bless 属执行时授权事项。

## Next Steps

1. 从 main 新建 codex/p36-03-atomic-approval 分支实现 p36-03（readReviewContext/approveChapter 原子比较提交），先写 VersionedApprovalRepositoryTest RED：两连接竞争仅一方成功、同 key 重试同 approvalId、同 key 异请求冲突、旧 version/hash/reviewSetHash 拒绝、批准行写入后注入 SQL 错误全回滚。
2. 单连接事务约束：不得在事务内调用另开连接的公共仓储方法；SPI 缺省实现抛 UnsupportedOperationException 保持旧 stub 可编译。
3. 保留工作区原有未跟踪文件（.zcode/plans、docs/reports/2026-09-05-ui-experience），不推送远端。
