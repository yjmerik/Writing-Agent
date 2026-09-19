# NCS 方案进度 — 2026-09-19

Last Updated: 2026-09-19

## Current

Codex 已核对并接收 ZCode 工作。当前 Phase 49，NCS 完成 27/30：M1–M5 完成，M6 完成 NCS-027。
事实依据为 feature_list.json、completions.json、已提交证据与 main 祖先关系；旧进度停留于 21/30，已归档。
接手基点 main=9348eac；最近实现 NCS-027=4864435，已合入 main。
active_feature=null；未开始 NCS-028/029/030。本次仅核验与交接维护，未开始业务实现。

## Files

- 方案：docs/specs/2026-09-19-novel-complete-solution.md
- 完成证据：docs/harness/evidence/2026-09-19-ncs-001.json 至 ncs-027.json
- NCS-027：frontend/src/components/EditionsPanel.tsx、BookReviewView.tsx、api/client.ts、types.ts
- 下一入口：BookEditionController.approve；当前核对 manifestHash、活动屏障及快照章节批准，尚无绑定式校验报告门禁。
- 接手时已有未跟踪材料保留：.zcode/plans/plan-sess_8dbf5c3f-54dd-45b9-88b9-e02cd5e0b153.md、docs/reports/2026-09-05-ui-experience/。
- 旧交接已归档至 docs/harness/archive/2026-09-19-pre-codex-takeover-*.md。

## Verification

- python3 scripts/check-harness.py --check-git：PASS，361 项，356 completed / 2 deferred / 3 not-started。
- 本次 Java 21 运行 ./init.sh PASS：编译通过；1074 tests / 0 failures / 0 errors / 1 skipped；前端构建通过（既有大包提示）。本地日志 /tmp/writingagent-takeover-init-jdk21-20260919.log。
- 本次未重跑 E2E、文学质量评估或浏览器 Journey；既有记录不等于本次重新验收。

## Blockers

- p40-05/06 保持 deferred：人工评分缺 severeContinuityErrors，需补独立逐章计数后严格重评。
- 默认 Maven 采用 Java 26，受限执行时 Mockito 自附加失败；接手验证改用已有 Java 21 并允许测试进程正常附加。

## Next Steps

1. 从 main 新建 codex/ncs-028-edition-validation；依赖 NCS-027 已合入。
2. 先写 EditionValidationTest 失败用例：报告绑定 manifestHash、窗口外 HIGH 问题仍计入、无有效报告不可定稿、新稿不继承旧报告。
3. 实现并通过专项及完整验证，提交、合入 main、干净主分支复验、登记完成。
4. 再依次推进 NCS-029 任务与版本中心 UI、NCS-030 联合浏览器验收；不能跳过最终验收宣称方案完成。
