# 协作第三轮修复 Implementation Plan

> **For agentic workers:** Implementation is authorized. Execute sequentially with executing-plans; each feature must complete its local-main merge and post-merge verification before the next.

**Goal:** 修复第三轮复验的六类缺陷并如实记录完整流程与文学实验的验收边界。

**Architecture:** 沿用领域 SPI 和 SQLite 原子事务，批准按稿件/意见身份校验。修改要求独立本地暂存；统计脚本按批准方案计算。

**Tech Stack:** Java 17 / Spring Boot 3 / SQLite / React 18 / Python 3 / Playwright。

## Global Constraints

- 已批准 spec：docs/specs/2026-09-17-collaboration-audit3-repair.md。
- 1 feature / 1 branch；先失败测试，后最小实现。不得修改 prompt、golden 或人工评分。
- 每项：init → RED → GREEN/全量 → 提交 → 合并 main → 干净检出全量与专项 → verified 回执。
- 原工作区未跟踪内容保留；只在隔离工作区实现；本地合并已授权，不推送。

## p41-01 批准事务绑定稿件身份与意见修订

**Files:** `src/main/java/com/writing/novel/persistence/sqlite/SqliteNovelRepository.java`; `src/test/java/com/writing/novel/persistence/ApprovalEvidenceIdentityTest.java`

**Dependencies:** p36-04, p37-07, p38-05

**Contract:** 错误项目/章号/版本/hash 的既有裁决不解除 HIGH 阻挡；revision=1 证据不满足 revision=2 意见；匹配证据可批准；失败不写批准记录、不解除屏障。

- [ ] 创建独立分支，运行 ./init.sh。
- [ ] 先构造上述违约输入并断言数据库/HTTP/UI真实结果；运行专项命令记录修复前失败（契约准备和测试迁移记录现有行为）。

Run: `mvn test -Dtest=ApprovalEvidenceIdentityTest,VersionedApprovalRepositoryTest,DispositionApprovalGateTest`

- [ ] 实现以下核心约束，并覆盖批准 spec 的全部边界：

```
e.directive_revision = d.revision
rd.project_id = ? AND rd.chapter_index = ? AND rd.chapter_version = ? AND rd.content_hash = ?
```

- [ ] 重跑专项命令，预期退出0；运行 mvn compile && mvn test。
- [ ] 审阅暂存差异，提交本项及必要状态文档；合并main。
- [ ] 在干净main检出重复全量和专项；记录提交、结果、RED/GREEN，再登记完成。

## p41-02 裁决写入校验真实问题和稿件

**Files:** `src/main/java/com/writing/novel/web/DispositionController.java`; `src/test/java/com/writing/novel/web/ReviewDispositionApiTest.java`

**Dependencies:** p41-01

**Contract:** 不存在的问题或稿件、跨项目/错章和伪造 hash 返回 400/409 且无新增裁决；真实当前稿裁决可保存并经批准事务核验。

- [ ] 创建独立分支，运行 ./init.sh。
- [ ] 先构造上述违约输入并断言数据库/HTTP/UI真实结果；运行专项命令记录修复前失败（契约准备和测试迁移记录现有行为）。

Run: `mvn test -Dtest=ReviewDispositionApiTest,DispositionApprovalGateApiTest`

- [ ] 实现以下核心约束，并覆盖批准 spec 的全部边界：

```
if (!id.equals(issue.getProjectId())) throw new IllegalArgumentException("问题不属于该项目");
```

- [ ] 重跑专项命令，预期退出0；运行 mvn compile && mvn test。
- [ ] 审阅暂存差异，提交本项及必要状态文档；合并main。
- [ ] 在干净main检出重复全量和专项；记录提交、结果、RED/GREEN，再登记完成。

## p41-03 计划原子提交的 SPI 契约

**Files:** `src/main/java/com/writing/novel/spi/NovelRepository.java`; `src/test/java/com/writing/novel/persistence/PlanCommitContractTest.java`

**Dependencies:** p38-02, p38-03

**Contract:** 新增领域级原子确认操作，默认实现明确拒绝且无写入；参数含预览身份、目标、大纲/偏好与逐项决定；不暴露 JDBC、不改变现有调用行为。

- [ ] 创建独立分支，运行 ./init.sh。
- [ ] 先构造上述违约输入并断言数据库/HTTP/UI真实结果；运行专项命令记录修复前失败（契约准备和测试迁移记录现有行为）。

Run: `mvn test -Dtest=PlanCommitContractTest`

- [ ] 实现以下核心约束，并覆盖批准 spec 的全部边界：

```
default PlanChange commitPlanEdition(String projectId, String previewId, String sourcePlanHash, WritingPreferences preferences, Outline outline, List<PlanChange.PromiseDecision> decisions, String requestKey) { throw new UnsupportedOperationException("原子计划确认未实现"); }
```

- [ ] 重跑专项命令，预期退出0；运行 mvn compile && mvn test。
- [ ] 审阅暂存差异，提交本项及必要状态文档；合并main。
- [ ] 在干净main检出重复全量和专项；记录提交、结果、RED/GREEN，再登记完成。

## p41-04 计划确认跨对象事务及故障重试

**Files:** `src/main/java/com/writing/novel/persistence/sqlite/SqliteNovelRepository.java`; `src/main/java/com/writing/novel/service/PlanEditionService.java`; `src/test/java/com/writing/novel/web/PlanConfirmationAtomicityTest.java`

**Dependencies:** p41-03

**Contract:** 对决定、偏好、大纲、确认状态各写入点注入 SQLite 失败后，四类数据全部维持原值；移除故障后同一请求成功一次；已确认请求不重复写入。

- [ ] 创建独立分支，运行 ./init.sh。
- [ ] 先构造上述违约输入并断言数据库/HTTP/UI真实结果；运行专项命令记录修复前失败（契约准备和测试迁移记录现有行为）。

Run: `mvn test -Dtest=PlanConfirmationAtomicityTest,PlanEditionTest,PlanChangeRepositoryTest,Audit2FixTest`

- [ ] 实现以下核心约束，并覆盖批准 spec 的全部边界：

```
return runInTransaction(conn -> {
    // 同连接复验来源、保存偏好和大纲及决定，最后更新 CONFIRMED
    return confirmed;
});
```

- [ ] 重跑专项命令，预期退出0；运行 mvn compile && mvn test。
- [ ] 审阅暂存差异，提交本项及必要状态文档；合并main。
- [ ] 在干净main检出重复全量和专项；记录提交、结果、RED/GREEN，再登记完成。

## p41-05 人物旧调用测试迁移到预览确认

**Files:** `src/test/java/com/writing/novel/web/CanonWriteApiTest.java`; `src/test/java/com/writing/novel/web/WorldCharacterFullEditTest.java`

**Dependencies:** p39-04, p39-09

**Contract:** 既有成功编辑测试先取真实 previewToken 后确认；原人物字段、真典及屏障断言保留且通过，不把拒绝旧路径误当成功覆盖。

- [ ] 创建独立分支，运行 ./init.sh。
- [ ] 先构造上述违约输入并断言数据库/HTTP/UI真实结果；运行专项命令记录修复前失败（契约准备和测试迁移记录现有行为）。

Run: `mvn test -Dtest=CanonWriteApiTest,WorldCharacterFullEditTest`

- [ ] 实现以下核心约束，并覆盖批准 spec 的全部边界：

```
body.put("confirm", false);
// 经真实 PUT 取回 token，再携 confirm=true 和 token 发起原有成功编辑断言
```

- [ ] 重跑专项命令，预期退出0；运行 mvn compile && mvn test。
- [ ] 审阅暂存差异，提交本项及必要状态文档；合并main。
- [ ] 在干净main检出重复全量和专项；记录提交、结果、RED/GREEN，再登记完成。

## p41-06 关闭人物无预览写入旁路

**Files:** `src/main/java/com/writing/novel/web/ContentController.java`; `src/test/java/com/writing/novel/web/CharacterImpactPreviewApiTest.java`

**Dependencies:** p41-05

**Contract:** confirm 缺失/null/非布尔拒绝且库不变；false 只预览；true 无凭证拒绝、过期凭证 409、有效凭证才保存；跨项目不写入。

- [ ] 创建独立分支，运行 ./init.sh。
- [ ] 先构造上述违约输入并断言数据库/HTTP/UI真实结果；运行专项命令记录修复前失败（契约准备和测试迁移记录现有行为）。

Run: `mvn test -Dtest=CharacterImpactPreviewApiTest,CanonWriteApiTest,WorldCharacterFullEditTest`

- [ ] 实现以下核心约束，并覆盖批准 spec 的全部边界：

```
if (!(body.get("confirm") instanceof Boolean)) throw new IllegalArgumentException("confirm 必须是布尔值");
```

- [ ] 重跑专项命令，预期退出0；运行 mvn compile && mvn test。
- [ ] 审阅暂存差异，提交本项及必要状态文档；合并main。
- [ ] 在干净main检出重复全量和专项；记录提交、结果、RED/GREEN，再登记完成。

## p41-07 事实证据验证对应历史稿

**Files:** `src/main/java/com/writing/novel/web/FactEvidenceController.java`; `src/test/java/com/writing/novel/web/HistoricalFactEvidenceApiTest.java`

**Dependencies:** p39-03

**Contract:** 经正式编辑产生 v3 后，真实历史 v2 的 hash/引文返回 200；不存在版本、错 hash、仅新稿含有的引文、跨项目来源拒绝；UNKNOWN 引文语义保留。

- [ ] 创建独立分支，运行 ./init.sh。
- [ ] 先构造上述违约输入并断言数据库/HTTP/UI真实结果；运行专项命令记录修复前失败（契约准备和测试迁移记录现有行为）。

Run: `mvn test -Dtest=HistoricalFactEvidenceApiTest,AuditP14EndpointsTest,Audit2FixTest`

- [ ] 实现以下核心约束，并覆盖批准 spec 的全部边界：

```
repository.findChapterVersion(id, chapterIndex, chapterVersion).orElseThrow();
```

- [ ] 重跑专项命令，预期退出0；运行 mvn compile && mvn test。
- [ ] 审阅暂存差异，提交本项及必要状态文档；合并main。
- [ ] 在干净main检出重复全量和专项；记录提交、结果、RED/GREEN，再登记完成。

## p41-08 实验严格门槛与可复现统计

**Files:** `scripts/evaluate-collaboration-study.py`; `scripts/test_evaluate_collaboration_study.py`; `docs/experiments/collaboration-quality-protocol.md`

**Dependencies:** p40-04

**Contract:** 240 行评分下降1分且耗时增长50%的反例不再 COMPLETE 且 require-complete 非零；-0.2/20%边界、严重错误增加/缺失、重复评委/非有限数字被正确判定；固定种子的置信区间可复现。

- [ ] 创建独立分支，运行 ./init.sh。
- [ ] 先构造上述违约输入并断言数据库/HTTP/UI真实结果；运行专项命令记录修复前失败（契约准备和测试迁移记录现有行为）。

Run: `python3 scripts/test_evaluate_collaboration_study.py`

- [ ] 实现以下核心约束，并覆盖批准 spec 的全部边界：

```
quality_pass = quality_delta >= -0.2
time_pass = collab_median <= solo_median * 0.8
errors_pass = collab_errors <= solo_errors
```

- [ ] 重跑专项命令，预期退出0；运行 mvn compile && mvn test。
- [ ] 审阅暂存差异，提交本项及必要状态文档；合并main。
- [ ] 在干净main检出重复全量和专项；记录提交、结果、RED/GREEN，再登记完成。

## p41-09 Agent 修改要求独立草稿恢复

**Files:** `frontend/src/components/AgentRevisionDialog.tsx`; `frontend/src/hooks/useRevisionInstructionDraft.ts`; `qa/collaboration/revision-instruction-draft.spec.ts`

**Dependencies:** p37-04

**Contract:** 未生成候选的输入刷新重开后逐字恢复；项目/对象/段落隔离；取消、生成失败、应用失败不丢输入；成功应用清理对应草稿；存储异常显示未暂存而不崩溃。

- [ ] 创建独立分支，运行 ./init.sh。
- [ ] 先构造上述违约输入并断言数据库/HTTP/UI真实结果；运行专项命令记录修复前失败（契约准备和测试迁移记录现有行为）。

Run: `QA_BASE_URL=http://127.0.0.1:18089 npx --prefix qa/collaboration playwright test -c qa/collaboration/playwright.config.ts revision-instruction-draft.spec.ts`

- [ ] 实现以下核心约束，并覆盖批准 spec 的全部边界：

```
localStorage.setItem(key, JSON.stringify({ version: 1, instructions: value }));
```

- [ ] 重跑专项命令，预期退出0；运行 mvn compile && mvn test。
- [ ] 审阅暂存差异，提交本项及必要状态文档；合并main。
- [ ] 在干净main检出重复全量和专项；记录提交、结果、RED/GREEN，再登记完成。

## 最终验收

- [ ] 前端构建、全部协作浏览器回归；真实后端API回归覆盖六类问题。
- [ ] 两题材 replay + diagnose + baseline：记录真实完成章数与暂停，不绕过人审。
- [ ] 重评现有人工评分，缺少严重错误计数保持 INCOMPLETE。
- [ ] Harness --check-git；交接记载已合并提交与剩余外部证据。
