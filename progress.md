# Phase-54 review 整改批次完成 — 2026-09-30

Last Updated: 2026-09-30

## Current

phase 54（review 整改）全部 11 项完成并合入 main（NCS-154..164，active_feature=None）：
批准制修复（154 预览-确认链 + 155 UI）、FAILED_RETRYABLE（156 类型化异常 +
157 终态/手动重投）、保存门禁（162）、内联裁决（163）、卫生类（158/159/160/161）、
登记可信度（164：10 处 verify_cmd 修正 + 13 条台账补建 + ncs-070 修复 +
check-verify-cmds.py 校验脚本）。backlog 剩 NCS-152/153（O-2/O-3 prompt 项，
各自独立 e2e）。

## Files

- main 提交：154→6a72d5a、155→dea6e65、156→0146765(+157 5723c35 同分支链)、
  162→ef9ca6b、163→6bba0e6、158→5dcd77d、159→072520e、160→33c0e57、161→81885b9
  （各 feature 独立 codex 分支 ff-merge，台账/evidence 随每个 docs 提交落库）
- spec：docs/specs/2026-09-30-phase53-review-remediation.md

## Verification

- 全量 mvn test 1462/0F/0E/1S（main=81885b9）；npm build 通过
- python3 scripts/check-harness.py PASS（零 FAIL）；check-verify-cmds.py 285 引用全过
- e2e（154/161 各对照干净 main 基线）：产出逐字节一致（ch1 v2 3288字
  sha=b8742f985e1f8b1f）= 零分叉，语料未重建；compare-baseline 双侧 PASS
- 已知稳态：e2e.sh --acceptance full 在 replay 因第 1 章人审屏障失败，
  与 golden（minChapters=1，含人审屏障验收轮）一致，属语料验收态非回归

## Blockers

- 无

## Next Steps

- NCS-152（人物性格母题去重）/153（场景锚点）：prompt 项，各自 e2e record+对比
- 155/163 的浏览器实机走查待下次真实 checkpoint 修订 / 批准被拒场景补录
- 《剑冢守夜人》第 10 章批准续跑待用户操作（18095 实例旧包，重载需重建 jar）
