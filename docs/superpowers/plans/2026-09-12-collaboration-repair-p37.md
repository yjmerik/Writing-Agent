# Phase37 候选恢复与意见落实 Implementation Plan

> **For agentic workers:** When implementation is authorized, select an allowed execution method: use superpowers:subagent-driven-development when delegation is permitted and useful for independent tasks; otherwise use superpowers:executing-plans. Steps use checkbox (`- [ ]`) syntax for tracking. A plan-only request does not authorize implementation.

**Goal:** 完成候选恢复与意见落实的可验证修复，不扩展解释已有切片的完成记录。

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

<a id="p37-01"></a>
### p37-01 — 持久修订候选模型与迁移

- **类型 / 来源**：feature；A5候选持久化
- **依赖**：p36-02
- **文件范围**：
  - 新增 `src/main/java/com/writing/novel/domain/RevisionProposal.java`
  - 修改 `src/main/java/com/writing/novel/persistence/sqlite/SqliteSchemaInitializer.java`
  - 新增 `src/test/java/com/writing/novel/persistence/ProposalSchemaTest.java`

**输入/输出契约**：RevisionProposal: id/projectId/targetType/targetId/chapterIndex/passageIndex/baseVersion/sourceHash/instructions/beforeJson/afterJson/summary/status/requestKey/resultJson/createdAt/updatedAt；status=READY|APPLIED|STALE|CANCELLED。

- [ ] **1. 复现/契约测试**：重复迁移无损；嵌套JSON/换行能恢复；跨项目key隔离；APPLIED不回到READY。
- [ ] **2. 运行 RED**：执行下列 verify_cmd，保存失败断言与起始 commit。已有业务缺陷必须在未改实现前看到对应行为失败；新接口可先提交最小编译契约再验证其行为失败，不将编译错误计 RED。

```bash
mvn test -Dtest=ProposalSchemaTest
```

- [ ] **3. 最小实现**：建revision_proposals，project+requestKey唯一；before/after/instructions只存DB不写日志。生成中不是READY；取消留记录。sourceHash与p36正文身份兼容，非章节对象使用规范JSON全SHA256。无TTL自动删除未处理候选。
- [ ] **4. GREEN 与回归**：再次执行同一 verify_cmd，逐项核对上面的失败场景变为预期结果；运行全量编译/测试。本项不得顺带改变 prompt 或质量基线。
- [ ] **5. 独立提交和合并**：按 AGENTS.md 明确暂存文件、检查 staged diff、提交信息含 `p37-01`，检查 `main..HEAD` 仅含本项；本地合并后重跑验证，再登记 verified 完成证据。未得到实现授权时停在计划，不能替外部工具预记完成。

**机器验收结果**：verify_cmd 退出0，并满足：重复迁移无损；嵌套JSON/换行能恢复；跨项目key隔离；APPLIED不回到READY。

**执行检查表输入**（这是计划契约，尚不是实现或测试证据）：

```json
{
  "feature": "p37-01",
  "expected_exit": 0,
  "assertions": "重复迁移无损；嵌套JSON/换行能恢复；跨项目key隔离；APPLIED不回到READY。",
  "failure_is_not_success": true
}
```

<a id="p37-02"></a>
### p37-02 — 候选持久存取与章节原子应用协议

- **类型 / 来源**：bug；A5与重复应用
- **依赖**：p37-01, p36-07
- **文件范围**：
  - 修改 `src/main/java/com/writing/novel/spi/NovelRepository.java`
  - 修改 `src/main/java/com/writing/novel/persistence/sqlite/SqliteNovelRepository.java`
  - 新增 `src/test/java/com/writing/novel/persistence/ProposalRepositoryTest.java`

**输入/输出契约**：新增 saveProposal/findProposal/listProposals/cancelProposal；新增 commitProposalChapter(proposalId,ChapterVersion,Chapter,expectedIdentity) 返回持久result；事务包含proposal消费、saveChapterRevision语义及审批失效。

- [ ] **1. 复现/契约测试**：重建repository/连接后候选仍可读；断在正文写入与候选消费之间重试不重复；跨项目/双apply/过期候选均核实最终正文版本。
- [ ] **2. 运行 RED**：执行下列 verify_cmd，保存失败断言与起始 commit。已有业务缺陷必须在未改实现前看到对应行为失败；新接口可先提交最小编译契约再验证其行为失败，不将编译错误计 RED。

```bash
mvn test -Dtest=ProposalRepositoryTest
```

- [ ] **3. 最小实现**：候选仅可在所属项目读取；READY取消用CAS；应用前先比source，正文+版本快照+候选结果一次提交；重复apply返回已持久result，不能另增版本。SQLite故障回滚后候选仍READY；冲突标STALE可在独立短事务记录，绝不改正文。
- [ ] **4. GREEN 与回归**：再次执行同一 verify_cmd，逐项核对上面的失败场景变为预期结果；运行全量编译/测试。本项不得顺带改变 prompt 或质量基线。
- [ ] **5. 独立提交和合并**：按 AGENTS.md 明确暂存文件、检查 staged diff、提交信息含 `p37-02`，检查 `main..HEAD` 仅含本项；本地合并后重跑验证，再登记 verified 完成证据。未得到实现授权时停在计划，不能替外部工具预记完成。

**机器验收结果**：verify_cmd 退出0，并满足：重建repository/连接后候选仍可读；断在正文写入与候选消费之间重试不重复；跨项目/双apply/过期候选均核实最终正文版本。

**执行检查表输入**（这是计划契约，尚不是实现或测试证据）：

```json
{
  "feature": "p37-02",
  "expected_exit": 0,
  "assertions": "重建repository/连接后候选仍可读；断在正文写入与候选消费之间重试不重复；跨项目/双apply/过期候选均核实最终正文版本。",
  "failure_is_not_success": true
}
```

<a id="p37-03"></a>
### p37-03 — 修订服务使用持久候选和恢复查询

- **类型 / 来源**：bug；A5
- **依赖**：p37-02, p37-14
- **文件范围**：
  - 修改 `src/main/java/com/writing/novel/service/AgentRevisionService.java`
  - 修改 `src/main/java/com/writing/novel/web/RevisionController.java`
  - 新增 `src/test/java/com/writing/novel/web/PersistentRevisionApiTest.java`

**输入/输出契约**：新增 GET /revisions?targetId=&status=READY、GET /revisions/{id}、POST /revisions/{id}/cancel；preview支持requestKey，返回持久proposal及source身份。API既有sourceVersion保留字符串hash兼容别名，新增sourceIdentity；域模型baseVersion是数值，禁止混用。

- [ ] **1. 复现/契约测试**：预览→重启服务→GET→应用同候选成功；模型延迟时编辑来源，返回STALE；取消后应用409；响应丢失重试章节apply仅一版。
- [ ] **2. 运行 RED**：执行下列 verify_cmd，保存失败断言与起始 commit。已有业务缺陷必须在未改实现前看到对应行为失败；新接口可先提交最小编译契约再验证其行为失败，不将编译错误计 RED。

```bash
mvn test -Dtest=PersistentRevisionApiTest
```

- [ ] **3. 最小实现**：替换ConcurrentHashMap权威存储；生成完再读来源身份，若模型返回期间内容变动，则存STALE而非可应用READY。重启查询恢复；apply CHAPTER/PASSAGE使用p37-02事务，PASSAGE重组全文后仍以整个章identity比对。WORLD/CHARACTER/VOLUME暂保持现有语义但禁用自动断线apply重试，等待p39-04统一提交。
- [ ] **4. GREEN 与回归**：再次执行同一 verify_cmd，逐项核对上面的失败场景变为预期结果；运行全量编译/测试。本项不得顺带改变 prompt 或质量基线。
- [ ] **5. 独立提交和合并**：按 AGENTS.md 明确暂存文件、检查 staged diff、提交信息含 `p37-03`，检查 `main..HEAD` 仅含本项；本地合并后重跑验证，再登记 verified 完成证据。未得到实现授权时停在计划，不能替外部工具预记完成。

**机器验收结果**：verify_cmd 退出0，并满足：预览→重启服务→GET→应用同候选成功；模型延迟时编辑来源，返回STALE；取消后应用409；响应丢失重试章节apply仅一版。

**执行检查表输入**（这是计划契约，尚不是实现或测试证据）：

```json
{
  "feature": "p37-03",
  "expected_exit": 0,
  "assertions": "预览→重启服务→GET→应用同候选成功；模型延迟时编辑来源，返回STALE；取消后应用409；响应丢失重试章节apply仅一版。",
  "failure_is_not_success": true
}
```

<a id="p37-04"></a>
### p37-04 — 候选恢复入口与未提交意见草稿

- **类型 / 来源**：bug；A5
- **依赖**：p36-01, p37-03, p36-09
- **文件范围**：
  - 修改 `frontend/src/api/client.ts`
  - 修改 `frontend/src/components/AgentRevisionDialog.tsx`
  - 新增 `qa/collaboration/candidate-recovery.spec.ts`

**输入/输出契约**：修订弹窗进入目标时查询未处理候选并显示恢复列表；instructions本地按项目+target身份暂存；正文候选内容从后端读取。

- [ ] **1. 复现/契约测试**：三条意见生成→刷新→恢复内容与意见；服务重启仍恢复；多个目标不串候选；已应用再次打开不会重新应用；版本冲突保留原意见。
- [ ] **2. 运行 RED**：执行下列 verify_cmd，保存失败断言与起始 commit。已有业务缺陷必须在未改实现前看到对应行为失败；新接口可先提交最小编译契约再验证其行为失败，不将编译错误计 RED。

```bash
npm --prefix qa/collaboration test -- candidate-recovery.spec.ts
```

- [ ] **3. 最小实现**：打开对话框不再无条件清空；选择恢复一个候选时显示来源版本与有效性，过期只读且提供重新生成；刷新/切页签后重新进入同目标可恢复。取消动作需明确区分关闭窗口和丢弃候选；应用网络不确定先GET状态，禁止重复生成新key重试。
- [ ] **4. GREEN 与回归**：再次执行同一 verify_cmd，逐项核对上面的失败场景变为预期结果；运行全量编译/测试与前端构建。本项不得顺带改变 prompt 或质量基线。
- [ ] **5. 独立提交和合并**：按 AGENTS.md 明确暂存文件、检查 staged diff、提交信息含 `p37-04`，检查 `main..HEAD` 仅含本项；本地合并后重跑验证，再登记 verified 完成证据。未得到实现授权时停在计划，不能替外部工具预记完成。

**机器验收结果**：verify_cmd 退出0，并满足：三条意见生成→刷新→恢复内容与意见；服务重启仍恢复；多个目标不串候选；已应用再次打开不会重新应用；版本冲突保留原意见。

**关键断言参考**（准备数据必须走本项说明的 fixture/正式 API，变量在测试 setUp 中创建）：

```typescript
await page.getByRole('textbox', {name:'修改要求'}).fill('一、提前铺垫。\n二、保留动机。\n三、减少谜语。');
await page.getByRole('button', {name:'生成预览', exact:true}).click();
await expect(page.getByRole('button', {name:'确认应用', exact:true})).toBeVisible();
await page.reload();
await page.getByRole('button', {name:'让 Agent 修改第1章', exact:true}).click();
await page.getByRole('button', {name:'恢复此候选', exact:true}).click();
await expect(page.getByRole('textbox', {name:'修改要求'})).toHaveValue(/提前铺垫/);
```

<a id="p37-05"></a>
### p37-05 — 作者可读的正文与字段差异对比

- **类型 / 来源**：feature；A5内部术语
- **依赖**：p37-04
- **文件范围**：
  - 新增 `frontend/src/components/RevisionDiff.tsx`
  - 修改 `frontend/src/components/AgentRevisionDialog.tsx`
  - 新增 `qa/collaboration/revision-diff.spec.ts`

**输入/输出契约**：RevisionDiff接收targetType、before、after；章/段展示标题和正文，人物/世界/卷展示改变字段的中文名与旧新值。

- [ ] **1. 复现/契约测试**：中文换行、引号、超长段落、空字段、只改标题均可读；DOM正文不含转义JSON；键盘可切换比较；390px无横向遮挡。
- [ ] **2. 运行 RED**：执行下列 verify_cmd，保存失败断言与起始 commit。已有业务缺陷必须在未改实现前看到对应行为失败；新接口可先提交最小编译契约再验证其行为失败，不将编译错误计 RED。

```bash
npm --prefix qa/collaboration test -- revision-diff.spec.ts
```

- [ ] **3. 最小实现**：正常流程不显示原始JSON、内部ID、来源hash；来源只显示第N章vV，技术详情折叠。长段落以实际文本换行比较，删除/插入有文字标识不能仅靠颜色；未改变字段默认折叠，390px上下排列。
- [ ] **4. GREEN 与回归**：再次执行同一 verify_cmd，逐项核对上面的失败场景变为预期结果；运行全量编译/测试与前端构建。本项不得顺带改变 prompt 或质量基线。
- [ ] **5. 独立提交和合并**：按 AGENTS.md 明确暂存文件、检查 staged diff、提交信息含 `p37-05`，检查 `main..HEAD` 仅含本项；本地合并后重跑验证，再登记 verified 完成证据。未得到实现授权时停在计划，不能替外部工具预记完成。

**机器验收结果**：verify_cmd 退出0，并满足：中文换行、引号、超长段落、空字段、只改标题均可读；DOM正文不含转义JSON；键盘可切换比较；390px无横向遮挡。

**执行检查表输入**（这是计划契约，尚不是实现或测试证据）：

```json
{
  "feature": "p37-05",
  "expected_exit": 0,
  "assertions": "中文换行、引号、超长段落、空字段、只改标题均可读；DOM正文不含转义JSON；键盘可切换比较；390px无横向遮挡。",
  "failure_is_not_success": true
}
```

<a id="p37-06"></a>
### p37-06 — 意见范围与逐章证据模型迁移

- **类型 / 来源**：feature；作者要求持续性
- **依赖**：p37-01
- **文件范围**：
  - 修改 `src/main/java/com/writing/novel/domain/AuthorDirective.java`
  - 修改 `src/main/java/com/writing/novel/persistence/sqlite/SqliteSchemaInitializer.java`
  - 新增 `src/test/java/com/writing/novel/persistence/DirectiveScopeMigrationTest.java`

**输入/输出契约**：AuthorDirective新增revision、fromChapter/toChapter、enabled；内嵌Evidence(id,directiveId,directiveRevision,chapterIndex,chapterVersion,contentHash,proposalId,quote,anchor,status,reason)。status=APPLIED_SYSTEM|UNMET|CONFIRMED|STALE。

- [ ] **1. 复现/契约测试**：迁移兼容旧构造/旧行；同意见多章证据不会互相覆盖；空范围、to<from、非正章号拒绝；HARD确认与enabled独立。
- [ ] **2. 运行 RED**：执行下列 verify_cmd，保存失败断言与起始 commit。已有业务缺陷必须在未改实现前看到对应行为失败；新接口可先提交最小编译契约再验证其行为失败，不将编译错误计 RED。

```bash
mvn test -Dtest=DirectiveScopeMigrationTest
```

- [ ] **3. 最小实现**：建directive_evidence独立表，一个全书HARD可有多章多稿证据；旧chapterIndex迁移为同起止，null全书从1起，enabled=true。旧systemEvidence无chapterIndex/hash标为未验证历史，不能升级当前有效证据。HARD确认某章不使后续要求失效。
- [ ] **4. GREEN 与回归**：再次执行同一 verify_cmd，逐项核对上面的失败场景变为预期结果；运行全量编译/测试。本项不得顺带改变 prompt 或质量基线。
- [ ] **5. 独立提交和合并**：按 AGENTS.md 明确暂存文件、检查 staged diff、提交信息含 `p37-06`，检查 `main..HEAD` 仅含本项；本地合并后重跑验证，再登记 verified 完成证据。未得到实现授权时停在计划，不能替外部工具预记完成。

**机器验收结果**：verify_cmd 退出0，并满足：迁移兼容旧构造/旧行；同意见多章证据不会互相覆盖；空范围、to<from、非正章号拒绝；HARD确认与enabled独立。

**执行检查表输入**（这是计划契约，尚不是实现或测试证据）：

```json
{
  "feature": "p37-06",
  "expected_exit": 0,
  "assertions": "迁移兼容旧构造/旧行；同意见多章证据不会互相覆盖；空范围、to<from、非正章号拒绝；HARD确认与enabled独立。",
  "failure_is_not_success": true
}
```

<a id="p37-07"></a>
### p37-07 — 意见与证据版本化仓储

- **类型 / 来源**：bug；意见确认不能直接置状态
- **依赖**：p37-06
- **文件范围**：
  - 修改 `src/main/java/com/writing/novel/spi/NovelRepository.java`
  - 修改 `src/main/java/com/writing/novel/persistence/sqlite/SqliteNovelRepository.java`
  - 新增 `src/test/java/com/writing/novel/persistence/DirectiveEvidenceRepositoryTest.java`

**输入/输出契约**：新增 updateDirective(expectedRevision)、findApplicableDirectives(project,index)、saveDirectiveEvidence、confirmDirectiveEvidence(id,expectedIdentity,directiveRevision)。

- [ ] **1. 复现/契约测试**：旧稿证据不能确认；更改意见revision后旧证据无效；跨章/项目伪证据拒绝；相同证据幂等；HARD某章确认后下一章仍适用。
- [ ] **2. 运行 RED**：执行下列 verify_cmd，保存失败断言与起始 commit。已有业务缺陷必须在未改实现前看到对应行为失败；新接口可先提交最小编译契约再验证其行为失败，不将编译错误计 RED。

```bash
mvn test -Dtest=DirectiveEvidenceRepositoryTest
```

- [ ] **3. 最小实现**：按enabled/范围/Kind过滤：HARD/PREFERENCE持续注入，ONCE在有效证据经作者确认后不再作为未完成修改；证据quote必须匹配指定版本正文锚点。确认在事务内重读章/意见版本，旧稿和OPEN直接确认409；撤销HARD必须显式理由，留revision审计。
- [ ] **4. GREEN 与回归**：再次执行同一 verify_cmd，逐项核对上面的失败场景变为预期结果；运行全量编译/测试。本项不得顺带改变 prompt 或质量基线。
- [ ] **5. 独立提交和合并**：按 AGENTS.md 明确暂存文件、检查 staged diff、提交信息含 `p37-07`，检查 `main..HEAD` 仅含本项；本地合并后重跑验证，再登记 verified 完成证据。未得到实现授权时停在计划，不能替外部工具预记完成。

**机器验收结果**：verify_cmd 退出0，并满足：旧稿证据不能确认；更改意见revision后旧证据无效；跨章/项目伪证据拒绝；相同证据幂等；HARD某章确认后下一章仍适用。

**执行检查表输入**（这是计划契约，尚不是实现或测试证据）：

```json
{
  "feature": "p37-07",
  "expected_exit": 0,
  "assertions": "旧稿证据不能确认；更改意见revision后旧证据无效；跨章/项目伪证据拒绝；相同证据幂等；HARD某章确认后下一章仍适用。",
  "failure_is_not_success": true
}
```

<a id="p37-08"></a>
### p37-08 — 意见接口呈现范围、证据与显式撤销

- **类型 / 来源**：bug；意见API语义
- **依赖**：p37-07
- **文件范围**：
  - 修改 `src/main/java/com/writing/novel/web/DirectiveController.java`
  - 修改 `frontend/src/api/client.ts`
  - 新增 `src/test/java/com/writing/novel/web/DirectiveVersionApiTest.java`

**输入/输出契约**：POST/PATCH directives 使用kind/fromChapter/toChapter/expectedRevision；GET 返回enabled、revision、evidence[]；POST /directives/{id}/evidence/{evidenceId}/confirm接收identity；POST /revoke必须reason。

- [ ] **1. 复现/契约测试**：原POST confirm不能凭空确认OPEN；缺version/hash不被默补；范围/硬性撤销/当前证据确认和跨项目均有HTTP断言。
- [ ] **2. 运行 RED**：执行下列 verify_cmd，保存失败断言与起始 commit。已有业务缺陷必须在未改实现前看到对应行为失败；新接口可先提交最小编译契约再验证其行为失败，不将编译错误计 RED。

```bash
mvn test -Dtest=DirectiveVersionApiTest
```

- [ ] **3. 最小实现**：关闭原无校验confirm路径；apply-evidence不接受任意文本即宣称落实，转入仓储验证。错误字段400、陈旧证据409、HARD撤销需要明确author reason；列表失败向UI暴露错误而非空数组。
- [ ] **4. GREEN 与回归**：再次执行同一 verify_cmd，逐项核对上面的失败场景变为预期结果；运行全量编译/测试与前端构建。本项不得顺带改变 prompt 或质量基线。
- [ ] **5. 独立提交和合并**：按 AGENTS.md 明确暂存文件、检查 staged diff、提交信息含 `p37-08`，检查 `main..HEAD` 仅含本项；本地合并后重跑验证，再登记 verified 完成证据。未得到实现授权时停在计划，不能替外部工具预记完成。

**机器验收结果**：verify_cmd 退出0，并满足：原POST confirm不能凭空确认OPEN；缺version/hash不被默补；范围/硬性撤销/当前证据确认和跨项目均有HTTP断言。

**执行检查表输入**（这是计划契约，尚不是实现或测试证据）：

```json
{
  "feature": "p37-08",
  "expected_exit": 0,
  "assertions": "原POST confirm不能凭空确认OPEN；缺version/hash不被默补；范围/硬性撤销/当前证据确认和跨项目均有HTTP断言。",
  "failure_is_not_success": true
}
```

<a id="p37-09"></a>
### p37-09 — 写作上下文容纳不可静默裁剪的作者要求

- **类型 / 来源**：feature；意见注入前置
- **依赖**：无；可从当前 main 启动
- **文件范围**：
  - 修改 `src/main/java/com/writing/novel/memory/WritingContext.java`
  - 新增 `src/test/java/com/writing/novel/memory/DirectiveContextSectionTest.java`

**输入/输出契约**：WritingContext新增authorDirectiveSection getter/setter；toSystemPrompt在原旨后输出【作者要求】，空值保持原prompt字节不变。

- [ ] **1. 复现/契约测试**：无要求时与原prompt逐字相同；有要求稳定排序且正文只出现一次；原旨不丢失。
- [ ] **2. 运行 RED**：执行下列 verify_cmd，保存失败断言与起始 commit。已有业务缺陷必须在未改实现前看到对应行为失败；新接口可先提交最小编译契约再验证其行为失败，不将编译错误计 RED。

```bash
mvn test -Dtest=DirectiveContextSectionTest
```

- [ ] **3. 最小实现**：节中每项包含directiveId/revision/kind/from-to/content；本特性只定义容器，不读取DB、不改变无意见的输出。硬性要求预算标记交由下项组装器处理。
- [ ] **4. GREEN 与回归**：再次执行同一 verify_cmd，逐项核对上面的失败场景变为预期结果；运行全量编译/测试。本项不得顺带改变 prompt 或质量基线。
- [ ] **5. 独立提交和合并**：按 AGENTS.md 明确暂存文件、检查 staged diff、提交信息含 `p37-09`，检查 `main..HEAD` 仅含本项；本地合并后重跑验证，再登记 verified 完成证据。未得到实现授权时停在计划，不能替外部工具预记完成。

**机器验收结果**：verify_cmd 退出0，并满足：无要求时与原prompt逐字相同；有要求稳定排序且正文只出现一次；原旨不丢失。

**执行检查表输入**（这是计划契约，尚不是实现或测试证据）：

```json
{
  "feature": "p37-09",
  "expected_exit": 0,
  "assertions": "无要求时与原prompt逐字相同；有要求稳定排序且正文只出现一次；原旨不丢失。",
  "failure_is_not_success": true
}
```

<a id="p37-10"></a>
### p37-10 — 生成上下文实际消费有效作者要求

- **类型 / 来源**：bug；意见账本未接生成
- **依赖**：p37-07, p37-09
- **文件范围**：
  - 新增 `src/main/java/com/writing/novel/service/DirectiveContextService.java`
  - 修改 `src/main/java/com/writing/novel/memory/ContextAssembler.java`
  - 新增 `src/test/java/com/writing/novel/memory/DirectiveContextInjectionTest.java`

**输入/输出契约**：DirectiveContextService.select(projectId,index)使用findApplicableDirectives；render稳定按kind优先级+createdAt+id；ContextAssembler为authorDirectiveSection计入TokenBudget。

- [ ] **1. 复现/契约测试**：捕获实际LlmRequest：本章/全书/未来范围/撤销分别出现或不出现；HARD超预算请求不发送；无意见旧回放不变。
- [ ] **2. 运行 RED**：执行下列 verify_cmd，保存失败断言与起始 commit。已有业务缺陷必须在未改实现前看到对应行为失败；新接口可先提交最小编译契约再验证其行为失败，不将编译错误计 RED。

```bash
mvn test -Dtest=DirectiveContextInjectionTest
```

- [ ] **3. 最小实现**：在生成章前注入，不能通过后端登记但完全不读取；硬性要求及原旨若超窗口明确阻塞并报告需缩减，禁止截掉HARD。软意见可截断但返回遗漏ID供UI解释。避免将HARD改为CONFIRMED后排除；不做Agent直接调用。
- [ ] **4. GREEN 与回归**：再次执行同一 verify_cmd，逐项核对上面的失败场景变为预期结果；运行全量编译/测试。本项影响生成或检查流程，还须执行入口中的质量门禁 QG。
- [ ] **5. 独立提交和合并**：按 AGENTS.md 明确暂存文件、检查 staged diff、提交信息含 `p37-10`，检查 `main..HEAD` 仅含本项；本地合并后重跑验证，再登记 verified 完成证据。未得到实现授权时停在计划，不能替外部工具预记完成。

**机器验收结果**：verify_cmd 退出0，并满足：捕获实际LlmRequest：本章/全书/未来范围/撤销分别出现或不出现；HARD超预算请求不发送；无意见旧回放不变。

**执行检查表输入**（这是计划契约，尚不是实现或测试证据）：

```json
{
  "feature": "p37-10",
  "expected_exit": 0,
  "assertions": "捕获实际LlmRequest：本章/全书/未来范围/撤销分别出现或不出现；HARD超预算请求不发送；无意见旧回放不变。",
  "failure_is_not_success": true
}
```

<a id="p37-11"></a>
### p37-11 — 候选模型返回逐项落实与未落实原因

- **类型 / 来源**：feature；A5逐项落实
- **依赖**：p37-06
- **文件范围**：
  - 修改 `src/main/java/com/writing/novel/agent/ContentRevisionAgent.java`
  - 修改 `src/main/resources/prompts/content-revision.st`
  - 新增 `src/test/java/com/writing/novel/agent/RevisionEvidenceContractTest.java`

**输入/输出契约**：RevisionCandidate新增directiveResults[]，每项directiveId、directiveRevision、status=APPLIED_SYSTEM|UNMET、quote、anchor、reason；旧五字段保留；模板变量targetType/currentJson/instructions不改名。

- [ ] **1. 复现/契约测试**：三意见只实现两项必须第三项UNMET+原因；重复/未知ID/无证据不能APPLIED_SYSTEM；现有ContentRevisionAgent对应测试仍通过。
- [ ] **2. 运行 RED**：执行下列 verify_cmd，保存失败断言与起始 commit。已有业务缺陷必须在未改实现前看到对应行为失败；新接口可先提交最小编译契约再验证其行为失败，不将编译错误计 RED。

```bash
mvn test -Dtest=RevisionEvidenceContractTest
```

- [ ] **3. 最小实现**：instructions中传结构化编号要求；有N项输入则结果必须恰好覆盖N个已知ID，未知/重复/遗漏ID或空UNMET原因拒绝；quote不等于文学质量已保证。无要求结果可空，兼容旧调用。修改prompt需记录新fixture并人审基线。
- [ ] **4. GREEN 与回归**：再次执行同一 verify_cmd，逐项核对上面的失败场景变为预期结果；运行全量编译/测试。本项影响生成或检查流程，还须执行入口中的质量门禁 QG。
- [ ] **5. 独立提交和合并**：按 AGENTS.md 明确暂存文件、检查 staged diff、提交信息含 `p37-11`，检查 `main..HEAD` 仅含本项；本地合并后重跑验证，再登记 verified 完成证据。未得到实现授权时停在计划，不能替外部工具预记完成。

**机器验收结果**：verify_cmd 退出0，并满足：三意见只实现两项必须第三项UNMET+原因；重复/未知ID/无证据不能APPLIED_SYSTEM；现有ContentRevisionAgent对应测试仍通过。

**执行检查表输入**（这是计划契约，尚不是实现或测试证据）：

```json
{
  "feature": "p37-11",
  "expected_exit": 0,
  "assertions": "三意见只实现两项必须第三项UNMET+原因；重复/未知ID/无证据不能APPLIED_SYSTEM；现有ContentRevisionAgent对应测试仍通过。",
  "failure_is_not_success": true
}
```

<a id="p37-12"></a>
### p37-12 — 候选生成与应用落实证据联动

- **类型 / 来源**：feature；意见生成-应用-确认
- **依赖**：p37-03, p37-07, p37-10, p37-11
- **文件范围**：
  - 修改 `src/main/java/com/writing/novel/service/AgentRevisionService.java`
  - 修改 `src/main/java/com/writing/novel/persistence/sqlite/SqliteNovelRepository.java`
  - 新增 `src/test/java/com/writing/novel/service/DirectiveProposalIntegrationTest.java`

**输入/输出契约**：preview选取适用directive并固定revision；directiveResults嵌入proposal持久JSON；commitProposalChapter将验证通过的证据与新章、候选消费同事务保存。

- [ ] **1. 复现/契约测试**：三意见→预览DB无CONFIRMED/APPLIED章节证据→应用只产生实际匹配的版本证据→作者确认；编辑稿或意见变动旧证据变STALE；回滚无半套证据。
- [ ] **2. 运行 RED**：执行下列 verify_cmd，保存失败断言与起始 commit。已有业务缺陷必须在未改实现前看到对应行为失败；新接口可先提交最小编译契约再验证其行为失败，不将编译错误计 RED。

```bash
mvn test -Dtest=DirectiveProposalIntegrationTest
```

- [ ] **3. 最小实现**：校验quote确实存在于候选对应段、来源身份正确；预览阶段只记录候选的自述，不把意见状态改为已落实。正式应用后证据绑定result章节版本/hash；未落实HARD允许保留候选但不允许批准该章，p38裁决中可显式豁免，不能自动confirmed。
- [ ] **4. GREEN 与回归**：再次执行同一 verify_cmd，逐项核对上面的失败场景变为预期结果；运行全量编译/测试。本项影响生成或检查流程，还须执行入口中的质量门禁 QG。
- [ ] **5. 独立提交和合并**：按 AGENTS.md 明确暂存文件、检查 staged diff、提交信息含 `p37-12`，检查 `main..HEAD` 仅含本项；本地合并后重跑验证，再登记 verified 完成证据。未得到实现授权时停在计划，不能替外部工具预记完成。

**机器验收结果**：verify_cmd 退出0，并满足：三意见→预览DB无CONFIRMED/APPLIED章节证据→应用只产生实际匹配的版本证据→作者确认；编辑稿或意见变动旧证据变STALE；回滚无半套证据。

**执行检查表输入**（这是计划契约，尚不是实现或测试证据）：

```json
{
  "feature": "p37-12",
  "expected_exit": 0,
  "assertions": "三意见→预览DB无CONFIRMED/APPLIED章节证据→应用只产生实际匹配的版本证据→作者确认；编辑稿或意见变动旧证据变STALE；回滚无半套证据。",
  "failure_is_not_success": true
}
```

<a id="p37-13"></a>
### p37-13 — 意见范围编辑与证据逐项作者确认

- **类型 / 来源**：feature；A5闭环UI
- **依赖**：p37-08, p37-12, p36-05, p37-16
- **文件范围**：
  - 新增 `frontend/src/components/DirectivePanel.tsx`
  - 修改 `frontend/src/components/InteractiveReview.tsx`
  - 新增 `qa/collaboration/directive-loop.spec.ts`

**输入/输出契约**：抽出DirectivePanel(projectId,currentIdentity,onChanged)，登记多条意见分别持久化；显示范围、启用状态、证据版本和逐条确认。

- [ ] **1. 复现/契约测试**：登记三意见→生成→应用→两个有效证据确认+一个UNMET保留；刷新不丢；旧稿确认禁用；HARD撤销留历史；390px同样可操作。
- [ ] **2. 运行 RED**：执行下列 verify_cmd，保存失败断言与起始 commit。已有业务缺陷必须在未改实现前看到对应行为失败；新接口可先提交最小编译契约再验证其行为失败，不将编译错误计 RED。

```bash
npm --prefix qa/collaboration test -- directive-loop.spec.ts
```

- [ ] **3. 最小实现**：本章/区间/全书选择及生效章号；未落实原因与证据原文可跳到稿中；只有当前稿有效APPLIED_SYSTEM可确认；HARD撤销显式输入理由；网络失败保留输入并给重试，不静默当作暂无意见。新组件直接消费p37-08 client。
- [ ] **4. GREEN 与回归**：再次执行同一 verify_cmd，逐项核对上面的失败场景变为预期结果；运行全量编译/测试与前端构建。本项不得顺带改变 prompt 或质量基线。
- [ ] **5. 独立提交和合并**：按 AGENTS.md 明确暂存文件、检查 staged diff、提交信息含 `p37-13`，检查 `main..HEAD` 仅含本项；本地合并后重跑验证，再登记 verified 完成证据。未得到实现授权时停在计划，不能替外部工具预记完成。

**机器验收结果**：verify_cmd 退出0，并满足：登记三意见→生成→应用→两个有效证据确认+一个UNMET保留；刷新不丢；旧稿确认禁用；HARD撤销留历史；390px同样可操作。

**执行检查表输入**（这是计划契约，尚不是实现或测试证据）：

```json
{
  "feature": "p37-13",
  "expected_exit": 0,
  "assertions": "登记三意见→生成→应用→两个有效证据确认+一个UNMET保留；刷新不丢；旧稿确认禁用；HARD撤销留历史；390px同样可操作。",
  "failure_is_not_success": true
}
```

<a id="p37-14"></a>
### p37-14 — 统一修订边界接入候选原子提交

- **类型 / 来源**：feature；候选不绕过统一修订边界
- **依赖**：p37-02
- **文件范围**：
  - 修改 `src/main/java/com/writing/novel/service/ChapterRevisionService.java`
  - 修改 `src/main/java/com/writing/novel/service/AgentRevisionService.java`
  - 新增 `src/test/java/com/writing/novel/service/ProposalRevisionBoundaryTest.java`

**输入/输出契约**：ChapterRevisionService新增applyProposalInOwnedWork(projectId,proposalId)；校验/全文重组/快照创建沿用doEdit，提交切换commitProposalChapter。

- [ ] **1. 复现/契约测试**：候选与手编同等500字门槛、版本快照、批准失效；派生失败网络重试不增版；段落候选任何全文变动都冲突。
- [ ] **2. 运行 RED**：执行下列 verify_cmd，保存失败断言与起始 commit。已有业务缺陷必须在未改实现前看到对应行为失败；新接口可先提交最小编译契约再验证其行为失败，不将编译错误计 RED。

```bash
mvn test -Dtest=ProposalRevisionBoundaryTest
```

- [ ] **3. 最小实现**：AgentRevisionService对CHAPTER/PASSAGE调用统一边界，不直接写正文；应用前项目工作权、当前source身份和候选状态均核验；成功返回仓储持久result。派生刷新在提交后执行，失败标pending不得再次写一版正文。
- [ ] **4. GREEN 与回归**：再次执行同一 verify_cmd，逐项核对上面的失败场景变为预期结果；运行全量编译/测试。本项不得顺带改变 prompt 或质量基线。
- [ ] **5. 独立提交和合并**：按 AGENTS.md 明确暂存文件、检查 staged diff、提交信息含 `p37-14`，检查 `main..HEAD` 仅含本项；本地合并后重跑验证，再登记 verified 完成证据。未得到实现授权时停在计划，不能替外部工具预记完成。

**机器验收结果**：verify_cmd 退出0，并满足：候选与手编同等500字门槛、版本快照、批准失效；派生失败网络重试不增版；段落候选任何全文变动都冲突。

**执行检查表输入**（这是计划契约，尚不是实现或测试证据）：

```json
{
  "feature": "p37-14",
  "expected_exit": 0,
  "assertions": "候选与手编同等500字门槛、版本快照、批准失效；派生失败网络重试不增版；段落候选任何全文变动都冲突。",
  "failure_is_not_success": true
}
```

<a id="p37-15"></a>
### p37-15 — 生成后作者要求核对器契约

- **类型 / 来源**：feature；持续要求逐章落实
- **依赖**：p37-06
- **文件范围**：
  - 新增 `src/main/java/com/writing/novel/agent/DirectiveEvidenceAgent.java`
  - 新增 `src/main/resources/prompts/directive-evidence.st`
  - 新增 `src/test/java/com/writing/novel/agent/DirectiveEvidenceAgentTest.java`

**输入/输出契约**：DirectiveEvidenceAgent.evaluate(chapterJson,directivesJson)返回 List<AuthorDirective.Evidence> 的候选值；经GENERAL任务类型、promptService.render("directive-evidence",vars)。

- [ ] **1. 复现/契约测试**：固定Stub响应覆盖全三项/遗漏/伪造引文/空返回；核对器失败让要求保持未验证而非已落实；禁止Agent直接调用其他Agent。
- [ ] **2. 运行 RED**：执行下列 verify_cmd，保存失败断言与起始 commit。已有业务缺陷必须在未改实现前看到对应行为失败；新接口可先提交最小编译契约再验证其行为失败，不将编译错误计 RED。

```bash
mvn test -Dtest=DirectiveEvidenceAgentTest
```

- [ ] **3. 最小实现**：模板固定变量chapterJson/directivesJson；要求每个directiveId输出实现/未实现与原文证据；只读不修改正文不确认意见；未知/重复ID或不存在quote不当成成功，输出UNMET并说明证据不足。
- [ ] **4. GREEN 与回归**：再次执行同一 verify_cmd，逐项核对上面的失败场景变为预期结果；运行全量编译/测试。本项影响生成或检查流程，还须执行入口中的质量门禁 QG。
- [ ] **5. 独立提交和合并**：按 AGENTS.md 明确暂存文件、检查 staged diff、提交信息含 `p37-15`，检查 `main..HEAD` 仅含本项；本地合并后重跑验证，再登记 verified 完成证据。未得到实现授权时停在计划，不能替外部工具预记完成。

**机器验收结果**：verify_cmd 退出0，并满足：固定Stub响应覆盖全三项/遗漏/伪造引文/空返回；核对器失败让要求保持未验证而非已落实；禁止Agent直接调用其他Agent。

**执行检查表输入**（这是计划契约，尚不是实现或测试证据）：

```json
{
  "feature": "p37-15",
  "expected_exit": 0,
  "assertions": "固定Stub响应覆盖全三项/遗漏/伪造引文/空返回；核对器失败让要求保持未验证而非已落实；禁止Agent直接调用其他Agent。",
  "failure_is_not_success": true
}
```

<a id="p37-16"></a>
### p37-16 — 章节生成完成后落库意见落实证据

- **类型 / 来源**：feature；持续要求运行闭环
- **依赖**：p37-10, p37-15, p36-07
- **文件范围**：
  - 修改 `src/main/java/com/writing/novel/orchestrator/NovelOrchestrator.java`
  - 新增 `src/main/java/com/writing/novel/service/DirectiveEvidenceService.java`
  - 新增 `src/test/java/com/writing/novel/orchestrator/GeneratedDirectiveEvidenceTest.java`

**输入/输出契约**：NovelOrchestrator在当前稿产出并完成检查后调用DirectiveEvidenceAgent；DirectiveEvidenceService只做quote/identity/revision验证及仓储证据写入。

- [ ] **1. 复现/契约测试**：连写两章HARD仍注入且各自证据独立；慢核对期间作者修改，旧结果不能落实新稿；失败可重试而不重复证据；捕获额外调用成本。
- [ ] **2. 运行 RED**：执行下列 verify_cmd，保存失败断言与起始 commit。已有业务缺陷必须在未改实现前看到对应行为失败；新接口可先提交最小编译契约再验证其行为失败，不将编译错误计 RED。

```bash
mvn test -Dtest=GeneratedDirectiveEvidenceTest
```

- [ ] **3. 最小实现**：先固定chapter identity及directive revision，异步结果回来时重读，过期只存历史或丢弃并标STALE，不能更新新稿确认状态；收割/风格改写导致版本提升后重新核对最终稿。无适用要求不调用额外模型。
- [ ] **4. GREEN 与回归**：再次执行同一 verify_cmd，逐项核对上面的失败场景变为预期结果；运行全量编译/测试。本项影响生成或检查流程，还须执行入口中的质量门禁 QG。
- [ ] **5. 独立提交和合并**：按 AGENTS.md 明确暂存文件、检查 staged diff、提交信息含 `p37-16`，检查 `main..HEAD` 仅含本项；本地合并后重跑验证，再登记 verified 完成证据。未得到实现授权时停在计划，不能替外部工具预记完成。

**机器验收结果**：verify_cmd 退出0，并满足：连写两章HARD仍注入且各自证据独立；慢核对期间作者修改，旧结果不能落实新稿；失败可重试而不重复证据；捕获额外调用成本。

**执行检查表输入**（这是计划契约，尚不是实现或测试证据）：

```json
{
  "feature": "p37-16",
  "expected_exit": 0,
  "assertions": "连写两章HARD仍注入且各自证据独立；慢核对期间作者修改，旧结果不能落实新稿；失败可重试而不重复证据；捕获额外调用成本。",
  "failure_is_not_success": true
}
```
