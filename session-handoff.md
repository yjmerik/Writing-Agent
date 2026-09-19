# 审阅体验修复交接 — 2026-09-18

Last Updated: 2026-09-19

## Current

无进行中特性（active_feature=null）。p42-01 已完成合入 main：实现 879c90b + 测试门禁修复 a64d13a，完成记录 p42-01-p42（docs/harness/evidence/2026-09-18-p42-01.json）。
恢复入口：从 main 为下一特性新建独立分支；一轮只推进一个 feature。

## Files

- 特性实现：frontend/src/pages/ProjectWorkspace.tsx、frontend/src/components/InteractiveReview.tsx
- 测试：qa/collaboration/review-workspace.spec.ts（6 项）、src/test/java/com/writing/novel/agent/VectorCleanupOnRewritePathsTest.java
- 文档：docs/specs/2026-09-18-review-workspace-usability.md、docs/superpowers/plans/2026-09-18-review-workspace-usability.md
- 历史状态：docs/harness/archive/2026-09-18-pre-p42-01-*.md、2026-09-18-mid-p42-01-*.md

## Verification

干净检出（worktree --detach a64d13a）复验全部通过：mvn compile && mvn test 935 项 0 失败/0 错误/1 跳过；verify_cmd 6 项 Playwright（隔离 18089 服务）6/6；前端构建通过；check-harness --check-git PASS。
8080 正式服务是 a64d13a 构建的 jar，凤鸣九州数据完好；本轮验证只读，未改小说内容与审批状态。
服务运维注记：工具会话挂起会连带回收其派生的 java 子进程（09-19 晨两次复现）；重启需 `bash -c 'source scripts/load-env.sh && …'`（提供 DASHSCOPE_TOKEN_PLAN_API_KEY 等），并以 start_new_session/setsid 脱离进程组启动，否则会被再次回收。生产冒烟脚本 /tmp/p42-01-prod-smoke2.mjs 可复用。
环境注记：全新检出需先构建前端（生成 gitignore 的 src/main/resources/static）再跑全量测试，否则 ApiCacheControlTest 因缺首页 500；详见 evidence。
日志 /tmp/p42-01-*.log；QA 产物 /tmp/p42-01-qa-clean-main/。

## Blockers

无。
p40-05/p40-06 保持 deferred：缺真实严重错误计数、回放等待人工复核。

## Next Steps

等待用户在凤鸣九州复核新审阅界面；复核意见若产生新缺陷，按需求拆分协议登记新 feature。
启动新会话时先跑 ./init.sh 确认基线健康。
