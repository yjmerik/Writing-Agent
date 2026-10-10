# 叙事质感架构落地 Phase1（NCS-187..189） — 2026-10-09

Last Updated: 2026-10-10

## Current

- 《山河执棋人》质量根因（剧情快/对话乒乓/缺描写转场）落为正式 spec：
  docs/specs/2026-10-09-narrative-quality-architecture.md（六组件闭环控制设计，
  NCS-187..200 已登记 feature_list，phase 56）
- Phase 1「看见」三个 feature 全部完成并合入 main：
  - NCS-187 纹理遥测（03a591b）：StyleMetricsService.computeTexture——对话段占比/
    成段叙述块/场景分隔/对白乒乓/均段长，写章后 TEXTURE_METRICS 事件（observe-only）
  - NCS-188 节拍算术（91ddf37）：parseChapterDetails 超限节拍合并裁剪+OUTLINE_BEATS_TRIMMED
    事件；修 preset genre/maxBeats 未生效 bug（历史项目 4→3）
  - NCS-189 反馈链小修（ab72f33）：反馈截断 80→200 字；readback pacing 节奏观察透传
- 基线修复顺带完成：NCS-185 completions.json 记录补登、NCS-186 spec 指针修正
  （b3575e6，docs 分支）

## Files

- spec：docs/specs/2026-10-09-narrative-quality-architecture.md
- main 链：b3575e6(登记) → 03a591b(NCS-187) → dcce672(回执) → 91ddf37(NCS-188) →
  8e1fe3b(回执) → ab72f33(NCS-189) → 7594cea(回执)
- 实测数据：对话段占比 77%/均段 17.3 字/引号字符 37%（低于 50% 指令却无人测量）——
  证据在 spec 背景节 + docs/reports/2026-10-09-novel-eval-shanhezhiqiren/

## Verification

- 每个 feature：TDD 先红后绿；分支全量 + 合并后 main 全量均绿
- 最终 main：1501 tests / 0 failures / 0 errors / 1 skipped（既有）
- python3 scripts/check-harness.py PASS（531 features：485 completed / 35 not-started / 11 deferred）

## Blockers

- 无

## Next Steps

- NCS-190 NarrativePolicy 域对象+题材默认+持久化+REST（Phase 2 起点，无依赖可直接开）
- 之后 NCS-191/192（约束统一读 policy + 模板节奏改版——注意 p32-15 需 record 重建 fixture）
- 既有 backlog 不变：NCS-186（e2e 屏障）/NCS-183/184/NCS-179/180/177
