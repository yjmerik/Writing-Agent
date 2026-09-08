# Phase32 UI 验证发现的 4 项修复完成 — 2026-09-08

Last Updated: 2026-09-08

## Current（当前）

UI 验证发现的 4 个问题（p32-11~14）与第二批前两项（p32-10 e2e 语料 preset 匹配、p32-06 StoryFact 证据分级 696cc6d）已合入 main。feature_list 共 242 项：238 completed / 4 not-started（p32-07/08/09 + **p32-15 e2e 回放语料与代码演化路径分叉**——LLM 输出非确定性使 canary-20260831 语料不再可靠，p32-06 合入后 e2e FAIL 即此因，取证见 feature evidence；修复方案——record 重建语料+bless，或 replay 归一化容忍——待用户决策）。active_feature=null，main=本轮 docs 提交。

## Files（文件与证据）

状态源 [feature_list.json](feature_list.json)；完成索引 [docs/harness/completions.json](docs/harness/completions.json)（本轮新增 p32-11~14 四条 verified）；逐项证据 docs/harness/evidence/p32-1*-author-trust.json；spec [docs/specs/2026-09-08-author-trust-closed-loop.md](docs/specs/2026-09-08-author-trust-closed-loop.md)。

## Verification（验证）

- 每项先 RED 复现（p32-13 实测同步阻塞 4082ms；p32-11/12/14 均先失败）后 GREEN。
- p32-06：FactEvidenceGradingTest 3 项 RED→GREEN；canon 系 29 项回归全过；main 全量 705/0失败/1跳过；check-harness --check-git PASS。
- e2e 零参数当前 FAIL（基线四项退化）——归因 p32-15 语料分叉（prompt 逐字一致但 LLM 输出历史不同），非 p32-06 引入；修复决策留待下轮。
- p32-13 行为：修订响应 <2.5s、所有权立即释放、摘要后台最终一致、异步失败发 CHAPTER_REFRESH_FAILED(async=true)。

## Blockers（阻塞与限制）

未推送远端。截图 CDP 超时未修（环境现象）。**p32-15 阻塞后续质量类改动的 e2e/基线验证**：p32-07（prompt 收紧）合入前必须先重建语料或实现 replay 容忍，否则无法按 DoD 验证。p32-08/09 不直接改 prompt，可在现有条件下验证。验证实例数据与 miss 取证保留（data/verify-fullrun-0908.db、data/e2e-fixtures/canary-mystery-20260831/*.miss.json）。

## Next Steps（下一步）

./init.sh 恢复（mvn 需 bash -c 'source scripts/load-env.sh'）。建议顺序：p32-10（恢复 e2e 零参数可跑）→ 第二批 p32-06 StoryFact 证据分级 → p32-07/08/09。UI 回归可复用 docs/reports/2026-09-08-ui-verification 的操作路径。
