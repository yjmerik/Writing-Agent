# Phase38 计划收尾与成书批准 Implementation Plan

> **For agentic workers:** When implementation is authorized, select an allowed execution method: use superpowers:subagent-driven-development when delegation is permitted and useful for independent tasks; otherwise use superpowers:executing-plans. Steps use checkbox (`- [ ]`) syntax for tracking. A plan-only request does not authorize implementation.

**Goal:** 完成计划收尾与成书批准的可验证修复，不扩展解释已有切片的完成记录。

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

<a id="p38-01"></a>
### p38-01 — 计划变更凭证与承诺处置模型

- **类型 / 来源**：feature；24→20承诺处理
- **依赖**：p36-02
- **文件范围**：
  - 新增 `src/main/java/com/writing/novel/domain/PlanChange.java`
  - 修改 `src/main/java/com/writing/novel/persistence/sqlite/SqliteSchemaInitializer.java`
  - 新增 `src/test/java/com/writing/novel/persistence/PlanChangeSchemaTest.java`

**输入/输出契约**：PlanChange保存previewId/projectId/sourcePlanHash/target/writtenBoundary/affectedPromises[]/prunedChapters[]/createdAt/status；PromiseDecision={id,action:MIGRATE|OPEN_END,targetChapter?,reason}。

- [ ] **1. 复现/契约测试**：迁移幂等；预览内容持久且不可被目标输入改写；承诺用ID不靠描述匹配；跨项目不能引用。
- [ ] **2. 运行 RED**：执行下列 verify_cmd，保存失败断言与起始 commit。已有业务缺陷必须在未改实现前看到对应行为失败；新接口可先提交最小编译契约再验证其行为失败，不将编译错误计 RED。

```bash
mvn test -Dtest=PlanChangeSchemaTest
```

- [ ] **3. 最小实现**：建plan_changes及promise_decisions审计表；planHash覆盖目标/大纲/已写边界/承诺版本和现有处置。只允许未写章裁剪，必须回收承诺默认不能OPEN_END，必须显式豁免并记录理由，见p38-10。
- [ ] **4. GREEN 与回归**：再次执行同一 verify_cmd，逐项核对上面的失败场景变为预期结果；运行全量编译/测试。本项不得顺带改变 prompt 或质量基线。
- [ ] **5. 独立提交和合并**：按 AGENTS.md 明确暂存文件、检查 staged diff、提交信息含 `p38-01`，检查 `main..HEAD` 仅含本项；本地合并后重跑验证，再登记 verified 完成证据。未得到实现授权时停在计划，不能替外部工具预记完成。

**机器验收结果**：verify_cmd 退出0，并满足：迁移幂等；预览内容持久且不可被目标输入改写；承诺用ID不靠描述匹配；跨项目不能引用。

**执行检查表输入**（这是计划契约，尚不是实现或测试证据）：

```json
{
  "feature": "p38-01",
  "expected_exit": 0,
  "assertions": "迁移幂等；预览内容持久且不可被目标输入改写；承诺用ID不靠描述匹配；跨项目不能引用。",
  "failure_is_not_success": true
}
```

<a id="p38-02"></a>
### p38-02 — 篇幅与承诺处置的原子提交

- **类型 / 来源**：bug；篇幅确认与持久一致
- **依赖**：p38-01
- **文件范围**：
  - 修改 `src/main/java/com/writing/novel/spi/NovelRepository.java`
  - 修改 `src/main/java/com/writing/novel/persistence/sqlite/SqliteNovelRepository.java`
  - 新增 `src/test/java/com/writing/novel/persistence/PlanChangeRepositoryTest.java`

**输入/输出契约**：新增 savePlanChange/findPlanChange/commitPlanChange(previewId,sourcePlanHash,decisions,key)；结果返回target、planHash、decisionIds。

- [ ] **1. 复现/契约测试**：预览22后改计划/新增章再确认旧token409；故障全部回滚；同key重试不重复审计；MIGRATE和OPEN_END落库口径不同。
- [ ] **2. 运行 RED**：执行下列 verify_cmd，保存失败断言与起始 commit。已有业务缺陷必须在未改实现前看到对应行为失败；新接口可先提交最小编译契约再验证其行为失败，不将编译错误计 RED。

```bash
mvn test -Dtest=PlanChangeRepositoryTest
```

- [ ] **3. 最小实现**：在同事务重读计划/已写边界/承诺，拒绝过期预览、缺处置、迁移到被裁章节；保存目标、大纲及处置审计一起提交。OPEN_END是作者接受未解，不是标记原文已回收；可查询acceptedOpenEnding，最终门禁消费此区别。
- [ ] **4. GREEN 与回归**：再次执行同一 verify_cmd，逐项核对上面的失败场景变为预期结果；运行全量编译/测试。本项不得顺带改变 prompt 或质量基线。
- [ ] **5. 独立提交和合并**：按 AGENTS.md 明确暂存文件、检查 staged diff、提交信息含 `p38-02`，检查 `main..HEAD` 仅含本项；本地合并后重跑验证，再登记 verified 完成证据。未得到实现授权时停在计划，不能替外部工具预记完成。

**机器验收结果**：verify_cmd 退出0，并满足：预览22后改计划/新增章再确认旧token409；故障全部回滚；同key重试不重复审计；MIGRATE和OPEN_END落库口径不同。

**执行检查表输入**（这是计划契约，尚不是实现或测试证据）：

```json
{
  "feature": "p38-02",
  "expected_exit": 0,
  "assertions": "预览22后改计划/新增章再确认旧token409；故障全部回滚；同key重试不重复审计；MIGRATE和OPEN_END落库口径不同。",
  "failure_is_not_success": true
}
```

<a id="p38-03"></a>
### p38-03 — 计划变更服务使用持久预览凭证

- **类型 / 来源**：bug；计划预览服务端校验
- **依赖**：p38-02
- **文件范围**：
  - 修改 `src/main/java/com/writing/novel/service/PlanEditionService.java`
  - 修改 `src/main/java/com/writing/novel/web/ContentController.java`
  - 新增 `src/test/java/com/writing/novel/web/PlanPreviewCommitApiTest.java`

**输入/输出契约**：POST /plan/target-chapters confirm=false返回previewId/sourcePlanHash/目标/影响；confirm=true必须previewId/sourcePlanHash/decisions/idempotencyKey，不接受只有target的确认。

- [ ] **1. 复现/契约测试**：HTTP复现预览22→提交20被400/409；正文边界改变409；没有处理承诺不能成功；纯预览不更改DB。
- [ ] **2. 运行 RED**：执行下列 verify_cmd，保存失败断言与起始 commit。已有业务缺陷必须在未改实现前看到对应行为失败；新接口可先提交最小编译契约再验证其行为失败，不将编译错误计 RED。

```bash
mvn test -Dtest=PlanPreviewCommitApiTest
```

- [ ] **3. 最小实现**：项目工作权与事务配合，确认值取持久preview；不直接相信浏览器target。所有影响承诺需决定，实际结束处达到目标时提供finalAuditEligible/blockedReasons，不能只改数字宣称完成。
- [ ] **4. GREEN 与回归**：再次执行同一 verify_cmd，逐项核对上面的失败场景变为预期结果；运行全量编译/测试。本项不得顺带改变 prompt 或质量基线。
- [ ] **5. 独立提交和合并**：按 AGENTS.md 明确暂存文件、检查 staged diff、提交信息含 `p38-03`，检查 `main..HEAD` 仅含本项；本地合并后重跑验证，再登记 verified 完成证据。未得到实现授权时停在计划，不能替外部工具预记完成。

**机器验收结果**：verify_cmd 退出0，并满足：HTTP复现预览22→提交20被400/409；正文边界改变409；没有处理承诺不能成功；纯预览不更改DB。

**执行检查表输入**（这是计划契约，尚不是实现或测试证据）：

```json
{
  "feature": "p38-03",
  "expected_exit": 0,
  "assertions": "HTTP复现预览22→提交20被400/409；正文边界改变409；没有处理承诺不能成功；纯预览不更改DB。",
  "failure_is_not_success": true
}
```

<a id="p38-04"></a>
### p38-04 — 篇幅收尾的承诺逐项处置界面

- **类型 / 来源**：feature；24→20完整工作流
- **依赖**：p38-03, p36-10
- **文件范围**：
  - 修改 `frontend/src/api/client.ts`
  - 修改 `frontend/src/components/OutlineView.tsx`
  - 新增 `qa/collaboration/plan-resolution.spec.ts`

**输入/输出契约**：界面接收previewId与完整影响清单，逐个选择迁移或开放结局、输入理由；存在不可豁免mustResolve时禁用不合法选项。

- [ ] **1. 复现/契约测试**：合成24章计划写20章，22-24有未回收承诺：迁移一条+接受开放一条→确认20→刷新仍20；没有新增21章；处置审计可重读。
- [ ] **2. 运行 RED**：执行下列 verify_cmd，保存失败断言与起始 commit。已有业务缺陷必须在未改实现前看到对应行为失败；新接口可先提交最小编译契约再验证其行为失败，不将编译错误计 RED。

```bash
npm --prefix qa/collaboration test -- plan-resolution.spec.ts
```

- [ ] **3. 最小实现**：先可视化被删章、现有章保留及承诺承载；必须全部决定才确认；成功重读大纲/协作状态；已写目标到达后明确“进入成书审查”而非再写下一章。异常保留用户决定供重新核对，不能自动套在新预览。
- [ ] **4. GREEN 与回归**：再次执行同一 verify_cmd，逐项核对上面的失败场景变为预期结果；运行全量编译/测试与前端构建。本项不得顺带改变 prompt 或质量基线。
- [ ] **5. 独立提交和合并**：按 AGENTS.md 明确暂存文件、检查 staged diff、提交信息含 `p38-04`，检查 `main..HEAD` 仅含本项；本地合并后重跑验证，再登记 verified 完成证据。未得到实现授权时停在计划，不能替外部工具预记完成。

**机器验收结果**：verify_cmd 退出0，并满足：合成24章计划写20章，22-24有未回收承诺：迁移一条+接受开放一条→确认20→刷新仍20；没有新增21章；处置审计可重读。

**执行检查表输入**（这是计划契约，尚不是实现或测试证据）：

```json
{
  "feature": "p38-04",
  "expected_exit": 0,
  "assertions": "合成24章计划写20章，22-24有未回收承诺：迁移一条+接受开放一条→确认20→刷新仍20；没有新增21章；处置审计可重读。",
  "failure_is_not_success": true
}
```

<a id="p38-05"></a>
### p38-05 — 不可变成书版本模型与存储结构

- **类型 / 来源**：feature；成书固定版本
- **依赖**：p36-02
- **文件范围**：
  - 新增 `src/main/java/com/writing/novel/domain/BookEdition.java`
  - 修改 `src/main/java/com/writing/novel/persistence/sqlite/SqliteSchemaInitializer.java`
  - 新增 `src/test/java/com/writing/novel/persistence/BookEditionSchemaTest.java`

**输入/输出契约**：BookEdition含editionId/projectId/manifestHash/state=DRAFT|APPROVED/createdAt/approvedAt；manifest逐章保存identity、title、正文副本、报告身份、作者批准ID、计划版本、承诺决定。

- [ ] **1. 复现/契约测试**：多章快照顺序确定；正文换行保持；同标题不同版本hash不同；两次schema初始化不损坏既有edition。
- [ ] **2. 运行 RED**：执行下列 verify_cmd，保存失败断言与起始 commit。已有业务缺陷必须在未改实现前看到对应行为失败；新接口可先提交最小编译契约再验证其行为失败，不将编译错误计 RED。

```bash
mvn test -Dtest=BookEditionSchemaTest
```

- [ ] **3. 最小实现**：建book_editions/book_edition_chapters；正文快照物化，不依赖会被后来覆盖的chapters表。manifestHash使用章节号排序的规范JSON全SHA256。旧Project.bookSnapshot是记忆摘要，不是BookEdition，不迁移为成书批准。
- [ ] **4. GREEN 与回归**：再次执行同一 verify_cmd，逐项核对上面的失败场景变为预期结果；运行全量编译/测试。本项不得顺带改变 prompt 或质量基线。
- [ ] **5. 独立提交和合并**：按 AGENTS.md 明确暂存文件、检查 staged diff、提交信息含 `p38-05`，检查 `main..HEAD` 仅含本项；本地合并后重跑验证，再登记 verified 完成证据。未得到实现授权时停在计划，不能替外部工具预记完成。

**机器验收结果**：verify_cmd 退出0，并满足：多章快照顺序确定；正文换行保持；同标题不同版本hash不同；两次schema初始化不损坏既有edition。

**执行检查表输入**（这是计划契约，尚不是实现或测试证据）：

```json
{
  "feature": "p38-05",
  "expected_exit": 0,
  "assertions": "多章快照顺序确定；正文换行保持；同标题不同版本hash不同；两次schema初始化不损坏既有edition。",
  "failure_is_not_success": true
}
```

<a id="p38-06"></a>
### p38-06 — 成书快照创建与批准原子性

- **类型 / 来源**：feature；成书批准版本一致
- **依赖**：p38-05, p36-03, p38-02, p38-10
- **文件范围**：
  - 修改 `src/main/java/com/writing/novel/spi/NovelRepository.java`
  - 修改 `src/main/java/com/writing/novel/persistence/sqlite/SqliteNovelRepository.java`
  - 新增 `src/test/java/com/writing/novel/persistence/BookEditionRepositoryTest.java`

**输入/输出契约**：新增 createBookEdition(projectId,key)、findBookEdition、listBookEditions、approveBookEdition(editionId,manifestHash,key)。

- [ ] **1. 复现/契约测试**：并发编辑创建不得混合两代正文；批准前改一章拒绝；崩溃回滚不产生半本书；批准后再改正文，历史edition字节不变。
- [ ] **2. 运行 RED**：执行下列 verify_cmd，保存失败断言与起始 commit。已有业务缺陷必须在未改实现前看到对应行为失败；新接口可先提交最小编译契约再验证其行为失败，不将编译错误计 RED。

```bash
mvn test -Dtest=BookEditionRepositoryTest
```

- [ ] **3. 最小实现**：创建在同一读取快照事务中收集正文/报告/批准/计划/处置；批准时重新比较当前manifest并校验覆盖资格，任何章升版/未批/旧报告/未决硬约束均阻塞。APPROVED edition不可改；新正文只使其不再代表当前稿，但历史导出保持。
- [ ] **4. GREEN 与回归**：再次执行同一 verify_cmd，逐项核对上面的失败场景变为预期结果；运行全量编译/测试。本项不得顺带改变 prompt 或质量基线。
- [ ] **5. 独立提交和合并**：按 AGENTS.md 明确暂存文件、检查 staged diff、提交信息含 `p38-06`，检查 `main..HEAD` 仅含本项；本地合并后重跑验证，再登记 verified 完成证据。未得到实现授权时停在计划，不能替外部工具预记完成。

**机器验收结果**：verify_cmd 退出0，并满足：并发编辑创建不得混合两代正文；批准前改一章拒绝；崩溃回滚不产生半本书；批准后再改正文，历史edition字节不变。

**执行检查表输入**（这是计划契约，尚不是实现或测试证据）：

```json
{
  "feature": "p38-06",
  "expected_exit": 0,
  "assertions": "并发编辑创建不得混合两代正文；批准前改一章拒绝；崩溃回滚不产生半本书；批准后再改正文，历史edition字节不变。",
  "failure_is_not_success": true
}
```

<a id="p38-07"></a>
### p38-07 — 成书覆盖、批准与指定版本导出接口

- **类型 / 来源**：feature；成书导出
- **依赖**：p38-06, p38-10, p38-13
- **文件范围**：
  - 新增 `src/main/java/com/writing/novel/service/BookEditionService.java`
  - 新增 `src/main/java/com/writing/novel/web/BookEditionController.java`
  - 新增 `src/test/java/com/writing/novel/web/BookEditionExportTest.java`

**输入/输出契约**：POST/GET /book-editions；GET /book-editions/{editionId}；POST /{editionId}/approve {manifestHash,idempotencyKey}；GET /{editionId}/export?format=markdown。

- [ ] **1. 复现/契约测试**：批准版导出章节号、版本、正文hash与manifest逐项相等；后来编辑不改变导出；跨项目404/400；未批准不能冒用成书导出；下载响应头正确。
- [ ] **2. 运行 RED**：执行下列 verify_cmd，保存失败断言与起始 commit。已有业务缺陷必须在未改实现前看到对应行为失败；新接口可先提交最小编译契约再验证其行为失败，不将编译错误计 RED。

```bash
mvn test -Dtest=BookEditionExportTest
```

- [ ] **3. 最小实现**：全部请求校验项目所有权；只有APPROVED可称成书导出，响应Content-Disposition attachment，UTF-8 filename*。保留旧book-export命名“当前草稿导出”；覆盖明细逐项列缺报告/旧报告/缺作者批准/未决问题/承诺处置及准确章节版本。
- [ ] **4. GREEN 与回归**：再次执行同一 verify_cmd，逐项核对上面的失败场景变为预期结果；运行全量编译/测试。本项不得顺带改变 prompt 或质量基线。
- [ ] **5. 独立提交和合并**：按 AGENTS.md 明确暂存文件、检查 staged diff、提交信息含 `p38-07`，检查 `main..HEAD` 仅含本项；本地合并后重跑验证，再登记 verified 完成证据。未得到实现授权时停在计划，不能替外部工具预记完成。

**机器验收结果**：verify_cmd 退出0，并满足：批准版导出章节号、版本、正文hash与manifest逐项相等；后来编辑不改变导出；跨项目404/400；未批准不能冒用成书导出；下载响应头正确。

**关键断言参考**（准备数据必须走本项说明的 fixture/正式 API，变量在测试 setUp 中创建）：

```java
// before/after 是同一批准 edition 在当前正文修改前后的 HTTP 下载字节。
assertArrayEquals(before, after);
assertEquals(manifestHash, approvedEdition.manifestHash());
assertTrue(response.getHeader("Content-Disposition").startsWith("attachment;"));
```

<a id="p38-08"></a>
### p38-08 — 成书版本选择、覆盖阻塞与下载界面

- **类型 / 来源**：feature；成书批准UI
- **依赖**：p38-07, p36-11
- **文件范围**：
  - 修改 `frontend/src/api/client.ts`
  - 修改 `frontend/src/components/BookReviewView.tsx`
  - 新增 `qa/collaboration/edition-export.spec.ts`

**输入/输出契约**：页面区分当前稿检查与成书版本列表，显示editionId简短标识/创建时间/状态/章节版本；批准操作发送manifestHash。

- [ ] **1. 复现/契约测试**：补齐各章批准→创建edition→批准→下载→修改一章→下载旧edition逐字相同；当前稿缺批准明显标注；390px可操作。
- [ ] **2. 运行 RED**：执行下列 verify_cmd，保存失败断言与起始 commit。已有业务缺陷必须在未改实现前看到对应行为失败；新接口可先提交最小编译契约再验证其行为失败，不将编译错误计 RED。

```bash
npm --prefix qa/collaboration test -- edition-export.spec.ts
```

- [ ] **3. 最小实现**：当前稿不完整可查看清单但不批准；阻塞项直达具体章/意见/承诺。批准前展示版本清单，下载选择明确edition；稿件已变化时显示“此为历史成书版本”。不再把DONE当作批准。
- [ ] **4. GREEN 与回归**：再次执行同一 verify_cmd，逐项核对上面的失败场景变为预期结果；运行全量编译/测试与前端构建。本项不得顺带改变 prompt 或质量基线。
- [ ] **5. 独立提交和合并**：按 AGENTS.md 明确暂存文件、检查 staged diff、提交信息含 `p38-08`，检查 `main..HEAD` 仅含本项；本地合并后重跑验证，再登记 verified 完成证据。未得到实现授权时停在计划，不能替外部工具预记完成。

**机器验收结果**：verify_cmd 退出0，并满足：补齐各章批准→创建edition→批准→下载→修改一章→下载旧edition逐字相同；当前稿缺批准明显标注；390px可操作。

**执行检查表输入**（这是计划契约，尚不是实现或测试证据）：

```json
{
  "feature": "p38-08",
  "expected_exit": 0,
  "assertions": "补齐各章批准→创建edition→批准→下载→修改一章→下载旧edition逐字相同；当前稿缺批准明显标注；390px可操作。",
  "failure_is_not_success": true
}
```

<a id="p38-09"></a>
### p38-09 — 版本绑定的问题裁决与豁免模型

- **类型 / 来源**：feature；误报/例外/延期
- **依赖**：p36-02, p37-06
- **文件范围**：
  - 新增 `src/main/java/com/writing/novel/domain/ReviewDisposition.java`
  - 修改 `src/main/java/com/writing/novel/persistence/sqlite/SqliteSchemaInitializer.java`
  - 新增 `src/test/java/com/writing/novel/persistence/DispositionSchemaTest.java`

**输入/输出契约**：ReviewDisposition含id/projectId/issueId/issueFingerprint/identity/action=FALSE_POSITIVE|EXCEPTION|DEFER/理由/deferUntilChapter/createdAt；原问题不删除。

- [ ] **1. 复现/契约测试**：缺理由拒绝，DEFER缺期限拒绝；同一句描述两个问题不混淆；旧稿裁决不自动有效；迁移幂等。
- [ ] **2. 运行 RED**：执行下列 verify_cmd，保存失败断言与起始 commit。已有业务缺陷必须在未改实现前看到对应行为失败；新接口可先提交最小编译契约再验证其行为失败，不将编译错误计 RED。

```bash
mvn test -Dtest=DispositionSchemaTest
```

- [ ] **3. 最小实现**：建review_dispositions；问题身份由类型+来源报告+章节身份+问题ID定义，不能靠数组下标。EXCEPTION是作者接受风险，DEFER有期限；章内容变动不继承旧裁决，必须重新确认。mustResolve承诺例外用独立promiseId关联，不伪造问题ID。
- [ ] **4. GREEN 与回归**：再次执行同一 verify_cmd，逐项核对上面的失败场景变为预期结果；运行全量编译/测试。本项不得顺带改变 prompt 或质量基线。
- [ ] **5. 独立提交和合并**：按 AGENTS.md 明确暂存文件、检查 staged diff、提交信息含 `p38-09`，检查 `main..HEAD` 仅含本项；本地合并后重跑验证，再登记 verified 完成证据。未得到实现授权时停在计划，不能替外部工具预记完成。

**机器验收结果**：verify_cmd 退出0，并满足：缺理由拒绝，DEFER缺期限拒绝；同一句描述两个问题不混淆；旧稿裁决不自动有效；迁移幂等。

**执行检查表输入**（这是计划契约，尚不是实现或测试证据）：

```json
{
  "feature": "p38-09",
  "expected_exit": 0,
  "assertions": "缺理由拒绝，DEFER缺期限拒绝；同一句描述两个问题不混淆；旧稿裁决不自动有效；迁移幂等。",
  "failure_is_not_success": true
}
```

<a id="p38-10"></a>
### p38-10 — 问题裁决存取与批准门禁统一计算

- **类型 / 来源**：feature；审查裁决与门禁
- **依赖**：p38-09, p37-07, p36-03
- **文件范围**：
  - 修改 `src/main/java/com/writing/novel/spi/NovelRepository.java`
  - 修改 `src/main/java/com/writing/novel/persistence/sqlite/SqliteNovelRepository.java`
  - 新增 `src/test/java/com/writing/novel/persistence/DispositionApprovalGateTest.java`

**输入/输出契约**：新增 saveDisposition(expectedReviewSetHash)/listDispositions；readReviewContext提供问题证据及裁决、未落实HARD；approveChapter/approveBookEdition共同采用这些资格约束。

- [ ] **1. 复现/契约测试**：旧裁决、过期DEFER、无当前报告、未落实HARD分别阻断；新裁决后旧审阅token409；人工例外留痕且能按明确规则放行；成书仍拒绝延期未清。
- [ ] **2. 运行 RED**：执行下列 verify_cmd，保存失败断言与起始 commit。已有业务缺陷必须在未改实现前看到对应行为失败；新接口可先提交最小编译契约再验证其行为失败，不将编译错误计 RED。

```bash
mvn test -Dtest=DispositionApprovalGateTest
```

- [ ] **3. 最小实现**：新增裁决会改变reviewSetHash；未处理HIGH/未落实HARD阻止章批准。DEFER可允许阶段性继续但到期阻塞，成书批准要求清零或显式EXCEPTION。例外不把reportCurrent改true；无当前报告必须重检。schema已有但无裁决时保持保守，不默认为全部解决。
- [ ] **4. GREEN 与回归**：再次执行同一 verify_cmd，逐项核对上面的失败场景变为预期结果；运行全量编译/测试。本项不得顺带改变 prompt 或质量基线。
- [ ] **5. 独立提交和合并**：按 AGENTS.md 明确暂存文件、检查 staged diff、提交信息含 `p38-10`，检查 `main..HEAD` 仅含本项；本地合并后重跑验证，再登记 verified 完成证据。未得到实现授权时停在计划，不能替外部工具预记完成。

**机器验收结果**：verify_cmd 退出0，并满足：旧裁决、过期DEFER、无当前报告、未落实HARD分别阻断；新裁决后旧审阅token409；人工例外留痕且能按明确规则放行；成书仍拒绝延期未清。

**执行检查表输入**（这是计划契约，尚不是实现或测试证据）：

```json
{
  "feature": "p38-10",
  "expected_exit": 0,
  "assertions": "旧裁决、过期DEFER、无当前报告、未落实HARD分别阻断；新裁决后旧审阅token409；人工例外留痕且能按明确规则放行；成书仍拒绝延期未清。",
  "failure_is_not_success": true
}
```

<a id="p38-11"></a>
### p38-11 — 问题裁决接口与按当前稿重新检查

- **类型 / 来源**：feature；A3裁决闭环
- **依赖**：p38-10, p36-04
- **文件范围**：
  - 新增 `src/main/java/com/writing/novel/web/ReviewDecisionController.java`
  - 修改 `frontend/src/api/client.ts`
  - 新增 `src/test/java/com/writing/novel/web/ReviewDispositionApiTest.java`

**输入/输出契约**：POST /chapters/{n}/review-dispositions携identity/reviewSetHash/issueId/action/reason/deferUntilChapter；POST /chapters/{n}/recheck携identity，返回任务状态。

- [ ] **1. 复现/契约测试**：HTTP逐种裁决成功/缺字段/旧稿409；检查期间新稿产生后报告不冒充新稿；网络重试不创建多份裁决。
- [ ] **2. 运行 RED**：执行下列 verify_cmd，保存失败断言与起始 commit。已有业务缺陷必须在未改实现前看到对应行为失败；新接口可先提交最小编译契约再验证其行为失败，不将编译错误计 RED。

```bash
mvn test -Dtest=ReviewDispositionApiTest
```

- [ ] **3. 最小实现**：重新检查通过已有编排刷新路径执行，不直接Agent相互调用；异步返回只可绑定起始identity，期间编辑后旧检查保持历史不覆盖当前；对尚无报告给出真实检查状态。
- [ ] **4. GREEN 与回归**：再次执行同一 verify_cmd，逐项核对上面的失败场景变为预期结果；运行全量编译/测试与前端构建。本项影响生成或检查流程，还须执行入口中的质量门禁 QG。
- [ ] **5. 独立提交和合并**：按 AGENTS.md 明确暂存文件、检查 staged diff、提交信息含 `p38-11`，检查 `main..HEAD` 仅含本项；本地合并后重跑验证，再登记 verified 完成证据。未得到实现授权时停在计划，不能替外部工具预记完成。

**机器验收结果**：verify_cmd 退出0，并满足：HTTP逐种裁决成功/缺字段/旧稿409；检查期间新稿产生后报告不冒充新稿；网络重试不创建多份裁决。

**执行检查表输入**（这是计划契约，尚不是实现或测试证据）：

```json
{
  "feature": "p38-11",
  "expected_exit": 0,
  "assertions": "HTTP逐种裁决成功/缺字段/旧稿409；检查期间新稿产生后报告不冒充新稿；网络重试不创建多份裁决。",
  "failure_is_not_success": true
}
```

<a id="p38-12"></a>
### p38-12 — 桌面与窄屏共享完整审查操作

- **类型 / 来源**：feature；A6完整闭环
- **依赖**：p38-11, p37-13, p36-05
- **文件范围**：
  - 新增 `frontend/src/components/ReviewPanel.tsx`
  - 修改 `frontend/src/components/InteractiveReview.tsx`
  - 新增 `qa/collaboration/review-dispositions.spec.ts`

**输入/输出契约**：ReviewPanel(projectId,chapterIdentity,onChanged)同时用于桌面栏与Drawer，包含报告有效性、问题证据、裁决、重检、DirectivePanel、批准。

- [ ] **1. 复现/契约测试**：相同数据桌面与390px问题数/动作一致；Tab/Enter完成误报、例外、延期及确认；旧稿不能裁决当前；抽屉内可看到意见落实和错误重试；截图无截断遮挡。
- [ ] **2. 运行 RED**：执行下列 verify_cmd，保存失败断言与起始 commit。已有业务缺陷必须在未改实现前看到对应行为失败；新接口可先提交最小编译契约再验证其行为失败，不将编译错误计 RED。

```bash
npm --prefix qa/collaboration test -- review-dispositions.spec.ts
```

- [ ] **3. 最小实现**：移除两套不一致JSX；窄屏85%宽可滚动，关闭焦点返回审查按钮。所有严重性/问题类型中文标签，保留原类型仅技术详情；每条裁决有理由、确认动作和结果；候选管理可使用现有区块，按钮可达。
- [ ] **4. GREEN 与回归**：再次执行同一 verify_cmd，逐项核对上面的失败场景变为预期结果；运行全量编译/测试与前端构建。本项不得顺带改变 prompt 或质量基线。
- [ ] **5. 独立提交和合并**：按 AGENTS.md 明确暂存文件、检查 staged diff、提交信息含 `p38-12`，检查 `main..HEAD` 仅含本项；本地合并后重跑验证，再登记 verified 完成证据。未得到实现授权时停在计划，不能替外部工具预记完成。

**机器验收结果**：verify_cmd 退出0，并满足：相同数据桌面与390px问题数/动作一致；Tab/Enter完成误报、例外、延期及确认；旧稿不能裁决当前；抽屉内可看到意见落实和错误重试；截图无截断遮挡。

**执行检查表输入**（这是计划契约，尚不是实现或测试证据）：

```json
{
  "feature": "p38-12",
  "expected_exit": 0,
  "assertions": "相同数据桌面与390px问题数/动作一致；Tab/Enter完成误报、例外、延期及确认；旧稿不能裁决当前；抽屉内可看到意见落实和错误重试；截图无截断遮挡。",
  "failure_is_not_success": true
}
```

<a id="p38-13"></a>
### p38-13 — 成书门禁与承诺处置采用同一规则

- **类型 / 来源**：feature；计划收尾与最终审查一致
- **依赖**：p38-02, p38-10
- **文件范围**：
  - 新增 `src/main/java/com/writing/novel/service/EndingDecisionPolicy.java`
  - 修改 `src/main/java/com/writing/novel/orchestrator/NovelOrchestrator.java`
  - 新增 `src/test/java/com/writing/novel/orchestrator/EndingDecisionIntegrationTest.java`

**输入/输出契约**：EndingDecisionPolicy.evaluate(projectId,targetChapter)返回blockingPromises、acceptedOpenEndings、deferredItems；NovelOrchestrator.finalAudit和自动尾声补写采用它。

- [ ] **1. 复现/契约测试**：24→20后open-end不追加尾声，未处置mustResolve仍阻塞；迁移到20但原文未兑现不能PASS；延期问题未清成书阻塞。
- [ ] **2. 运行 RED**：执行下列 verify_cmd，保存失败断言与起始 commit。已有业务缺陷必须在未改实现前看到对应行为失败；新接口可先提交最小编译契约再验证其行为失败，不将编译错误计 RED。

```bash
mvn test -Dtest=EndingDecisionIntegrationTest
```

- [ ] **3. 最小实现**：接受开放结局的承诺不再触发自动追加尾声，但仍在成书清单显示未回收且作者已接受；MIGRATE只改目标，不伪造resolved。必须回收项无明确有效豁免仍阻塞；篇幅已达目标不能为已接受开放结局继续生成21章。
- [ ] **4. GREEN 与回归**：再次执行同一 verify_cmd，逐项核对上面的失败场景变为预期结果；运行全量编译/测试。本项影响生成或检查流程，还须执行入口中的质量门禁 QG。
- [ ] **5. 独立提交和合并**：按 AGENTS.md 明确暂存文件、检查 staged diff、提交信息含 `p38-13`，检查 `main..HEAD` 仅含本项；本地合并后重跑验证，再登记 verified 完成证据。未得到实现授权时停在计划，不能替外部工具预记完成。

**机器验收结果**：verify_cmd 退出0，并满足：24→20后open-end不追加尾声，未处置mustResolve仍阻塞；迁移到20但原文未兑现不能PASS；延期问题未清成书阻塞。

**执行检查表输入**（这是计划契约，尚不是实现或测试证据）：

```json
{
  "feature": "p38-13",
  "expected_exit": 0,
  "assertions": "24→20后open-end不追加尾声，未处置mustResolve仍阻塞；迁移到20但原文未兑现不能PASS；延期问题未清成书阻塞。",
  "failure_is_not_success": true
}
```
