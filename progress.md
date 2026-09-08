# Phase32 第一批 P1（作者信任闭环）完成 — 2026-09-08

Last Updated: 2026-09-08

## Current（当前）

体验报告 `docs/reports/2026-09-05-ui-experience/体验报告.md` 的 P1 前三组问题（#2 保存一致性、#1 计划约束、#3 评审版本绑定）已按 spec `docs/specs/2026-09-08-author-trust-closed-loop.md` 拆为 p32-01~05 五个 feature，全部独立分支实现、ff 合入本地 main（HEAD b75b819 + docs 回执）并逐项登记 verified 完成记录。feature_list 共 237 项：232 completed / 5 not-started（p32-06~09 第二批记忆分级、p32-10 e2e 语料分叉既有 bug）；active_feature=null。临时 worktree 已清理，主工作区在 main。

## Files（文件与证据）

唯一状态源 [feature_list.json](feature_list.json)；完成索引 [docs/harness/completions.json](docs/harness/completions.json)，逐项证据 `docs/harness/evidence/p32-0*-author-trust.json`（commit/cleanCheckout/checks）。实现提交：p32-01=552f880、p32-02=5545160、p32-03=f25fba4、p32-04=7516bde、p32-05=b75b819。

## Verification（验证）

- 最终干净 main 树全量 694 tests / 0 failures / 0 errors / 1 skipped；各 feature verify_cmd 单独通过。
- `E2E_FIXTURE_DIR=data/e2e-fixtures/canary-mystery-20260831 ./scripts/e2e.sh`：构思 WRITING、3 章写作 COMPLETED；`python3 scripts/compare-baseline.py` PASS，golden 未改。
- 前端构建通过（p32-01 文案分级）；`python3 scripts/check-harness.py --check-git` PASS。
- 行为变化：派生刷新失败仍 2xx+`derivedRefreshPending`+`CHAPTER_REFRESH_FAILED`；自动重写经版本门+`CHAPTER_AUTHOR_CONFLICT`；卷计划章号区间门+`PLAN_VOLUME_RANGE_REJECTED`；章节落 `plan_ref=volume=N;synopsis=<sha12>`；质量报告输出 `stale/staleIssueCount/reviewStale`。

## Blockers（阻塞与限制）

未推送远端。e2e.sh 不带 E2E_FIXTURE_DIR 时按 mtime 自动选语料，与当前 prompt 指纹分叉（基线 2b8677e 对照同样分叉，属既有问题），登记为 p32-10。第二批记忆分级（p32-06~08 涉及 fact-harvest.st/伏笔 detector 行为）与评审 UI 展示 stale 尚未实现。

## Next Steps（下一步）

从 main 运行 `./init.sh` 恢复。按 spec「建议的后续顺序」推进第二批：p32-06 StoryFact 证据分级 → p32-07 收割规则收紧 → p32-08 伏笔回收证据 → p32-09 提醒去重；或先修 p32-10 恢复 e2e 零参数可跑。新需求仍走 spec→feature 协议。
