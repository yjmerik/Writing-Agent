# Session Handoff

> 状态日期：2026-09-02。历史过程保留在 `progress.md` 与 Git 历史；本文只描述下一会话需要读取的当前事实。

## Current Objective

- **Goal**: 以产品一致性审计的浏览器红灯套件驱动下一轮 P0 → P1 → P2 修复。
- **Current status**: Phase 23 的 production-readiness 29/29 已完成并合并到 `main`；Phase 24 文档维护完成；Phase 25 的产品审计 E2E 测试先行 p25-01 已完成。9 个新增场景当前为 0 PASS / 9 XFAIL / 0 ERROR，尚未实施产品修复。
- **Branch**: `main`，HEAD `de38d9d`；工作区在本次文档修改前为 clean；仓库当前没有 Git remote/upstream。
- **Feature 台账**: `feature_list.json`，`current_phase=25`，179/179 features 全部 completed。
- **Current spec**: `docs/specs/2026-09-02-product-audit-browser-e2e.md`。
- **Product audit**: `docs/audits/2026-09-02-product-design-consistency-audit.md`（P0 3 项 / P1 8 项 / P2 5 项；仅审查，未登记或实施新 feature）。

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

## Verification（2026-09-02）

- 本会话修改前 `./init.sh`：481 tests / 0 failures / 0 errors / 1 skipped；27 个 Prompt 完整；前端生产构建通过。
- p24-01 修改后：`jq empty feature_list.json` PASS；`mvn compile -q` PASS；`mvn test` 481 tests / 0 failures / 0 errors / 1 skipped，BUILD SUCCESS。
- p25-01：`./scripts/product-audit-e2e.sh --expect-known-failures` PASS，0 PASS / 9 XFAIL / 0 ERROR；默认 strict 单场景按预期 exit 1；修改后 `./init.sh` PASS（481 tests / 0 failures / 0 errors / 1 skipped + 前端构建）。

## Non-blocking Observations

1. `baselines/golden.json` 与 `baselines/golden-wuxia.json` 的 `minEvaluationScore` 当前均为 null，release replay 也未带 `--evaluate`；现有 baseline 门禁守住章数/字数/错误，不自动守综合质量分。若要修复，必须按新需求协议另立 feature。
2. NovelOrchestrator（2329 行）和 ContextAssembler（1131 行）仍是复杂度热点；ChapterProduction 仍持有 Orchestrator 回引用。它们不是当前 blocker，但属于后续模块深化候选。
3. 前端主 bundle 约 1.19 MB（gzip 372 KB），构建通过但有分包警告。
4. 当前无 Git remote，`.github/workflows/ci.yml` 不会在远端实际触发；现有证据均为本地 release gate。

## Next Entry

- 不存在遗留的 not-started feature。下一步按审计建议先修 P0-01（初始设定/卷大纲审核改写真正路由）；修复前先复用 `--case initial-setup-modify` 与 `--case volume-outline-modify`，修复后从 `KNOWN_FAILURES` 移除通过项。
- 其余红灯依次为 P0-02 独立章节重写、P0-03 交互切自动、P1-03 审核后正文稳定、P1-07 多卡对象绑定、P1-04/05/06 人工管理、P2-02 移动端；自动零干预 P1-01 需单独设计持续运行生命周期。
- 新需求继续遵循：写 spec → 拆 1-3 文件 feature → 登记 `feature_list.json` → 实现与验证 → 更新三份状态文档。
- 本机代理可能劫持 localhost；脚本内 curl 保持 `--noproxy '*'`。ego-browser 内写文件使用绝对路径。
