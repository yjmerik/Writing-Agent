# Phase32 UI 验证发现的 4 项修复完成 — 2026-09-08

Last Updated: 2026-09-08

## Current（当前）

浏览器 UI 全流程验证（docs/reports/2026-09-08-ui-verification/UI验证记录.md）发现的 4 个问题已全部按 Bugfix gate 修复合入 main：p32-13 REST 修订同步派生刷新假死锁（c115248，定性从"所有权泄漏"更正为"同步 LLM 链阻塞响应/所有权十几分钟"）、p32-11 全书评价持久化+读回（ad9e444）、p32-12 所有权 409 与版本冲突 409 分级（673c020）、p32-14 /api no-store（73da06f）。加上此前 p32-01~05，feature_list 共 241 项：236 completed / 5 not-started（第二批 p32-06~09 + e2e 语料 p32-10）；active_feature=null，主工作区在 main=87962e4。

## Files（文件与证据）

状态源 [feature_list.json](feature_list.json)；完成索引 [docs/harness/completions.json](docs/harness/completions.json)（本轮新增 p32-11~14 四条 verified）；逐项证据 docs/harness/evidence/p32-1*-author-trust.json；spec [docs/specs/2026-09-08-author-trust-closed-loop.md](docs/specs/2026-09-08-author-trust-closed-loop.md)。

## Verification（验证）

- 每项先 RED 复现（p32-13 实测同步阻塞 4082ms；p32-11/12/14 均先失败）后 GREEN。
- 最终干净 main：mvn test 702/0失败/1跳过；前端构建通过；canary-mystery e2e COMPLETED + compare-baseline PASS；check-harness --check-git PASS。
- p32-13 行为：修订响应 <2.5s、所有权立即释放、摘要后台最终一致、异步失败发 CHAPTER_REFRESH_FAILED(async=true)。

## Blockers（阻塞与限制）

未推送远端。截图 CDP 超时未修（环境现象）。第二批（记忆分级 p32-06~09）与 e2e 语料分叉 p32-10 仍在 backlog。验证实例数据保留 data/verify-fullrun-0908.db（18100 服务已停）。

## Next Steps（下一步）

./init.sh 恢复（mvn 需 bash -c 'source scripts/load-env.sh'）。建议顺序：p32-10（恢复 e2e 零参数可跑）→ 第二批 p32-06 StoryFact 证据分级 → p32-07/08/09。UI 回归可复用 docs/reports/2026-09-08-ui-verification 的操作路径。
