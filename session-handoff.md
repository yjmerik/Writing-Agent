# Session Handoff

> 状态日期：2026-09-03。历史过程保留在 `progress.md` 与 Git 历史；本文只描述下一会话需要读取的当前事实。

## Current Objective

- **Goal**: 以产品一致性审计的浏览器红灯套件驱动下一轮 P0 → P1 → P2 修复。
- **Current status**: Phase 26 正在按产品一致性审计逐项修复。p26-01 已完成：checkpoint 的 approve/reject/modify 均先验证项目归属与 PENDING 状态。9 个浏览器场景的既知失败集合尚未更新；`checkpoint-card-targeting` 仍为 p26-03 的预期 XFAIL。
- **Branch**: 当前 Phase 26 修复分支；仓库没有 Git remote/upstream。
- **Feature 台账**: `feature_list.json`，`current_phase=26`；p26-01 completed，p26-02 起仍 not-started。
- **Current spec**: `docs/specs/2026-09-02-product-audit-browser-e2e.md` 与 `.superpowers/sdd/2026-09-03-product-design-consistency-remediation/` 任务简报。
- **Product audit**: `docs/audits/2026-09-02-product-design-consistency-audit.md`（P0 3 项 / P1 8 项 / P2 5 项）。

## Current Architecture

1. **编排**：NovelOrchestrator 管项目状态与章节索引；ChapterProduction 管 Context→Write→Quality→Rewrite；ChapterPostProcessor 管正文落库后的 10 阶段处理。
2. **可靠性**：ProjectWorkCoordinator 以 owner-token 保证单项目单 worker；post_process_ledger 支持崩溃后只续跑未完成阶段。
3. **长程记忆**：ContextAssembler 汇总故事原旨、StoryCanon、全书快照/分层摘要、近期正文、向量检索、实体关系、叙事线索和伏笔。
4. **真典 seam**：业务层只依赖 StoryCanon；HUMAN_PATCH 5 章保护、ACTIVE 唯一性与 supersede 审计链由适配器实现。
5. **状态语义**：项目 FINAL 全写锁；NEEDS_REVISION 只允许修订后 retryFinalAudit；章节 NEEDS_MANUAL_REVIEW 会冻结后续写作，resolve-review 后恢复 DONE。

## Completed Baseline

- `feature_list.json`：Phase 1-23 及 legacy 条目共 177 个，全部 completed；Phase 23 的 29 个 production-readiness feature 全部完成。
- 2026-08-31 release gate：Maven 全量测试、前端构建、replay 基线、reliability E2E、400 章 soak、ego-browser E2E 六门禁全部 PASS，manifest 位于 `data/release/manifest.json`。
- 400 章 soak：400 章连续唯一、约 105 万字、零非终态章节、零重复 ACTIVE 真典键、零任务重叠、零 LLM 网络调用，章节导航 11ms。
- reliability E2E：crash-restart / stale-worker-409 / manual-review / canon-protection 四场景全部 PASS。
- 真实 LLM 金丝雀：mystery 3 章 10,191 字、WUXIA 3 章 11,308 字，均 0 LLM 错误、0 章节失败；WUXIA 规则断言无修仙规则污染。

## Verification（2026-09-03）

- p26-01 RED：`mvn test -Dtest=CheckpointTargetValidationTest` 为 2 failures / 0 errors（跨项目、已处理 checkpoint 实际均接受为 HTTP 200）。
- p26-01 focused GREEN：`mvn test -Dtest=CheckpointTargetValidationTest,ApproveSpecificCheckpointTest` 为 3 tests / 0 failures / 0 errors。
- p26-01 browser：`./scripts/product-audit-e2e.sh --case checkpoint-card-targeting --expect-known-failures` 为 0 PASS / 1 XFAIL / 0 ERROR（卡片绑定保留给 p26-03）。
- 全量：`mvn compile -q && mvn test` 与 `./init.sh` 为 483 tests / 0 failures / 0 errors / 1 skipped；init 还通过 27 个 prompt 与前端生产构建检查。

## Non-blocking Observations

1. `baselines/golden.json` 与 `baselines/golden-wuxia.json` 的 `minEvaluationScore` 当前均为 null，release replay 也未带 `--evaluate`；现有 baseline 门禁守住章数/字数/错误，不自动守综合质量分。若要修复，必须按新需求协议另立 feature。
2. NovelOrchestrator（2329 行）和 ContextAssembler（1131 行）仍是复杂度热点；ChapterProduction 仍持有 Orchestrator 回引用。它们不是当前 blocker，但属于后续模块深化候选。
3. 前端主 bundle 约 1.19 MB（gzip 372 KB），构建通过但有分包警告。
4. 当前无 Git remote，`.github/workflows/ci.yml` 不会在远端实际触发；现有证据均为本地 release gate。

## Next Entry

- 下一步为 p26-02（依赖 p26-01）：初始设定和卷大纲的审核修改必须真正走候选生成、校验与保存路径；先复用 `--case initial-setup-modify` 与 `--case volume-outline-modify`。
- p26-03 负责前端多卡片显式绑定和确认摘要；在该 feature 完成前 `checkpoint-card-targeting` 必须继续作为 XFAIL，不得提前从 `KNOWN_FAILURES` 移除。
- 后续红灯还有独立章节重写、交互切自动、审核后正文稳定、人工管理、移动端；自动零干预需单独设计持续运行生命周期。
- 新需求继续遵循：写 spec → 拆 1-3 文件 feature → 登记 `feature_list.json` → 实现与验证 → 更新三份状态文档。
- 本机代理可能劫持 localhost；脚本内 curl 保持 `--noproxy '*'`。ego-browser 内写文件使用绝对路径。
