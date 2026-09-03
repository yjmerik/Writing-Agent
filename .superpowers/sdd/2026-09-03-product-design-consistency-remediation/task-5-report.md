# p26-05：批准后章节正文加载收敛

## Result

- 实现提交：`b04a03e7e0634ab711c51f9f668a0f9463b11129` — `fix(chapter): retain body after review refresh`
- 范围：`InteractiveReview` 的正文缓存、审批刷新和正文读取失败反馈；审计脚本仅移除本项已知失败。

## TDD Evidence

- RED：`./scripts/product-audit-e2e.sh --case approve-keeps-chapter-visible` 返回非零，结果为 **0 PASS / 1 XFAIL / 0 ERROR**；`visibleBefore=true`、`visibleAfter=false`、`bodyLoading=true`。
- GREEN：同一严格场景为 **1 PASS / 0 XFAIL / 0 ERROR**。从 `KNOWN_FAILURES` 移除 `approve-keeps-chapter-visible` 后再次运行，结果仍为 **1 PASS / 0 XFAIL / 0 ERROR**。

## Gates

- `frontend npm run build`：PASS。
- JDK21 `mvn compile -q`：PASS。
- JDK21 `mvn test`：**504 tests / 0 failures / 0 errors / 1 skipped**。
- JDK21 `./init.sh`：PASS；包含 27 个 prompt 完整性和前端生产构建，测试计数同上。

## Self-review

- 元数据刷新按 `(chapterIndex, version)` 保留仍有效正文；过期版本被淘汰。
- 当前章版本变化会显式回读；批准后强制重读当前选中章，同时旧正文持续展示直到新响应到达。
- 独立章节重写继续同时更新正文缓存与章节元数据。
- 请求失败显示站内错误和重试按钮，正文 spinner 不会永久停留。

## Concerns

- 无阻塞问题。前端生产构建仍报告既有的大 bundle 警告（约 1.19 MB），不属于本 feature 范围。

## Review Round 1 (base `9440381`)

- 修复：正文请求现在以 `projectId + project generation + chapter generation` 标识；只有当前项目且该章节最新的一次请求可以更新正文缓存、该章的 loading 或该章的 error。项目变更立即失效所有正文请求/缓存状态，章节管理组件也以 `projectId` 参与 remount key。
- 状态：正文 loading/error 改为按 `chapterIndex` 记录，章节 A 的请求不会清除章节 B 的状态；渲染只采用版本等于当前 metadata 的正文。强制重读失败时，旧正文保持可见，同时在正文下方显示错误与重试。
- Focused browser proof：审批场景扩为两章，先切到第 2 章、确认其正文可见、批准第 2 章，再断言第 2 章正文仍可见且无正文 spinner；strict **1 PASS / 0 XFAIL / 0 ERROR**。`standalone-chapter-rewrite` 回归同为 **1 PASS / 0 XFAIL / 0 ERROR**。
- 如实记录：现有真实浏览器链路无法确定性延迟或乱序 `getChapter` 响应，因此本 round **没有 RED**；乱序安全性由请求代际不变量和代码审查保证，未声称浏览器覆盖了合成 race。
- Gates：frontend `npm run build` PASS；JDK21 `mvn test` 与 `./init.sh` PASS（**504 tests / 0 failures / 0 errors / 1 skipped**，init 另确认 27 个 prompt 与前端生产构建）。
