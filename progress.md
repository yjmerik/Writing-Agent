# p36-03 原子批准比较提交已完成 — 2026-09-13

Last Updated: 2026-09-13

## Current

协作修复程序推进中：p36-02（不可变审阅身份与批准审计表）、p36-03（章节批准与屏障的原子比较提交）均已按分支协议合入本地 main（p36-03 主分支提交 28d1de9）。feature_list 319 项 = 263 completed + 56 not-started，active_feature=null。

p36-03 交付：SPI 新增 `readReviewContext`/`approveChapter`（缺省抛 UnsupportedOperationException 保持 stub 可编译）+ `ReviewConflictException`（携带当前身份/待审集合指纹供客户端刷新）；SqliteNovelRepository 实现单连接事务的原子批准：幂等重放→双重批准守卫→身份比较→reviewSetHash 比较→写批准审计→精确版本 DONE→只解决匹配稿号屏障（含 RESOLVED 历史）→处理同章章节类检查点→按剩余待审项退出 PAUSED_AT_CHECKPOINT。

## Files

- src/main/java/com/writing/novel/spi/NovelRepository.java：版本化审阅 SPI 契约与冲突异常。
- src/main/java/com/writing/novel/persistence/sqlite/SqliteNovelRepository.java：原子比较提交实现（事务内全部使用传入连接）。
- src/test/java/com/writing/novel/persistence/VersionedApprovalRepositoryTest.java：8 项契约测试（新增）。
- docs/harness/evidence/p36-02-p36.json、p36-03-p36.json：两特性 RED/GREEN/合并后证据。

## Verification

- p36-03 RED（起始 2914374，契约提交 1fc90b7）：8/8 errors（UnsupportedOperation，非编译失败）。
- p36-03 GREEN：专项 8/8（含 CyclicBarrier 真并发仅一方成功、BEFORE UPDATE 触发器注入 SQL 错误全回滚；连跑 4 次稳定）；全量 748 tests / 0 failures / 0 errors / 1 skipped。
- 两次合并（ff-only）后干净 main 复跑全量与专项均通过；`git merge-base --is-ancestor` 通过。
- 无 prompt/上下文/前端改动，按 quality_gate 未跑 e2e/baseline；未 bless。

## Blockers

旧窗口批准漏洞的产品修复仍未闭环：HTTP 层（review-context/review-decisions 接口）属 p36-04，前端接入属 p36-05，旧入口关闭属 p36-06；reviewSetHash 中 issues/directives/dispositions 为空集合，p37/p38 接入。勿将仓储层测试当作端到端修复证据。

## Next Steps

1. 下一特性选 p36-04（暴露携带版本的审阅上下文与批准接口，依赖 p36-02/03 已完成），从 main 新建 codex/p36-04-versioned-review-api 分支。
2. p36-04 先写 VersionedReviewDecisionTest RED：v2 读取→正式 PUT 产生 v3→提交 v2 必须 409 且 v3 待审；缺版本 400；其他项目不能批准；相同请求重试幂等。
3. 桌面 QA / 真实模型录制 / bless 等仍需执行时单独授权。
