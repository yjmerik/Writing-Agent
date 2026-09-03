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
