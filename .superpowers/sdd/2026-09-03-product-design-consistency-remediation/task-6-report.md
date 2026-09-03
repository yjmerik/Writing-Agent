# Task 6 Report — p26-06 状态操作矩阵与服务端门禁

日期：2026-09-03

## 状态与提交

- 状态：COMPLETED
- p26-06 实现提交：`0ede4d7 fix(workflow): expose and enforce valid actions`
- p26-06 审查修复提交：`eab3034 fix(workflow): revalidate chapter action at start`
- 交付前独立基线修复：
  - `e4b20bb fix(chapter): deduplicate initial body load`
  - `2b92249 fix(chapter): expose metadata version`
- 原始指定 base：`95be33d7a9f09e4a84ffd7d00bc25c4cd5a275da`
- p26-06 直接 base：`2b92249`

## RED 证据

1. 先写 `ProjectActionPolicyTest` 的 9 组参数化矩阵，再运行 `mvn test -Dtest=ProjectActionPolicyTest`：因 `ProjectActionPolicy` / `ProjectAction` 不存在而 testCompile 失败。
2. 在改 endpoint 实现前增加无副作用回归，聚焦运行 `OrchestratorControllerTest,ContentControllerTest` 得到 4 个预期失败：
   - status 未返回 `allowedActions`；
   - WRITING 状态 `/run` 返回 200 并隐式写章；
   - CONCEIVING 状态 `/write-all` 返回 200 并创建任务；
   - 无效 `/write-chapter` 会先创建再留下 FAILED 任务。
3. 端点 GREEN 后又加固直接 mock 证据：四个被拒绝入口既不调 `saveWritingTask`，也不调 orchestrator；status 集成测试证明它根据真实 outline/pending checkpoint 变更动作集。
4. 独立标准审查发现 `/write-chapter` 首次门禁与异步任务启动之间存在 TOCTOU。先写确定性 latch 并发测试：请求通过首次门禁后暂停项目并加入 pending，再放行任务启动；RED 为 `WritingTaskExecutor.startWriteChapter(String, Runnable)` 尚不存在导致 testCompile 失败。

## GREEN 实现

- `ProjectActionPolicy` 唯一定义主操作矩阵：
  - CONCEIVING / PLANNING / VOLUME_DONE → ADVANCE；
  - WRITING + outline + no pending → WRITE_CHAPTER + WRITE_ALL；
  - NEEDS_REVISION → FINAL_AUDIT；
  - PAUSED_AT_CHECKPOINT / FINAL → 无主操作。
- pending checkpoint 对所有写作动作关闭；无大纲不能写作。
- status 从 repository 读取 outline/pending，发布 `allowedActions` 与 `blockedReasons`。
- `/run`、`/write-all`、`/write-chapter`、`/final-audit` 均在任务持久化或 orchestrator/Agent 工作前执行 policy。`/run` 在 WRITING 明确拒绝，不再隐式写章。
- `/write-chapter` 在取得既有 `ProjectWorkCoordinator` owner 后执行 repository 真相二次门禁，门禁通过才保存 task；若暂停先发生，拒绝并释放 owner，不进入 orchestrator。由此把校验与任务启动线性化在同一协调边界内。
- owner 获取后的二次门禁是单章动作的启动线性化点；若 pause 在该点之后发生，允许当前章完成再退出，沿用既有 cancel 语义，不扩张本 feature 的暂停生命周期。
- `writingTaskExecutor == null` 仅保留给旧控制器单元测试兼容；Spring 生产注入路径始终使用 executor，并执行上述 owner 内二次门禁。
- RunConsole 取消本地终态推断主导权，获取 status 后仅呈现服务端允许的主操作；无动作时显示服务端 reason，status 读取失败时 fail closed。
- 产品 approve 种子在 WRITING 直接使用 `/write-chapter`，不再依赖 `/run` 写章，也未额外制造 pending checkpoint。

## 验证门禁

- Focused Java：`ProjectActionPolicyTest,TerminalStateCommandGateTest,OrchestratorControllerTest,ContentControllerTest,WritingEntryOwnershipTest,WriteInstructionTest` → **35 tests / 0 failures / 0 errors**；新增并发用例直接证明暂停抢先时不保存 task、不调用 orchestrator 且不残留 owner。
- Frontend：`npm run build` → PASS（TypeScript + Vite production build）。
- Product strict：
  - `approve-keeps-chapter-visible` → **1 PASS / 0 XFAIL / 0 ERROR**；
  - `standalone-chapter-rewrite` → **1 PASS / 0 XFAIL / 0 ERROR**。
- Browser E2E：`./scripts/browser-e2e.sh` → **8/8 PASS**，含 FINAL/NEEDS_REVISION 无效主操作不呈现的前端证据。
- JDK21 full：`mvn compile && mvn test` → **522 tests / 0 failures / 0 errors / 1 skipped**。
- JDK21 init：`./init.sh` → PASS，同为 **522 / 0 / 0 / 1**，另通过 27 个 prompt 完整性检查和前端生产构建。

## 自审

- 矩阵是单一服务端 policy，控制器没有复制 switch。
- 无效请求在 task 持久化及 orchestrator/Agent 调用前拒绝；owner 内二次门禁失败会释放 owner，并有直接 Mockito `never` / `verifyNoInteractions`、确定性并发测试与 HTTP/DB 回归证据。
- 独立 spec 审查无发现；独立 standards 审查的唯一 Important（单章启动 TOCTOU）已由 `eab3034` 和确定性并发回归关闭。修复后重新执行全部门禁，standards 复审确认上述线性化/cancel 边界并返回 **NO FINDINGS**，达到 fixed point。
- 前端不再从 prop 自行推导可执行动作；status 未就绪或失败时不呈现可点主操作。
- 未修改自动任务语义、暂停进度或文案门禁；`resume` 不在本 feature 指定的四个主动作 endpoint 中，保持原语义。
- 独立基线修复与 p26-06 提交分离，p26-06 提交未夹带 `InteractiveReview` 或 SQLite metadata mapper 修复。

## Concerns

- 无 feature blocker。
- 前端主 bundle 仍有既有的超过 500 kB 分包警告；构建通过，不属于 p26-06 范围。
- Mockito 在 JDK21 测试时仍输出未来动态 agent 限制警告；当前 full/init 全绿，不影响本次交付。
