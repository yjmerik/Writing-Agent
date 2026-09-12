# Phase36 版本安全与作者草稿 Implementation Plan

> **For agentic workers:** When implementation is authorized, select an allowed execution method: use superpowers:subagent-driven-development when delegation is permitted and useful for independent tasks; otherwise use superpowers:executing-plans. Steps use checkbox (`- [ ]`) syntax for tracking. A plan-only request does not authorize implementation.

**Goal:** 完成版本安全与作者草稿的可验证修复，不扩展解释已有切片的完成记录。

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

<a id="p36-01"></a>
### p36-01 — 建立真实浏览器回归入口

- **类型 / 来源**：feature；所有 UI 验证不能只 grep 源码
- **依赖**：无；可从当前 main 启动
- **文件范围**：
  - 新增 `qa/collaboration/package.json`
  - 新增 `qa/collaboration/package-lock.json`
  - 新增 `qa/collaboration/smoke.spec.ts`

**输入/输出契约**：独立 QA 包使用 @playwright/test；测试读取 QA_BASE_URL，仅允许 localhost/127.0.0.1，未设置即失败；npm test 转发 playwright test --workers=1。

- [ ] **1. 复现/契约测试**：正常本地服务通过；错误端口、空白页、页面运行异常均让命令非零退出；保存测试结果、截图和 trace 到 QA_ARTIFACT_DIR，不放小说正文进 Git。
- [ ] **2. 运行 RED**：执行下列 verify_cmd，保存失败断言与起始 commit。已有业务缺陷必须在未改实现前看到对应行为失败；新接口可先提交最小编译契约再验证其行为失败，不将编译错误计 RED。

```bash
npm --prefix qa/collaboration test -- smoke.spec.ts
```

- [ ] **3. 最小实现**：固定并锁定实现当天核验可用的 Playwright 精确版本；不得依赖外部工具专有浏览器状态。smoke 启动两个 BrowserContext，访问项目列表，检查标题、非空主界面、无框架错误遮罩；收集 pageerror/console.error。依赖安装与浏览器安装只发生在此特性。
- [ ] **4. GREEN 与回归**：再次执行同一 verify_cmd，逐项核对上面的失败场景变为预期结果；运行全量编译/测试与前端构建。本项不得顺带改变 prompt 或质量基线。
- [ ] **5. 独立提交和合并**：按 AGENTS.md 明确暂存文件、检查 staged diff、提交信息含 `p36-01`，检查 `main..HEAD` 仅含本项；本地合并后重跑验证，再登记 verified 完成证据。未得到实现授权时停在计划，不能替外部工具预记完成。

**机器验收结果**：verify_cmd 退出0，并满足：正常本地服务通过；错误端口、空白页、页面运行异常均让命令非零退出；保存测试结果、截图和 trace 到 QA_ARTIFACT_DIR，不放小说正文进 Git。

**执行检查表输入**（这是计划契约，尚不是实现或测试证据）：

```json
{
  "feature": "p36-01",
  "expected_exit": 0,
  "assertions": "正常本地服务通过；错误端口、空白页、页面运行异常均让命令非零退出；保存测试结果、截图和 trace 到 QA_ARTIFACT_DIR，不放小说正文进 Git。",
  "failure_is_not_success": true
}
```

<a id="p36-02"></a>
### p36-02 — 定义不可变审阅身份与批准审计表

- **类型 / 来源**：feature；A4；DONE不等于作者批准
- **依赖**：无；可从当前 main 启动
- **文件范围**：
  - 新增 `src/main/java/com/writing/novel/domain/ChapterApproval.java`
  - 修改 `src/main/java/com/writing/novel/persistence/sqlite/SqliteSchemaInitializer.java`
  - 新增 `src/test/java/com/writing/novel/persistence/ApprovalSchemaTest.java`

**输入/输出契约**：新增 ChapterApproval，内嵌 Identity(projectId,chapterIndex,version,contentHash)、Request(identity,reviewSetHash,idempotencyKey,reason)、Result(approvalId,identity,replayed)。contentHash=SHA256 UTF-8 标题长度前缀+标题+正文长度前缀+正文，64位hex；不含状态/updatedAt。 内嵌ReviewContext(identity,reviewSetHash,pendingCheckpointIds,barrierIds,reportCurrent,approval,issues,dispositions,directives)，预留集合默认空但不默认已通过。

- [ ] **1. 复现/契约测试**：旧库连续初始化两次无丢行；标题/正文任一变化改变hash，状态变化不改变；批准记录不能被普通章保存覆盖；相同幂等键唯一。
- [ ] **2. 运行 RED**：执行下列 verify_cmd，保存失败断言与起始 commit。已有业务缺陷必须在未改实现前看到对应行为失败；新接口可先提交最小编译契约再验证其行为失败，不将编译错误计 RED。

```bash
mvn test -Dtest=ApprovalSchemaTest
```

- [ ] **3. 最小实现**：建 chapter_approvals 与 review_barrier_history；前者保存 identity、reviewSetHash、reason、createdAt、idempotencyKey，后者追加旧屏障身份与修订前后版本。对 projectId+idempotencyKey 加唯一约束。已存在 DONE 不回填为作者批准；旧12位 ReviewFingerprint 保持现有报告用途。
- [ ] **4. GREEN 与回归**：再次执行同一 verify_cmd，逐项核对上面的失败场景变为预期结果；运行全量编译/测试。本项不得顺带改变 prompt 或质量基线。
- [ ] **5. 独立提交和合并**：按 AGENTS.md 明确暂存文件、检查 staged diff、提交信息含 `p36-02`，检查 `main..HEAD` 仅含本项；本地合并后重跑验证，再登记 verified 完成证据。未得到实现授权时停在计划，不能替外部工具预记完成。

**机器验收结果**：verify_cmd 退出0，并满足：旧库连续初始化两次无丢行；标题/正文任一变化改变hash，状态变化不改变；批准记录不能被普通章保存覆盖；相同幂等键唯一。

**执行检查表输入**（这是计划契约，尚不是实现或测试证据）：

```json
{
  "feature": "p36-02",
  "expected_exit": 0,
  "assertions": "旧库连续初始化两次无丢行；标题/正文任一变化改变hash，状态变化不改变；批准记录不能被普通章保存覆盖；相同幂等键唯一。",
  "failure_is_not_success": true
}
```

<a id="p36-03"></a>
### p36-03 — 实现章节批准与屏障的原子比较提交

- **类型 / 来源**：bug；A4
- **依赖**：p36-02
- **文件范围**：
  - 修改 `src/main/java/com/writing/novel/spi/NovelRepository.java`
  - 修改 `src/main/java/com/writing/novel/persistence/sqlite/SqliteNovelRepository.java`
  - 新增 `src/test/java/com/writing/novel/persistence/VersionedApprovalRepositoryTest.java`

**输入/输出契约**：NovelRepository 增 readReviewContext(projectId,index)、approveChapter(ChapterApproval.Request)。ReviewContext 作为 ChapterApproval 内嵌记录已由 p36-02 定义：identity、reviewSetHash、pendingCheckpointIds、barrierIds、reportCurrent、approval。

- [ ] **1. 复现/契约测试**：两连接同时用不同key批准同一待办，仅一方成功；同key同请求重试返回相同approvalId；同key不同内容冲突；旧version/hash/reviewSetHash均拒绝；在批准行写入后注入SQL错误，正文状态/屏障/checkpoint/审计全部不变。
- [ ] **2. 运行 RED**：执行下列 verify_cmd，保存失败断言与起始 commit。已有业务缺陷必须在未改实现前看到对应行为失败；新接口可先提交最小编译契约再验证其行为失败，不将编译错误计 RED。

```bash
mvn test -Dtest=VersionedApprovalRepositoryTest
```

- [ ] **3. 最小实现**：同一 SQLite 连接/事务读取并比较正文身份、当前待审集合指纹；写批准审计、更新精确版本状态、只解决匹配稿号屏障、处理同章 checkpoint、按剩余待审项决定项目是否退出停点。正文/待办变化一律冲突并回滚。不得在事务内调用会新开连接的公共仓储方法。新增 SPI 缺省实现可抛 UnsupportedOperationException 保持旧 stub 可编译，禁止默认成功。
- [ ] **4. GREEN 与回归**：再次执行同一 verify_cmd，逐项核对上面的失败场景变为预期结果；运行全量编译/测试。本项不得顺带改变 prompt 或质量基线。
- [ ] **5. 独立提交和合并**：按 AGENTS.md 明确暂存文件、检查 staged diff、提交信息含 `p36-03`，检查 `main..HEAD` 仅含本项；本地合并后重跑验证，再登记 verified 完成证据。未得到实现授权时停在计划，不能替外部工具预记完成。

**机器验收结果**：verify_cmd 退出0，并满足：两连接同时用不同key批准同一待办，仅一方成功；同key同请求重试返回相同approvalId；同key不同内容冲突；旧version/hash/reviewSetHash均拒绝；在批准行写入后注入SQL错误，正文状态/屏障/checkpoint/审计全部不变。

**执行检查表输入**（这是计划契约，尚不是实现或测试证据）：

```json
{
  "feature": "p36-03",
  "expected_exit": 0,
  "assertions": "两连接同时用不同key批准同一待办，仅一方成功；同key同请求重试返回相同approvalId；同key不同内容冲突；旧version/hash/reviewSetHash均拒绝；在批准行写入后注入SQL错误，正文状态/屏障/checkpoint/审计全部不变。",
  "failure_is_not_success": true
}
```

<a id="p36-04"></a>
### p36-04 — 暴露携带版本的审阅上下文与批准接口

- **类型 / 来源**：bug；A4
- **依赖**：p36-03
- **文件范围**：
  - 新增 `src/main/java/com/writing/novel/web/ReviewDecisionController.java`
  - 新增 `src/test/java/com/writing/novel/web/VersionedReviewDecisionTest.java`

**输入/输出契约**：GET /api/projects/{id}/chapters/{n}/review-context；POST 同路径 /review-decisions 接收 identity + reviewSetHash + idempotencyKey + reason，返回 ChapterApproval.Result。

- [ ] **1. 复现/契约测试**：MockMvc 实际 POST：v2读取→正式PUT产生v3→提交v2必须409且v3待审；缺版本400；其他项目不能批准；相同请求重试幂等。
- [ ] **2. 运行 RED**：执行下列 verify_cmd，保存失败断言与起始 commit。已有业务缺陷必须在未改实现前看到对应行为失败；新接口可先提交最小编译契约再验证其行为失败，不将编译错误计 RED。

```bash
mvn test -Dtest=VersionedReviewDecisionTest
```

- [ ] **3. 最小实现**：控制器注入 NovelRepository 和 WritingTaskExecutor；先 acquire 项目工作权，再调用原子仓储；finally 释放。路径与identity所有者不符400，缺字段400；版本/待办冲突409且返回当前身份供刷新；WORK_OWNERSHIP_CONFLICT沿用现有码。服务端不会补填客户端缺失的版本。
- [ ] **4. GREEN 与回归**：再次执行同一 verify_cmd，逐项核对上面的失败场景变为预期结果；运行全量编译/测试。本项不得顺带改变 prompt 或质量基线。
- [ ] **5. 独立提交和合并**：按 AGENTS.md 明确暂存文件、检查 staged diff、提交信息含 `p36-04`，检查 `main..HEAD` 仅含本项；本地合并后重跑验证，再登记 verified 完成证据。未得到实现授权时停在计划，不能替外部工具预记完成。

**机器验收结果**：verify_cmd 退出0，并满足：MockMvc 实际 POST：v2读取→正式PUT产生v3→提交v2必须409且v3待审；缺版本400；其他项目不能批准；相同请求重试幂等。

**关键断言参考**（准备数据必须走本项说明的 fixture/正式 API，变量在测试 setUp 中创建）：

```java
// 测试先用正式接口准备 v2，读取 review-context 后通过 PUT 保存 v3。
var response = mvc.perform(post("/api/projects/" + projectId + "/chapters/1/review-decisions")
    .contentType("application/json").content(staleRequestJson)).andReturn();
assertEquals(409, response.getResponse().getStatus());
assertEquals(Chapter.Status.NEEDS_MANUAL_REVIEW,
    repository.findChapterByIndex(projectId, 1).orElseThrow().getStatus());
assertFalse(repository.findActiveReviewBarriers(projectId).isEmpty());
```

<a id="p36-05"></a>
### p36-05 — 所有章节审阅按钮使用同一已读版本

- **类型 / 来源**：bug；A4
- **依赖**：p36-01, p36-04
- **文件范围**：
  - 修改 `frontend/src/api/client.ts`
  - 修改 `frontend/src/components/InteractiveReview.tsx`
  - 新增 `qa/collaboration/review-version.spec.ts`

**输入/输出契约**：api.getReviewContext 与 api.approveChapterRevision；审批 token 只能与已加载正文 identity 一致时启用；AFTER_CHAPTER/ON_CONTINUITY_FIX 卡与正文、抽屉调用同一handler，主按钮统一命名“批准当前稿”。

- [ ] **1. 复现/契约测试**：两个真实窗口：A显示v2，B保存v3，A批准必须409并仍不能续写；A显式刷新审阅后才可批准v3；两套旧按钮不会一个成功另一个仍卡住；390px同样验证。
- [ ] **2. 运行 RED**：执行下列 verify_cmd，保存失败断言与起始 commit。已有业务缺陷必须在未改实现前看到对应行为失败；新接口可先提交最小编译契约再验证其行为失败，不将编译错误计 RED。

```bash
npm --prefix qa/collaboration test -- review-version.spec.ts
```

- [ ] **3. 最小实现**：正文加载时请求上下文，按请求代次抛弃迟到结果；选择章/稿改变清空旧token。审批卡展示当前正文稿号/摘要，不继续显示旧payload。409保留草稿并强制重读正文和审阅上下文，不自动重试批准。同一用户点击的网络重试复用idempotencyKey。
- [ ] **4. GREEN 与回归**：再次执行同一 verify_cmd，逐项核对上面的失败场景变为预期结果；运行全量编译/测试与前端构建。本项不得顺带改变 prompt 或质量基线。
- [ ] **5. 独立提交和合并**：按 AGENTS.md 明确暂存文件、检查 staged diff、提交信息含 `p36-05`，检查 `main..HEAD` 仅含本项；本地合并后重跑验证，再登记 verified 完成证据。未得到实现授权时停在计划，不能替外部工具预记完成。

**机器验收结果**：verify_cmd 退出0，并满足：两个真实窗口：A显示v2，B保存v3，A批准必须409并仍不能续写；A显式刷新审阅后才可批准v3；两套旧按钮不会一个成功另一个仍卡住；390px同样验证。

**关键断言参考**（准备数据必须走本项说明的 fixture/正式 API，变量在测试 setUp 中创建）：

```typescript
// page A 已显示 v2，page B 经编辑器保存 v3；不要先 reload A。
const rejected = page.waitForResponse(r => r.url().endsWith('/review-decisions'));
await page.getByRole('button', {name:'批准当前稿', exact:true}).click();
expect((await rejected).status()).toBe(409);
await expect(page.getByRole('button', {name:'继续协作', exact:true})).toBeDisabled();
```

<a id="p36-06"></a>
### p36-06 — 关闭无版本批准的兼容旁路

- **类型 / 来源**：bug；A4旧入口绕过
- **依赖**：p36-05, p36-12, p36-13
- **文件范围**：
  - 修改 `src/main/java/com/writing/novel/web/ContentController.java`
  - 修改 `src/main/java/com/writing/novel/web/CheckpointController.java`
  - 新增 `src/test/java/com/writing/novel/web/LegacyApprovalGateTest.java`

**输入/输出契约**：旧 POST /chapters/{n}/resolve-review 及章节类 checkpoint approve/modify 不再接受无版本放行；返回409并提示刷新客户端，初始设定/卷计划审批保留独立流程。

- [ ] **1. 复现/契约测试**：逐一向所有旧入口真实POST，无token不改变章/屏障/checkpoint；初始设定仍可批准后继续；新的版本审批全量通过。
- [ ] **2. 运行 RED**：执行下列 verify_cmd，保存失败断言与起始 commit。已有业务缺陷必须在未改实现前看到对应行为失败；新接口可先提交最小编译契约再验证其行为失败，不将编译错误计 RED。

```bash
mvn test -Dtest=LegacyApprovalGateTest
```

- [ ] **3. 最小实现**：拒绝旧章节批准端点，拒绝 modify 先写新稿又默认批准；重写动作可生成/保存新稿但必须回到待审。检查 CONTINUITY 类型不能通过 legacy modify/approve 绕过新事务。兼容移除仅限章节审批，不能误伤初始设定构思流程。
- [ ] **4. GREEN 与回归**：再次执行同一 verify_cmd，逐项核对上面的失败场景变为预期结果；运行全量编译/测试。本项不得顺带改变 prompt 或质量基线。
- [ ] **5. 独立提交和合并**：按 AGENTS.md 明确暂存文件、检查 staged diff、提交信息含 `p36-06`，检查 `main..HEAD` 仅含本项；本地合并后重跑验证，再登记 verified 完成证据。未得到实现授权时停在计划，不能替外部工具预记完成。

**机器验收结果**：verify_cmd 退出0，并满足：逐一向所有旧入口真实POST，无token不改变章/屏障/checkpoint；初始设定仍可批准后继续；新的版本审批全量通过。

**执行检查表输入**（这是计划契约，尚不是实现或测试证据）：

```json
{
  "feature": "p36-06",
  "expected_exit": 0,
  "assertions": "逐一向所有旧入口真实POST，无token不改变章/屏障/checkpoint；初始设定仍可批准后继续；新的版本审批全量通过。",
  "failure_is_not_success": true
}
```

<a id="p36-07"></a>
### p36-07 — 正式修订原子失效批准并记录屏障历史

- **类型 / 来源**：bug；A4与历史不可变
- **依赖**：p36-03
- **文件范围**：
  - 修改 `src/main/java/com/writing/novel/persistence/sqlite/SqliteNovelRepository.java`
  - 修改 `src/main/java/com/writing/novel/service/ChapterRevisionService.java`
  - 新增 `src/test/java/com/writing/novel/service/RevisionBarrierAtomicityTest.java`

**输入/输出契约**：saveChapterRevision 在其原事务中增加屏障旧身份审计、重绑及当前批准有效性失效；历史批准保留原identity，只读查询按identity判有效。

- [ ] **1. 复现/契约测试**：修订事务中屏障更新失败则整个正文/历史回滚；v2批准后编辑v3，v2批准仍可查询但不算v3批准；回退也生成新稿待审；无变更保存不新增历史。
- [ ] **2. 运行 RED**：执行下列 verify_cmd，保存失败断言与起始 commit。已有业务缺陷必须在未改实现前看到对应行为失败；新接口可先提交最小编译契约再验证其行为失败，不将编译错误计 RED。

```bash
mvn test -Dtest=RevisionBarrierAtomicityTest
```

- [ ] **3. 最小实现**：删除 doEdit 中事务后吞异常的 rebind；编辑、候选、回退通过相同边界。已有人审屏障的新稿保持待审；由作者批准过的稿再次修改也必须重新审查。相同内容幂等保存不失效批准；派生任务只可写自己的版本。
- [ ] **4. GREEN 与回归**：再次执行同一 verify_cmd，逐项核对上面的失败场景变为预期结果；运行全量编译/测试。本项不得顺带改变 prompt 或质量基线。
- [ ] **5. 独立提交和合并**：按 AGENTS.md 明确暂存文件、检查 staged diff、提交信息含 `p36-07`，检查 `main..HEAD` 仅含本项；本地合并后重跑验证，再登记 verified 完成证据。未得到实现授权时停在计划，不能替外部工具预记完成。

**机器验收结果**：verify_cmd 退出0，并满足：修订事务中屏障更新失败则整个正文/历史回滚；v2批准后编辑v3，v2批准仍可查询但不算v3批准；回退也生成新稿待审；无变更保存不新增历史。

**执行检查表输入**（这是计划契约，尚不是实现或测试证据）：

```json
{
  "feature": "p36-07",
  "expected_exit": 0,
  "assertions": "修订事务中屏障更新失败则整个正文/历史回滚；v2批准后编辑v3，v2批准仍可查询但不算v3批准；回退也生成新稿待审；无变更保存不新增历史。",
  "failure_is_not_success": true
}
```

<a id="p36-08"></a>
### p36-08 — 同步目录、深链接、页签与浏览器历史

- **类型 / 来源**：bug；A2
- **依赖**：p36-01
- **文件范围**：
  - 修改 `frontend/src/pages/ProjectWorkspace.tsx`
  - 修改 `frontend/src/components/InteractiveReview.tsx`
  - 新增 `qa/collaboration/workspace-navigation.spec.ts`

**输入/输出契约**：保留 #/project/:id/chapter/:n；查询参数 tab=review|world|outline|book|quality|run。ProjectWorkspace 路由是所选章与页签权威来源，子组件 onSelectChapter(n)。 另支持?version=V&anchor=HASH用于打开对应历史正文；缺历史版明确提示，不能退回当前稿冒充。

- [ ] **1. 复现/契约测试**：手动创建7章→点击第7章→刷新仍第7章；切outline刷新仍outline；后退/前进同步；后台新增章不抢焦点；跨项目不能串章。
- [ ] **2. 运行 RED**：执行下列 verify_cmd，保存失败断言与起始 commit。已有业务缺陷必须在未改实现前看到对应行为失败；新接口可先提交最小编译契约再验证其行为失败，不将编译错误计 RED。

```bash
npm --prefix qa/collaboration test -- workspace-navigation.spec.ts
```

- [ ] **3. 最小实现**：普通点击、Enter/Space及待办跳转均更新路由；目录由章号而非数组下标标识；路由参数变化要响应后退/前进，不能只useState初始读取。更新轮询不抢选中章；非法章号显示明确提示并选择存在章；切页签保存返回阅读位置。
- [ ] **4. GREEN 与回归**：再次执行同一 verify_cmd，逐项核对上面的失败场景变为预期结果；运行全量编译/测试与前端构建。本项不得顺带改变 prompt 或质量基线。
- [ ] **5. 独立提交和合并**：按 AGENTS.md 明确暂存文件、检查 staged diff、提交信息含 `p36-08`，检查 `main..HEAD` 仅含本项；本地合并后重跑验证，再登记 verified 完成证据。未得到实现授权时停在计划，不能替外部工具预记完成。

**机器验收结果**：verify_cmd 退出0，并满足：手动创建7章→点击第7章→刷新仍第7章；切outline刷新仍outline；后退/前进同步；后台新增章不抢焦点；跨项目不能串章。

**关键断言参考**（准备数据必须走本项说明的 fixture/正式 API，变量在测试 setUp 中创建）：

```typescript
await page.getByRole('button', {name:'第7章 测试第七章', exact:true}).click();
await expect(page).toHaveURL(/\/chapter\/7/);
await page.reload();
await expect(page.getByRole('button', {name:'让 Agent 修改第7章', exact:true})).toBeVisible();
```

<a id="p36-09"></a>
### p36-09 — 标题与正文草稿恢复、冲突保留及存储失败提示

- **类型 / 来源**：bug；A2
- **依赖**：p36-01
- **文件范围**：
  - 修改 `frontend/src/lib/draftStore.ts`
  - 修改 `frontend/src/components/InteractiveReview.tsx`
  - 新增 `qa/collaboration/draft-recovery.spec.ts`

**输入/输出契约**：loadDraft 返回 {kind:"missing"|"matching"|"conflict"|"unavailable",draft?,error?}；草稿带baseVersion、baseHash、title、content、savedAt。按项目+章节保留当前草稿与冲突副本。

- [ ] **1. 复现/契约测试**：标题单独改、正文单独改、两者同改刷新恢复；B保存新稿后A刷新显示冲突且旧内容可复制；模拟quota异常有警示；保存失败/409草稿仍在；同内容保存历史数量不变。
- [ ] **2. 运行 RED**：执行下列 verify_cmd，保存失败断言与起始 commit。已有业务缺陷必须在未改实现前看到对应行为失败；新接口可先提交最小编译契约再验证其行为失败，不将编译错误计 RED。

```bash
npm --prefix qa/collaboration test -- draft-recovery.spec.ts
```

- [ ] **3. 最小实现**：标题/正文同样暂存；恢复两字段。基准变化保留原草稿并展示并排比较/复制/另存，不静默隐藏；保存409也不能把本地内容替换成新稿。新草稿不得覆盖未处理冲突副本；存储配额失败提示未暂存并启用离开保护；正式保存成功后才清除匹配草稿。旧格式无hash仅可提示核对恢复，不自动覆盖。
- [ ] **4. GREEN 与回归**：再次执行同一 verify_cmd，逐项核对上面的失败场景变为预期结果；运行全量编译/测试与前端构建。本项不得顺带改变 prompt 或质量基线。
- [ ] **5. 独立提交和合并**：按 AGENTS.md 明确暂存文件、检查 staged diff、提交信息含 `p36-09`，检查 `main..HEAD` 仅含本项；本地合并后重跑验证，再登记 verified 完成证据。未得到实现授权时停在计划，不能替外部工具预记完成。

**机器验收结果**：verify_cmd 退出0，并满足：标题单独改、正文单独改、两者同改刷新恢复；B保存新稿后A刷新显示冲突且旧内容可复制；模拟quota异常有警示；保存失败/409草稿仍在；同内容保存历史数量不变。

**关键断言参考**（准备数据必须走本项说明的 fixture/正式 API，变量在测试 setUp 中创建）：

```typescript
await page.getByRole('textbox', {name:'章节标题', exact:true}).fill('只改标题也应恢复');
await page.reload();
await page.getByRole('button', {name:'编辑正文', exact:true}).click();
await expect(page.getByRole('textbox', {name:'章节标题', exact:true})).toHaveValue('只改标题也应恢复');
```

<a id="p36-10"></a>
### p36-10 — 目标章数改变失效旧预览并刷新大纲

- **类型 / 来源**：bug；篇幅预览绑定
- **依赖**：p36-01
- **文件范围**：
  - 修改 `frontend/src/components/OutlineView.tsx`
  - 新增 `qa/collaboration/plan-preview.spec.ts`

**输入/输出契约**：本地记录 previewTargetNumber；只有 newTarget===previewTargetNumber 且未生效才启用确认。

- [ ] **1. 复现/契约测试**：22预览→改20必须禁用；旧请求迟到不能恢复22预览；确认20后大纲树已裁剪，再保存总纲不会复活21+章。
- [ ] **2. 运行 RED**：执行下列 verify_cmd，保存失败断言与起始 commit。已有业务缺陷必须在未改实现前看到对应行为失败；新接口可先提交最小编译契约再验证其行为失败，不将编译错误计 RED。

```bash
npm --prefix qa/collaboration test -- plan-preview.spec.ts
```

- [ ] **3. 最小实现**：修改目标立即清预览；预览请求用序号丢弃乱序返回；confirm成功重新读取大纲和目标，清掉裁剪前draft/persistedOutline，防保存总纲把旧章纲写回。确认期间禁用目标输入。
- [ ] **4. GREEN 与回归**：再次执行同一 verify_cmd，逐项核对上面的失败场景变为预期结果；运行全量编译/测试与前端构建。本项不得顺带改变 prompt 或质量基线。
- [ ] **5. 独立提交和合并**：按 AGENTS.md 明确暂存文件、检查 staged diff、提交信息含 `p36-10`，检查 `main..HEAD` 仅含本项；本地合并后重跑验证，再登记 verified 完成证据。未得到实现授权时停在计划，不能替外部工具预记完成。

**机器验收结果**：verify_cmd 退出0，并满足：22预览→改20必须禁用；旧请求迟到不能恢复22预览；确认20后大纲树已裁剪，再保存总纲不会复活21+章。

**关键断言参考**（准备数据必须走本项说明的 fixture/正式 API，变量在测试 setUp 中创建）：

```typescript
await page.getByRole('spinbutton', {name:'目标章数'}).fill('22');
await page.getByRole('button', {name:'影响预览', exact:true}).click();
await expect(page.getByRole('button', {name:'确认生效', exact:true})).toBeEnabled();
await page.getByRole('spinbutton', {name:'目标章数'}).fill('20');
await expect(page.getByRole('button', {name:'确认生效', exact:true})).toBeDisabled();
```

<a id="p36-11"></a>
### p36-11 — 成书清单区分流程完成与作者批准

- **类型 / 来源**：bug；成书批准标识
- **依赖**：p36-01
- **文件范围**：
  - 修改 `frontend/src/components/BookReviewView.tsx`
  - 新增 `qa/collaboration/book-state.spec.ts`

**输入/输出契约**：DONE仅显示“正文流程完成”；没有当前身份匹配的批准记录时显示“尚未作者批准”，不推断批准。

- [ ] **1. 复现/契约测试**：自动DONE、手工DONE、旧报告、缺报告四种响应分别渲染正确；页面不出现伪造“已批准流程完成”。
- [ ] **2. 运行 RED**：执行下列 verify_cmd，保存失败断言与起始 commit。已有业务缺陷必须在未改实现前看到对应行为失败；新接口可先提交最小编译契约再验证其行为失败，不将编译错误计 RED。

```bash
npm --prefix qa/collaboration test -- book-state.spec.ts
```

- [ ] **3. 最小实现**：当前只读接口尚无approval时一律视为未知/未批准；旧稿报告与缺报告明确区分；不以doneChapters===totalChapters宣称覆盖完成。完整成书批准在p38提供后再接入。
- [ ] **4. GREEN 与回归**：再次执行同一 verify_cmd，逐项核对上面的失败场景变为预期结果；运行全量编译/测试与前端构建。本项不得顺带改变 prompt 或质量基线。
- [ ] **5. 独立提交和合并**：按 AGENTS.md 明确暂存文件、检查 staged diff、提交信息含 `p36-11`，检查 `main..HEAD` 仅含本项；本地合并后重跑验证，再登记 verified 完成证据。未得到实现授权时停在计划，不能替外部工具预记完成。

**机器验收结果**：verify_cmd 退出0，并满足：自动DONE、手工DONE、旧报告、缺报告四种响应分别渲染正确；页面不出现伪造“已批准流程完成”。

**执行检查表输入**（这是计划契约，尚不是实现或测试证据）：

```json
{
  "feature": "p36-11",
  "expected_exit": 0,
  "assertions": "自动DONE、手工DONE、旧报告、缺报告四种响应分别渲染正确；页面不出现伪造“已批准流程完成”。",
  "failure_is_not_success": true
}
```


<a id="p36-12"></a>
### p36-12 — 迁移批准守卫和真实并发回归

- **类型 / 来源**：feature；A4测试迁移
- **依赖**：p36-04, p36-07
- **文件范围**：
  - 修改 `src/test/java/com/writing/novel/web/ManualReviewResolutionTest.java`
  - 修改 `src/test/java/com/writing/novel/web/ResolveReviewConcurrencyTest.java`
  - 修改 `src/test/java/com/writing/novel/web/ChapterDirectEditTest.java`

**输入/输出契约**：在旧接口仍存在期间迁移成功业务流程到新批准接口，让p36-06关闭旧接口时全量回归依然有效。本项只改既有测试。

- [ ] **1. 基线与差距**：运行当前专项，记录其通过仅说明旧契约；逐项列出新接口必须保持的原业务断言。新增并发/旧身份断言在p36-04/07已实现契约上运行，不伪称本任务发现了RED业务缺陷。
- [ ] **2. 迁移步骤**：成功批准、写作中冲突、非待审状态冲突改用GET review-context和POST review-decisions；并发请求使用同一identity但不同idempotencyKey，严格断言恰好一个200、一个409，异常或500必须失败；相同key重试另测200且replayed=true。旧的仅重绑屏障测试改名如实描述，并补上A读v2→正式PUT生成v3→A真实POST旧identity得到409的用例，不使用反射伪造版本。ChapterDirectEditTest保留旧modify拒绝测试，同时用新版批准验证工作权冲突，避免旧入口永久409后该测试空转。
- [ ] **3. 专项验证**：执行下面命令，任何HTTP异常必须使测试失败，不能归入预期冲突。

```bash
mvn test -Dtest=ManualReviewResolutionTest,ResolveReviewConcurrencyTest,ChapterDirectEditTest
```

- [ ] **4. 全量回归**：运行`mvn compile && mvn test`。若新版契约仍有业务缺陷，先记录实际failing test并按单feature规则回到对应修复，不扩大本项三文件范围。
- [ ] **5. 提交与合并**：按AGENTS.md逐项独立提交并合入main，合并后重跑专项/全量，记录证据再completed。

**机器验收结果**：mvn test -Dtest=ManualReviewResolutionTest,ResolveReviewConcurrencyTest,ChapterDirectEditTest 退出0；原业务断言保持，新版批准实际HTTP请求覆盖通过；无忽略、删除或放宽断言。并发状态严格200/409，旧身份409且新稿仍待审。

<a id="p36-13"></a>
### p36-13 — 迁移屏障恢复与工作台批准回归

- **类型 / 来源**：feature；A4测试迁移
- **依赖**：p36-04, p36-07
- **文件范围**：
  - 修改 `src/test/java/com/writing/novel/web/HitlCanonForeshadowReviewTest.java`
  - 修改 `src/test/java/com/writing/novel/web/ReviewBarrierGateTest.java`
  - 修改 `src/test/java/com/writing/novel/web/WorkspaceStatusApiTest.java`

**输入/输出契约**：在旧接口仍存在期间迁移成功业务流程到新批准接口，让p36-06关闭旧接口时全量回归依然有效。本项只改既有测试。

- [ ] **1. 基线与差距**：运行当前专项，记录其通过仅说明旧契约；逐项列出新接口必须保持的原业务断言。新增并发/旧身份断言在p36-04/07已实现契约上运行，不伪称本任务发现了RED业务缺陷。
- [ ] **2. 迁移步骤**：将成功章节批准准备步骤改成读取review-context后提交明确identity、reviewSetHash和每次新的idempotencyKey；保留原有真典/伏笔、人审阻止下一章、批准后恢复写作、持久屏障与工作台状态断言。初始设定/卷计划checkpoint继续原流程。不得直接在仓储写DONE或删除屏障来绕过批准接口；每个成功批准后验证批准记录identity与被读章节一致。
- [ ] **3. 专项验证**：执行下面命令，任何HTTP异常必须使测试失败，不能归入预期冲突。

```bash
mvn test -Dtest=HitlCanonForeshadowReviewTest,ReviewBarrierGateTest,WorkspaceStatusApiTest
```

- [ ] **4. 全量回归**：运行`mvn compile && mvn test`。若新版契约仍有业务缺陷，先记录实际failing test并按单feature规则回到对应修复，不扩大本项三文件范围。
- [ ] **5. 提交与合并**：按AGENTS.md逐项独立提交并合入main，合并后重跑专项/全量，记录证据再completed。

**机器验收结果**：mvn test -Dtest=HitlCanonForeshadowReviewTest,ReviewBarrierGateTest,WorkspaceStatusApiTest 退出0；原业务断言保持，新版批准实际HTTP请求覆盖通过；无忽略、删除或放宽断言。批准前不能续写，批准后屏障和工作台一致并恢复写作。
