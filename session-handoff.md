# 第二轮旅程修复九连（NCS-087..095）完成 — 2026-09-26

Last Updated: 2026-09-26

## Current

第二轮武侠旅程（《沉钟记》）全部 9 项缺陷修复完毕并合入 main，A-8 终局死锁解除。
每个特性独立分支（codex/ncs-0XX-<slug>）、失败测试先行（RED→GREEN）、
ff-only 合并、干净 main 复验、verified 登记（evidence 9 份）。

| 特性 | 分支 | 提交 |
|---|---|---|
| NCS-088 担责豁免（AUTHOR_WAIVED） | codex/ncs-088-author-waiver-check-envelope | 27c32c3 |
| NCS-087 recheck 按检查器路由 | codex/ncs-087-recheck-checker-routing | d029b19 |
| NCS-089 回收卷追加解禁写作 | codex/ncs-089-recovery-volume-resumes | d60fd23 |
| NCS-093 预览截断守护+900s 超时 | codex/ncs-093-revision-preview-truncation-guard | 1a97381 |
| NCS-091 recheck/checkpoint 遥测归属 | codex/ncs-091-recheck-checker-telemetry | a15571d |
| NCS-092 压缩强制处置关键承诺+MIGRATE 回写 | codex/ncs-092-compression-promise-ledger | f76042f |
| NCS-095 章节字数趋势预警 | codex/ncs-095-word-trend-warning | b621819 |
| NCS-090 框架修订人物层同步 | codex/ncs-090-initial-setup-character-sync | 2a6fcc7 |
| NCS-094 recheck/preview 异步任务化 | codex/ncs-094-long-task-async | d4aabe9 |

（NCS-089/092 的收尾 docs 提交：f8a50d8 / 81dd9d4 / 17433e1；其余同型。）

## Files

spec: docs/specs/2026-09-26-journey2-fixes.md（根因+设计+验收）
报告: docs/reports/2026-09-25-ui-wuxia-journey-2/体验报告.md
新增主要代码：CheckWaiverService / LongTaskService / NovelOrchestrator
（recheck 路由、syncCharactersFromOutline、回收卷置回 WRITING、
buildChapterQualityReport 字数趋势）
测试净增 26（1231→1257），全量 1257/0F/0E/1S；npm build + tsc 通过。

## Verification

每特性：失败测试复现 → 修复 GREEN → 分支全量 → ff-only 合并 → 干净 main 复跑
compile+全量（最终 1257/0F/0E/1S）→ register-completion.py 登记
（docs/harness/evidence/2026-09-26-ncs-09*.json）。
无 .st 模板改动；人物同步走既有 character-design 模板，未触发 e2e 基线重建。
18100 现场已用新构建重启，《沉钟记》NEEDS_REVISION 现场完好，等待人工 UI 验收。

## Blockers

无（业务）。ncs-070 检查器 schema 遗留待用户裁决。

## Next Steps

1. 人工 UI 验收（18100）：质量页「重新检查」重检 3×consistency-guard →
   「担责豁免」处理 2×correction-verification → 继续协作写回收卷 13-20 →
   台账回收 mustResolve 至 ≤3 → 宣告完结/重审到 FINAL。
2. 重启命令：bash -c 'source scripts/load-env.sh && java -jar
   target/writing-agent-0.1.0-SNAPSHOT.jar --server.port=18100
   --persistence.sqlite.db-path=./data/ui-wuxia-0925-journey/novel.db
   --persistence.vector-store.index-path=./data/ui-wuxia-0925-journey/vectors.idx'
3. 重建 jar 前先停 18100/18098 实例（热替换运行中 fat jar 会损坏 JVM）。
4. ncs-070 检查器 schema 遗留待用户裁决。
