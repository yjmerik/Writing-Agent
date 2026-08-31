# Session Handoff

## Current Objective

- **Goal**: production-readiness 架构落地（Phase 20-23，29 features）——按 `.omo/drafts/production-readiness-architecture.md` 实现并端到端验证。
- **Current status**: **本会话完成 22/29**（Phase 20 全部、Phase 21 全部、Phase 22 的 4/6、Phase 23 的 1/6）。全量 mvn test 465+ 全绿、`./init.sh` 通过、前端构建通过、e2e replay 与 record 基线逐字一致、compare-baseline PASS。
- **Spec**: `docs/specs/TEMPLATE.md` 路径外：本阶段 spec 即 `.omo/drafts/production-readiness-architecture.md`（status: approved-and-planned）
- **Branch**: `phase-11-quality-gate-architecture`（未 push，无 upstream）
- **Feature 台账**: `feature_list.json`（p20-01…p23-06，含验收与证据）

## Completed（2026-08-30）

| 阶段 | 内容 | 提交 |
|---|---|---|
| Phase 20 生命周期完整性（9/9） | 人工复核优先级 / ChapterPostProcessor 模块 / 阶段账本 / 重启续跑 / ProjectWorkCoordinator owner-token / 四入口 ownership / HUMAN_PATCH 保护 / ACTIVE 唯一性 / 仅标题归一化 | `5799346` `ace78c4` `4096cbc` `17d57b9` `0b27aca` `9ab1270` `f70ea66` |
| Phase 21 终态门禁+产品（8/8） | WUXIA 路线图对齐 / FINAL·NEEDS_REVISION 命令门禁+/final-audit / 章节元数据分页 / 前端终态动作+懒加载+响应式+a11y+SSE 重连 | `f4dd6d7` `cc25cc8` |
| Phase 22 模块深化（4/6） | StoryCanon 端口+双适配器契约（16 用例）/ 写入方读取方全量迁移（业务包零 StoryFactRepository import）/ 仓库 fail-loud（9 个 default no-op 改抽象） | `5e4afbc` |
| Phase 23 证据 harness（1/6） | ScenarioLlmGateway（8 任务类型确定性零网络响应）+ e2e.sh 语料自动解析 | 见最后提交 |

## Verification（2026-08-30 实跑）

- `./init.sh`：**exit 0**（编译 + 全量测试 + 前端构建）
- `mvn test`（需 `source scripts/load-env.sh`）：**465+ pass / 0 fail / 1 skip**
- `./scripts/e2e.sh`（不设 E2E_FIXTURE_DIR，自动解析）→ 3 章 / 10,574 字 / 50 调用 / 0 错误——与 dsv4-20260829d record **逐字一致**
- `python3 scripts/compare-baseline.py`：**PASS**（无需 bless）
- `cd frontend && npm run build`：通过
- `bash scripts/e2e-fixture-dir-test.sh`：通过

## 关键设计决策（后续会话必读）

1. **ChapterPostProcessor**（orchestrator/）：正文落库后 10 阶段单点编排，逐阶段记账；POST_PROCESSING→DONE 裁决留在 Orchestrator；p20-01 保证 NEEDS_MANUAL_REVIEW 不被覆盖。
2. **ProjectWorkCoordinator**（service/）：owner-token 语义是全部写作入口的唯一裁决点；重复启动=409（不存在强制接管路径）；release 一律条件删除。
3. **StoryCanon**（spi/）：真典全部读写语义（5 章保护窗口、ACTIVE 唯一性、审计链）收敛进端口；新增真典消费方一律注入 StoryCanon，禁止 import `persistence.sqlite.StoryFactRepository`（grep 可验证）。
4. **NovelRepository 无 silent default**：归档/预设/清理 9 方法缺实现=编译失败；给测试写新 stub 时需显式声明这些能力（参考 TestNovelRepositoryStub）。
5. **终态语义**：FINAL 全写锁 + NEEDS_REVISION 修订/重审分离（retryFinalAudit）；resolve-review 仍是 NEEDS_MANUAL_REVIEW→DONE 的恢复路径。

## 增补（2026-08-30 续，用户指示暂停真实书写验证后）

- **Phase 22 全部完成（6/6）**：p22-04 ChapterProduction 模块（produce 小结果接口，质量链迁移）+
  p22-05 OrchestratorPolicies 组合（11 个后置 setter 移除，requirePolicy 启动校验）
- **p23-03 根因修复**：soak 首跑 40 章封顶 = layeredOutline=false 走传统一次性大纲（≤40 章设计上限）；
  已改 soak.sh 为 layeredOutline=true，且场景网关支持卷范围感知与总纲章数感知
- **人工复核暂停任务终态改 PAUSED**（原 FAILED 误导）；场景网关 harsh 档就绪
- **验证**：481 测试 0 失败；e2e replay 逐字一致 + compare PASS（重构行为保持）
- **暂停中**：400 章 soak 全量实跑 / p23-02 脚本实跑 / p23-04 Playwright / p23-05 聚合器 /
  p23-06 金丝雀——恢复后按 session-handoff 上文命令执行

## 增补（2026-08-31）：Phase 23 收口——26/29 完成

- **p23-02/03/04/05 全部完成并实跑验证**：reliability-e2e 四场景 PASS；400 章 soak 六不变量 PASS
  （105.9 万字）；ego-browser 套件 8 检查 PASS（按用户指示替代 Playwright，已移除相关依赖）；
  release-gate.sh 六门禁聚合 manifest
- **套件抓到并修复的真实缺陷**：① meta 查询漏 status 列（待人审标记永不显示）② SPA 深链接 500
  （SpaForwardController）③ InteractiveReview 点章全量重取（refresh 依赖修复）
  ④ 场景正文章号污染（harsh 档改按调用序注入）
- **唯一剩余**：p23-06 真实 LLM 质量金丝雀——需用户批准花费，且 bless 需人工确认；
  触发命令：`./scripts/e2e.sh --mode record --chapters 3 && python3 scripts/compare-baseline.py`
- ⚠️ 本机代理 http_proxy=127.0.0.1:9098 会劫持 localhost——所有 curl 必须带 --noproxy '*'

## 最终状态（2026-08-31）：29/29 features 全部完成

- **p23-06 金丝雀已完成**（用户批准）：mystery 3 章 10,191 字 / WUXIA 3 章 11,308 字，均 0 错误 0 失败；
  mystery compare PASS；WUXIA 规则断言 PASS（无修仙规则）。报告 data/release/canary-*.json
- **WUXIA 基线 bless 待人工决定**：正文样本已在会话展示；如认可，将 canary-wuxia-report.json
  作为基线 bless（compare-baseline.py 支持指定报告路径 + --bless）
- 观察项：卷大纲层「题材规则（武侠）」注入密度仅总纲 1 次，可后续评估 p17-03 生产接线
- ⚠️ 本机代理 http_proxy=127.0.0.1:9098 劫持 localhost——curl 必须带 --noproxy '*'
- ⚠️ ego-browser 进程 cwd 非仓库根——其内 fs 写文件必须用绝对路径

## Known Issues / Next Steps（剩余 7 个 not-started feature 入口）

1. **p22-04 提取章节生产模块**：p20-02 已切出 ChapterPostProcessor；下一步把「Context→write→quality→rewrite」同样收敛为小结果接口（ChapterProductionTest 为验证命令）。
2. **p22-05 显式生命周期策略组合**：NovelOrchestrator 仍有 ~10 个后置可选 setter（setRepairArbiter/setPlanGate/…）；改为构造期必需 + 显式 no-op 适配器。注意：大量测试 harness 传 null 依赖此宽松性，改法建议「生产 @Bean 全量显式 + 测试用 no-op 适配器」。
3. **p23-02 reliability-e2e.sh**：PostProcessingRecoveryTest 的「注入崩溃→同库重建→恢复」就是蓝本，脚本化为打包 Spring 进程级场景并产出 report.json。
4. **p23-03 soak.sh（400 章）**：ScenarioLlmGateway 已就绪，但 PLOTTING 响应需支持**章节范围感知**的分层大纲生成（解析 prompt 中卷/章范围，生成对应数量的 `第N章 …` 行）；否则编排器大纲只有 3 章。
5. **p23-04 Playwright 套件**：p21-03/05/06/07/08 的行为断言（终态禁写/懒加载/375px/键盘/SSE 状态）已在前端就位，缺仓库自有 spec 文件与 CI 化。
6. **p23-05 release-gate.sh**：聚合 init/e2e/soak/browser 的清单式脚本，等 p23-02/03/04 落地后收口。
7. **p23-06 真实 LLM 金丝雀**：本次未改任何 `.st` 模板（replay 逐字一致证明），无需触发；但 p21-01 改变了 WUXIA 的 prompt 内容——下次涉及武侠的 record 需重录 + 人工审后 bless。
8. 分支未 push；如需：`git push -u origin phase-11-quality-gate-architecture`。
