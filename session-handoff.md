# Session Handoff

## Current Objective

- **Goal**: Phase 15 人机设定扩容与作业可观测 + 回放确定性收尾。
- **Current status**: **9/9 完成**（p15-08/p15-09 已实现入库并回填实跑证据）。backlog 耗尽。
- **Spec**: `docs/specs/2026-08-18-hitl-expansion.md`、`docs/specs/2026-08-22-isolated-e2e-fixtures.md`、`docs/specs/2026-08-22-replay-determinism.md`
- **Branch**: `phase-11-quality-gate-architecture`（未 push，无 upstream）

## Completed

前序：p15-01（HitlCreateWorldCharacterTest 3 pass）、p15-02（HitlExpandAgentsTest 2 pass）。

2026-08-22 复审原子入库（此前登记 evidence 但实现留在工作树的部分 + 新登记的 p15-08/09；混合文件按 hunk 级拆分提交）：

| id | commits | 证据 |
|---|---|---|
| p15-03 | `8e19ac4` / `a8cc932` | UnwrittenSynopsisEditTest 2 pass（staged 状态下复验）；npm run build pass |
| p15-04 | `f65a90b` / `0f33e25` | HitlCanonForeshadowReviewTest 3 pass；npm run build pass |
| p15-05 | `b2e9793` | ConceiveTaskTest 3 pass（DELETE active-task 409） |
| p15-06 | `8a7e580` / `3dbe851` | CheckpointSpanTest 2 pass；npm run build pass |
| p15-07 | `2ee2e57` | e2e-fixture-dir-test.sh pass；隔离目录 replay 3 章 0 errors |
| p15-08 | `7603ee1` | ContextOrderDeterminism/RelevanceGatewayAnchor/ContextAssembler/ClimaxDiversityTracker 测试 12 pass；e2e + compare-baseline PASS（上下文组装基线门禁） |
| p15-09 | `9a866a6` | RecordReplayLlmGatewayTest 8 pass（含 miss 转储/完整请求 2 新用例） |

其他：`fec6b53` 修复 `EditPassageReturnsBeforeHarvestTest` @TempDir 清理与后台 harvest 线程的竞态（latch 化；join 不可用因 POST_PROCESS 空闲池线程常驻）；`6699296`/`03794d5` 删除生成态 `.omo/`（含 41 个早期误入库文件）并 gitignore `.omo/`。

## Verification（2026-08-22 实跑）

- `mvn compile -q` exit 0；全量 `mvn test`：**337 pass / 0 fail / 0 error / 1 skip**
- `cd frontend && npm run build` 通过
- `bash scripts/e2e-fixture-dir-test.sh` pass
- `E2E_FIXTURE_DIR=data/e2e-fixtures/replay-proof-20260822 ./scripts/e2e.sh --mode replay`：3 章、10,802 字、50 LLM calls、llmErrors=0、chapterFailures=0
- `python3 scripts/compare-baseline.py` PASS（未使用 --bless）
- `git status` 干净

## E2E

- `E2E_FIXTURE_DIR` 默认 `data/e2e-fixtures/llm`，可覆盖并传入 Spring `llm.replay.dir`；共享 `data/fixtures/llm` 不再参与 E2E。
- runner 传 `--persistence.embedding=stub`、轮询异步 `/run` 任务至终态、record/live 每章 600s 预算。
- `data/e2e-fixtures/replay-proof-20260822` 是当前有效 fixture 目录；p15-08 改动后 replay 指纹稳定。
- replay miss 会转储 `<指纹>.miss.json`（完整请求），可离线比对定位漂移。

## Next Steps

1. backlog 耗尽：按 AGENTS.md 跑 diagnose + compare-baseline 复核，或向用户要新需求；不要自造需求。
2. 新录制请使用新的 `E2E_FIXTURE_DIR`，随后用相同路径 replay。
3. 分支未 push；如需 push：`git push -u origin phase-11-quality-gate-architecture`。
