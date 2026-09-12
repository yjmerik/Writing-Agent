# Phase39 事实来源与设定影响 Implementation Plan

> **For agentic workers:** When implementation is authorized, select an allowed execution method: use superpowers:subagent-driven-development when delegation is permitted and useful for independent tasks; otherwise use superpowers:executing-plans. Steps use checkbox (`- [ ]`) syntax for tracking. A plan-only request does not authorize implementation.

**Goal:** 完成事实来源与设定影响的可验证修复，不扩展解释已有切片的完成记录。

**Architecture:** 沿用 Spring Boot 编排、SPI 持久化和 React UI。以明确稿件身份和持久审计连接各操作，后端事务保证一致性，浏览器回归验证作者路径。

**Tech Stack:** Java 17 / Spring Boot 3 / SQLite / React 18 / TypeScript / Ant Design / JUnit + MockMvc / Playwright（新增独立 QA 包）。

## 全局执行约束

- 本文件是设计，不代表业务已实现。执行业务修复需用户授权；本轮只登记 not-started。
- 从仓库根运行；完整入口与契约见 [执行入口](2026-09-12-collaboration-repair-entry.md)，规范见 [总规格](../../specs/2026-09-12-collaboration-repair-program.md)。
- 每次只选一个依赖已在 main 完成的 feature，新建 `codex/<feature-id>-<slug>`。编号不代表执行顺序，以 dependencies 为准。
- 下列每项列出 1–3 个计划实现/测试文件；状态文件属于必要交接。若现有测试适配导致实际范围超过3文件，先将超出部分拆成有独立验收的子项并更新依赖，禁止无记录扩大范围。不得用删测试、skip、减弱断言凑绿。
- bug 必须先 RED，再修改实现；用真实行为测试，不能以字符串存在代替。RED 必须是目标行为失败，不是编译失败、依赖缺失、Mockito/端口环境失败。
- 每项必须 `mvn compile && mvn test`、本项 verify_cmd；改前端再 `npm --prefix frontend run build`。合并 main 后在干净检出重跑，验证通过后才 completed 并写 verified 记录。
- 业务层只依赖 SPI；同事务必须同连接，不能在外层事务调用自己另开连接的 public 仓储方法；SQLite 单进程，不用多实例共享一个实际作者库。
- 修改正文/批准/候选请求分别有身份比较；后端缺版本即拒绝。客户端不能以“最新版本”替换作者未读的版本后自动批准。
- 模型与外部调用不在 SQLite 写事务内；提交后派生任务携原稿身份。只读失败不得显示空列表冒充无问题。
- 影响生成上下文/Agent/prompt 的项须执行原项目质量门禁；新语料记录、两次回放、质量比较、人工确认后 bless，不把此设计当作真实模型调用或 bless 的新授权。

<a id="p39-01"></a>
### p39-01 — 事实来源锚点与知情区间模型

- **类型 / 来源**：feature；事实来源/时间线/知识边界
- **依赖**：p36-02
- **文件范围**：
  - 新增 `src/main/java/com/writing/novel/domain/FactEvidence.java`
  - 修改 `src/main/java/com/writing/novel/persistence/sqlite/SqliteSchemaInitializer.java`
  - 新增 `src/test/java/com/writing/novel/persistence/FactEvidenceSchemaTest.java`

**输入/输出契约**：FactEvidence含factId/sourceIdentity/quote/paragraphIndex/anchorHash/validFromChapter/validToChapter/observers[]，observer={characterId,learnedAtChapter,sourceIdentity,quote}；缺证据记UNKNOWN。

- [ ] **1. 复现/契约测试**：旧事实迁移不产生伪造来源；多人在不同章知情分别记录；区间边界合理；历史版正文不存在时保留缺失状态。
- [ ] **2. 运行 RED**：执行下列 verify_cmd，保存失败断言与起始 commit。已有业务缺陷必须在未改实现前看到对应行为失败；新接口可先提交最小编译契约再验证其行为失败，不将编译错误计 RED。

```bash
mvn test -Dtest=FactEvidenceSchemaTest
```

- [ ] **3. 最小实现**：建fact_evidence表；chapter有效区间为叙事时点，不用createdAt推断。旧StoryFact.sourceChapterVersion可辅助定位版本但不能伪造原文quote；不知道谁知情时明确UNKNOWN而非所有出场者知道。
- [ ] **4. GREEN 与回归**：再次执行同一 verify_cmd，逐项核对上面的失败场景变为预期结果；运行全量编译/测试。本项不得顺带改变 prompt 或质量基线。
- [ ] **5. 独立提交和合并**：按 AGENTS.md 明确暂存文件、检查 staged diff、提交信息含 `p39-01`，检查 `main..HEAD` 仅含本项；本地合并后重跑验证，再登记 verified 完成证据。未得到实现授权时停在计划，不能替外部工具预记完成。

**机器验收结果**：verify_cmd 退出0，并满足：旧事实迁移不产生伪造来源；多人在不同章知情分别记录；区间边界合理；历史版正文不存在时保留缺失状态。

**执行检查表输入**（这是计划契约，尚不是实现或测试证据）：

```json
{
  "feature": "p39-01",
  "expected_exit": 0,
  "assertions": "旧事实迁移不产生伪造来源；多人在不同章知情分别记录；区间边界合理；历史版正文不存在时保留缺失状态。",
  "failure_is_not_success": true
}
```

<a id="p39-02"></a>
### p39-02 — 事实证据仓储与历史时点查询

- **类型 / 来源**：feature；知识时点查询
- **依赖**：p39-01, p39-10
- **文件范围**：
  - 修改 `src/main/java/com/writing/novel/spi/StoryCanon.java`
  - 修改 `src/main/java/com/writing/novel/persistence/sqlite/SqliteStoryCanon.java`
  - 新增 `src/test/java/com/writing/novel/persistence/FactEvidenceRepositoryTest.java`

**输入/输出契约**：StoryCanon新增saveEvidence/findEvidence/findFactsAsOf(projectId,chapterIndex,characterId可空)。SqliteStoryCanon经现有连接设施访问fact_evidence，业务层不碰SQL。

- [ ] **1. 复现/契约测试**：第7章被第10章替换的事实在asOf7仍为旧事实，asOf10为新事实；角色第9章才知情则asOf7不显示为已知；跨项目无泄漏。
- [ ] **2. 运行 RED**：执行下列 verify_cmd，保存失败断言与起始 commit。已有业务缺陷必须在未改实现前看到对应行为失败；新接口可先提交最小编译契约再验证其行为失败，不将编译错误计 RED。

```bash
mvn test -Dtest=FactEvidenceRepositoryTest
```

- [ ] **3. 最小实现**：AsOf包含在该章仍有效的被后来替换历史事实，不能只过滤当前ACTIVE；以区间及superseded链验证，证据不足返回UNKNOWN。角色过滤只使用有证明的learnedAt<=N信息，不从事后事实泄露未来知识。
- [ ] **4. GREEN 与回归**：再次执行同一 verify_cmd，逐项核对上面的失败场景变为预期结果；运行全量编译/测试。本项不得顺带改变 prompt 或质量基线。
- [ ] **5. 独立提交和合并**：按 AGENTS.md 明确暂存文件、检查 staged diff、提交信息含 `p39-02`，检查 `main..HEAD` 仅含本项；本地合并后重跑验证，再登记 verified 完成证据。未得到实现授权时停在计划，不能替外部工具预记完成。

**机器验收结果**：verify_cmd 退出0，并满足：第7章被第10章替换的事实在asOf7仍为旧事实，asOf10为新事实；角色第9章才知情则asOf7不显示为已知；跨项目无泄漏。

**执行检查表输入**（这是计划契约，尚不是实现或测试证据）：

```json
{
  "feature": "p39-02",
  "expected_exit": 0,
  "assertions": "第7章被第10章替换的事实在asOf7仍为旧事实，asOf10为新事实；角色第9章才知情则asOf7不显示为已知；跨项目无泄漏。",
  "failure_is_not_success": true
}
```

<a id="p39-03"></a>
### p39-03 — 收割实际来源与人审补证据接口

- **类型 / 来源**：feature；来源可验证
- **依赖**：p39-02
- **文件范围**：
  - 修改 `src/main/java/com/writing/novel/agent/FactHarvesterAgent.java`
  - 新增 `src/main/java/com/writing/novel/web/FactEvidenceController.java`
  - 新增 `src/test/java/com/writing/novel/web/FactEvidenceCaptureTest.java`

**输入/输出契约**：GET /facts/evidence?asOfChapter=N&characterId=；POST /facts/{factId}/evidence携sourceIdentity、quote、observers。

- [ ] **1. 复现/契约测试**：收割v3事实来源不是默认v0；非法quote/项目/稿号拒绝；已替换稿保留原来源；无锚点如实展示UNKNOWN。
- [ ] **2. 运行 RED**：执行下列 verify_cmd，保存失败断言与起始 commit。已有业务缺陷必须在未改实现前看到对应行为失败；新接口可先提交最小编译契约再验证其行为失败，不将编译错误计 RED。

```bash
mvn test -Dtest=FactEvidenceCaptureTest
```

- [ ] **3. 最小实现**：收割保存调用时真实章节身份；现有输出无精确quote时不得猜测anchor，保存sourceIdentity+UNKNOWN。人审补证据必须校验引用在对应历史稿中，知情者来自项目人物ID且learnedAt不早于来源。不因这次视图功能修改既有事实真假裁决算法。
- [ ] **4. GREEN 与回归**：再次执行同一 verify_cmd，逐项核对上面的失败场景变为预期结果；运行全量编译/测试。本项影响生成或检查流程，还须执行入口中的质量门禁 QG。
- [ ] **5. 独立提交和合并**：按 AGENTS.md 明确暂存文件、检查 staged diff、提交信息含 `p39-03`，检查 `main..HEAD` 仅含本项；本地合并后重跑验证，再登记 verified 完成证据。未得到实现授权时停在计划，不能替外部工具预记完成。

**机器验收结果**：verify_cmd 退出0，并满足：收割v3事实来源不是默认v0；非法quote/项目/稿号拒绝；已替换稿保留原来源；无锚点如实展示UNKNOWN。

**执行检查表输入**（这是计划契约，尚不是实现或测试证据）：

```json
{
  "feature": "p39-03",
  "expected_exit": 0,
  "assertions": "收割v3事实来源不是默认v0；非法quote/项目/稿号拒绝；已替换稿保留原来源；无锚点如实展示UNKNOWN。",
  "failure_is_not_success": true
}
```

<a id="p39-04"></a>
### p39-04 — 人物与设定修改影响预览及安全提交

- **类型 / 来源**：feature；人物修改影响预览
- **依赖**：p37-03, p39-02, p39-07, p39-08
- **文件范围**：
  - 修改 `src/main/java/com/writing/novel/service/CanonPatchService.java`
  - 修改 `src/main/java/com/writing/novel/service/AgentRevisionService.java`
  - 新增 `src/test/java/com/writing/novel/service/CharacterImpactPreviewTest.java`

**输入/输出契约**：修改前构建影响结果{sourceHash,affectedChapterIdentities,affectedFacts,affectedPromises,confidence,limitations}；手改与Agent候选同一影响计算，确认提交绑定sourceHash。

- [ ] **1. 复现/契约测试**：人物动机修改可列已引用章并区分已写/未写；预览不改真典；确认时来源变化409；应用后原章正文hash不变；重复候选不会重复审计。
- [ ] **2. 运行 RED**：执行下列 verify_cmd，保存失败断言与起始 commit。已有业务缺陷必须在未改实现前看到对应行为失败；新接口可先提交最小编译契约再验证其行为失败，不将编译错误计 RED。

```bash
mvn test -Dtest=CharacterImpactPreviewTest
```

- [ ] **3. 最小实现**：用实体/事实显式引用查影响，未结构化提及时可补文本候选但标可能相关，不宣称穷尽。手工提交先生成detached proposal再经p39-07提交；Agent候选WORLD/CHARACTER走StoryCanon.commitCanonProposal，VOLUME走commitVolumeProposal，全部有持久幂等结果。历史章正文不自动改写，仅列重检建议；不能以synchronized冒充跨表原子性。
- [ ] **4. GREEN 与回归**：再次执行同一 verify_cmd，逐项核对上面的失败场景变为预期结果；运行全量编译/测试。本项不得顺带改变 prompt 或质量基线。
- [ ] **5. 独立提交和合并**：按 AGENTS.md 明确暂存文件、检查 staged diff、提交信息含 `p39-04`，检查 `main..HEAD` 仅含本项；本地合并后重跑验证，再登记 verified 完成证据。未得到实现授权时停在计划，不能替外部工具预记完成。

**机器验收结果**：verify_cmd 退出0，并满足：人物动机修改可列已引用章并区分已写/未写；预览不改真典；确认时来源变化409；应用后原章正文hash不变；重复候选不会重复审计。

**执行检查表输入**（这是计划契约，尚不是实现或测试证据）：

```json
{
  "feature": "p39-04",
  "expected_exit": 0,
  "assertions": "人物动机修改可列已引用章并区分已写/未写；预览不改真典；确认时来源变化409；应用后原章正文hash不变；重复候选不会重复审计。",
  "failure_is_not_success": true
}
```

<a id="p39-05"></a>
### p39-05 — 来源跳转、时间线与人物知识界面

- **类型 / 来源**：feature；事实界面入口
- **依赖**：p39-03, p36-08
- **文件范围**：
  - 修改 `frontend/src/api/client.ts`
  - 修改 `frontend/src/components/WorldbuildingView.tsx`
  - 新增 `qa/collaboration/fact-evidence.spec.ts`

**输入/输出契约**：api.getFactEvidence返回来源身份与时点信息；世界人物页提供截至第N章、人物知情筛选、事实原文跳转到章节历史稿。

- [ ] **1. 复现/契约测试**：asOf7/10切换事实替换正确；角色9章知情不提前泄露；来源指向v2时不得显示v6冒用；键盘完成筛选/返回。
- [ ] **2. 运行 RED**：执行下列 verify_cmd，保存失败断言与起始 commit。已有业务缺陷必须在未改实现前看到对应行为失败；新接口可先提交最小编译契约再验证其行为失败，不将编译错误计 RED。

```bash
npm --prefix qa/collaboration test -- fact-evidence.spec.ts
```

- [ ] **3. 最小实现**：事实类型中文映射；默认UNKNOWN明确显示“尚无来源证据/知情者未记录”；历史稿来源不能跳成当前稿而不告知；引用quote在历史正文高亮，缺稿时显示不能定位。
- [ ] **4. GREEN 与回归**：再次执行同一 verify_cmd，逐项核对上面的失败场景变为预期结果；运行全量编译/测试与前端构建。本项不得顺带改变 prompt 或质量基线。
- [ ] **5. 独立提交和合并**：按 AGENTS.md 明确暂存文件、检查 staged diff、提交信息含 `p39-05`，检查 `main..HEAD` 仅含本项；本地合并后重跑验证，再登记 verified 完成证据。未得到实现授权时停在计划，不能替外部工具预记完成。

**机器验收结果**：verify_cmd 退出0，并满足：asOf7/10切换事实替换正确；角色9章知情不提前泄露；来源指向v2时不得显示v6冒用；键盘完成筛选/返回。

**执行检查表输入**（这是计划契约，尚不是实现或测试证据）：

```json
{
  "feature": "p39-05",
  "expected_exit": 0,
  "assertions": "asOf7/10切换事实替换正确；角色9章知情不提前泄露；来源指向v2时不得显示v6冒用；键盘完成筛选/返回。",
  "failure_is_not_success": true
}
```

<a id="p39-06"></a>
### p39-06 — 人物修改影响确认界面

- **类型 / 来源**：feature；人物修改完整界面
- **依赖**：p39-04, p37-05, p39-09
- **文件范围**：
  - 修改 `frontend/src/components/AgentRevisionDialog.tsx`
  - 修改 `frontend/src/components/WorldbuildingView.tsx`
  - 新增 `qa/collaboration/character-impact.spec.ts`

**输入/输出契约**：使用p39-09的previewCanonPatch/confirmCanonPatch；世界人物手改与Agent候选显示相同影响清单，确认必须携sourceHash。

- [ ] **1. 复现/契约测试**：修改动机→影响预览→取消无DB变化→确认只更新设定不改正文；两个窗口来源变动409保留表单；无可追踪引用时明确覆盖不足。
- [ ] **2. 运行 RED**：执行下列 verify_cmd，保存失败断言与起始 commit。已有业务缺陷必须在未改实现前看到对应行为失败；新接口可先提交最小编译契约再验证其行为失败，不将编译错误计 RED。

```bash
npm --prefix qa/collaboration test -- character-impact.spec.ts
```

- [ ] **3. 最小实现**：手改保存先出现影响清单与来源范围，保留理由；确认后显示需重新检查的章。禁止直接保存文案宣称“已更新所有引用”；Agent候选弹窗复用相同影响显示，不让设置修改绕过验证。
- [ ] **4. GREEN 与回归**：再次执行同一 verify_cmd，逐项核对上面的失败场景变为预期结果；运行全量编译/测试与前端构建。本项不得顺带改变 prompt 或质量基线。
- [ ] **5. 独立提交和合并**：按 AGENTS.md 明确暂存文件、检查 staged diff、提交信息含 `p39-06`，检查 `main..HEAD` 仅含本项；本地合并后重跑验证，再登记 verified 完成证据。未得到实现授权时停在计划，不能替外部工具预记完成。

**机器验收结果**：verify_cmd 退出0，并满足：修改动机→影响预览→取消无DB变化→确认只更新设定不改正文；两个窗口来源变动409保留表单；无可追踪引用时明确覆盖不足。

**执行检查表输入**（这是计划契约，尚不是实现或测试证据）：

```json
{
  "feature": "p39-06",
  "expected_exit": 0,
  "assertions": "修改动机→影响预览→取消无DB变化→确认只更新设定不改正文；两个窗口来源变动409保留表单；无可追踪引用时明确覆盖不足。",
  "failure_is_not_success": true
}
```

<a id="p39-07"></a>
### p39-07 — 世界人物候选与真典原子幂等提交

- **类型 / 来源**：feature；非章节候选重启与一致性
- **依赖**：p37-02, p39-02
- **文件范围**：
  - 修改 `src/main/java/com/writing/novel/spi/StoryCanon.java`
  - 修改 `src/main/java/com/writing/novel/persistence/sqlite/SqliteStoryCanon.java`
  - 新增 `src/test/java/com/writing/novel/persistence/CanonProposalCommitTest.java`

**输入/输出契约**：StoryCanon新增commitCanonProposal(projectId,proposalId,sourceHash)；同DB事务写人物/世界对象、HUMAN_PATCH真典/审计与proposal结果，返回持久result。

- [ ] **1. 复现/契约测试**：写角色后写真典前故障全回滚；双apply只有一份修改/审计；重启后APPLIED返回原结果；旧事实保护期及原有StoryCanon契约仍通过。
- [ ] **2. 运行 RED**：执行下列 verify_cmd，保存失败断言与起始 commit。已有业务缺陷必须在未改实现前看到对应行为失败；新接口可先提交最小编译契约再验证其行为失败，不将编译错误计 RED。

```bash
mvn test -Dtest=CanonProposalCommitTest
```

- [ ] **3. 最小实现**：实现层复用同连接的内部SQL辅助函数，禁止从事务调用另开连接的方法；保持人工补丁保护、ACTIVE唯一性、历史事实链；对象及相关真典hash均加入sourceHash；相同request重试返回原结果。
- [ ] **4. GREEN 与回归**：再次执行同一 verify_cmd，逐项核对上面的失败场景变为预期结果；运行全量编译/测试。本项不得顺带改变 prompt 或质量基线。
- [ ] **5. 独立提交和合并**：按 AGENTS.md 明确暂存文件、检查 staged diff、提交信息含 `p39-07`，检查 `main..HEAD` 仅含本项；本地合并后重跑验证，再登记 verified 完成证据。未得到实现授权时停在计划，不能替外部工具预记完成。

**机器验收结果**：verify_cmd 退出0，并满足：写角色后写真典前故障全回滚；双apply只有一份修改/审计；重启后APPLIED返回原结果；旧事实保护期及原有StoryCanon契约仍通过。

**执行检查表输入**（这是计划契约，尚不是实现或测试证据）：

```json
{
  "feature": "p39-07",
  "expected_exit": 0,
  "assertions": "写角色后写真典前故障全回滚；双apply只有一份修改/审计；重启后APPLIED返回原结果；旧事实保护期及原有StoryCanon契约仍通过。",
  "failure_is_not_success": true
}
```

<a id="p39-08"></a>
### p39-08 — 卷候选与结构来源比较提交

- **类型 / 来源**：feature；卷候选与计划一致
- **依赖**：p37-02
- **文件范围**：
  - 修改 `src/main/java/com/writing/novel/spi/NovelRepository.java`
  - 修改 `src/main/java/com/writing/novel/persistence/sqlite/SqliteNovelRepository.java`
  - 新增 `src/test/java/com/writing/novel/persistence/VolumeProposalCommitTest.java`

**输入/输出契约**：commitVolumeProposal(projectId,proposalId,sourceHash)同事务更新未写卷允许字段与候选消费；sourceHash覆盖卷身份、章纲身份及已写边界。

- [ ] **1. 复现/契约测试**：候选生成后裁剪目标/新增已写章再apply409；并发应用及重启不会重复修改；不得改chapterIndex/id结构。
- [ ] **2. 运行 RED**：执行下列 verify_cmd，保存失败断言与起始 commit。已有业务缺陷必须在未改实现前看到对应行为失败；新接口可先提交最小编译契约再验证其行为失败，不将编译错误计 RED。

```bash
mvn test -Dtest=VolumeProposalCommitTest
```

- [ ] **3. 最小实现**：保留不允许修改章节结构的既有约束；期间开始写卷或计划被裁剪则冲突；候选不能复活已删除章纲。返回不可变result使响应丢失可恢复。
- [ ] **4. GREEN 与回归**：再次执行同一 verify_cmd，逐项核对上面的失败场景变为预期结果；运行全量编译/测试。本项不得顺带改变 prompt 或质量基线。
- [ ] **5. 独立提交和合并**：按 AGENTS.md 明确暂存文件、检查 staged diff、提交信息含 `p39-08`，检查 `main..HEAD` 仅含本项；本地合并后重跑验证，再登记 verified 完成证据。未得到实现授权时停在计划，不能替外部工具预记完成。

**机器验收结果**：verify_cmd 退出0，并满足：候选生成后裁剪目标/新增已写章再apply409；并发应用及重启不会重复修改；不得改chapterIndex/id结构。

**执行检查表输入**（这是计划契约，尚不是实现或测试证据）：

```json
{
  "feature": "p39-08",
  "expected_exit": 0,
  "assertions": "候选生成后裁剪目标/新增已写章再apply409；并发应用及重启不会重复修改；不得改chapterIndex/id结构。",
  "failure_is_not_success": true
}
```


<a id="p39-09"></a>
### p39-09 — 设定影响预览接口与前端请求契约

- **类型/来源**：feature；人物影响API与UI衔接
- **依赖**：p39-04
- **文件范围**：
- `src/main/java/com/writing/novel/web/ContentController.java`
- `frontend/src/api/client.ts`
- `src/test/java/com/writing/novel/web/CanonImpactApiTest.java`

**输入/输出契约**：人物/世界更新API新增confirm=false影响预览与confirm=true来源校验；client增加previewCanonPatch/confirmCanonPatch并提供typed response。

- [ ] **1. 写契约测试**：mvn test -Dtest=CanonImpactApiTest 退出0；预览不写入；确认缺sourceHash拒绝；来源改变409；跨项目拒绝；确认返回持久proposal结果及影响清单。
- [ ] **2. 运行RED**：先看到目标行为不满足，不能把缺类或环境错误视为RED。

```bash
mvn test -Dtest=CanonImpactApiTest
```

- [ ] **3. 实现**：ContentController直接使用p39-04的CanonPatchService预览/提交契约；保留原读取API。移除世界/人物无预览直接写入旁路，仅允许携hash确认；Agent候选应用已有同等hash约束。client把确认值显式传输，不能替调用者读取最新hash后静默确认。
- [ ] **4. GREEN**：重跑相同专项及全量`mvn compile && mvn test`，修改client时补前端构建。
- [ ] **5. 合并与证据**：本项独立提交/合入main，合并后干净检出重新验证，再登记verified与completed。

**机器验收**：mvn test -Dtest=CanonImpactApiTest 退出0；预览不写入；确认缺sourceHash拒绝；来源改变409；跨项目拒绝；确认返回持久proposal结果及影响清单。

<a id="p39-10"></a>
### p39-10 — 真典持久层暴露同连接事务辅助方法

- **类型/来源**：feature；事实证据与原子真典提交前置
- **依赖**：p39-01
- **文件范围**：
- `src/main/java/com/writing/novel/persistence/sqlite/StoryFactRepository.java`
- `src/main/java/com/writing/novel/persistence/sqlite/SqliteStoryCanon.java`
- `src/test/java/com/writing/novel/persistence/StoryCanonConnectionTest.java`

**输入/输出契约**：StoryFactRepository新增包内read(Function<Connection,T>)/runInTransaction(Function<Connection,T>)/save(Connection,StoryFact)/supersede(Connection,old,new)，现有public构造与方法保留兼容。

- [ ] **1. 写契约测试**：mvn test -Dtest=StoryCanonConnectionTest 退出0；同一事务组合save/supersede异常时全回滚；连接数断言为1；原单参SqliteStoryCanon构造及StoryCanon契约通过。
- [ ] **2. 运行RED**：先看到目标行为不满足，不能把缺类或环境错误视为RED。

```bash
mvn test -Dtest=StoryCanonConnectionTest
```

- [ ] **3. 实现**：只在persistence.sqlite包中使用带Connection的助手，不向业务层暴露SQL或强转；public独立操作委派到新助手，保持原行为。SqliteStoryCanon通过factRepo事务回调访问新表/组合写入，因此后续证据查询和候选应用无需改Spring配置或暴露ConnectionProvider。
- [ ] **4. GREEN**：重跑相同专项及全量`mvn compile && mvn test`，修改client时补前端构建。
- [ ] **5. 合并与证据**：本项独立提交/合入main，合并后干净检出重新验证，再登记verified与completed。

**机器验收**：mvn test -Dtest=StoryCanonConnectionTest 退出0；同一事务组合save/supersede异常时全回滚；连接数断言为1；原单参SqliteStoryCanon构造及StoryCanon契约通过。
