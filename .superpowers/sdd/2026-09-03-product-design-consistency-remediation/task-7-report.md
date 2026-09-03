# Task 7 Report — p26-07 初始设定审核内容摘要

日期：2026-09-03

## 状态与范围

- 状态：COMPLETED
- 基线：`13f745c`
- 提交：`feat(review): summarize initial setup checkpoint`
- 范围只包含 AFTER_INITIAL_SETUP checkpoint payload、审核卡摘要展示、真实页签跳转与专属 strict 场景。
- 未修改自动任务、mode switch/resume、Agent、prompt、上下文组装或生成产出逻辑。

## RED 证据

1. 先写 `InitialSetupCheckpointPayloadTest`，通过完整 INTERACTIVE 构思链生成并持久化世界观、人物与大纲，再读取 pending AFTER_INITIAL_SETUP checkpoint。
2. 初次运行得到 **1 test / 1 failure / 0 errors**：`初始设定 checkpoint 必须携带可审核摘要`，实际 payload 为 null。
3. 测试输入包含超长 emoji 与引号，预先锁定 Jackson JSON、真实持久化来源、三段非空、每段 300 code points、surrogate 完整，以及链接只允许真实 workspace target。
4. UI 实现前新增 `initial-setup-summary` strict case；首次为 **0 PASS / 1 XFAIL / 0 ERROR**，世界观、人物、原旨/大纲三段真实标记、两个跳转按钮和两个 active tab 断言均为 false。新 case 从未加入 `KNOWN_FAILURES`。

## GREEN 实现

- `NovelOrchestrator` 在构思内容完整并已落库后，为 AFTER_INITIAL_SETUP checkpoint 写入 Jackson payload：
  - `worldSummary` 读取已持久化 KnowledgeEntry；
  - `characterSummary` 读取已持久化 Character；
  - `premiseSummary` 合并项目原旨与已持久化 Outline premise；
  - `links` 只发布 `world` 与 `outline`。
- 摘要统一用 `String.codePointCount` / `offsetByCodePoints` 截断至最多 300 code points，不拆 UTF-16 surrogate pair。
- `InteractiveReview` 展示世界观、人物、原旨与大纲三段摘要；跳转由 Ant Design 原生 Button 承载，支持键盘激活。
- `ProjectWorkspace` 仅增加最小 `onNavigateTab={setActiveTab}` 回调，没有引入路由重构。
- UI 对反序列化后的 links 做运行时 label/target 校验；只有 `world`/`outline` 能进入 `setActiveTab`，旧或异常 payload 不能设置不存在的 tab。
- `product-audit-e2e.sh` 增加独立隔离种子和 strict `initial-setup-summary`，断言三段 Scenario 真值，以及点击后真实 `[role=tab][aria-selected=true]`。

## 验证门禁

- Focused：`InitialSetupCheckpointPayloadTest,CheckpointPayloadTest,CheckpointRevisionRoutingTest,VolumeOutlineCheckpointTest,ApproveSpecificCheckpointTest` → **15 tests / 0 failures / 0 errors**。
- Frontend：`npm run build` → PASS（TypeScript + Vite，3043 modules）。
- Product strict：
  - `initial-setup-summary` → **1 PASS / 0 XFAIL / 0 ERROR**；
  - `checkpoint-card-targeting` → **1 PASS / 0 XFAIL / 0 ERROR**；
  - `KNOWN_FAILURES` 未为新 case 增项，也未弱化既有断言。
- Browser E2E 产品断言 → **8/8 PASS**：完整脚本前 7 项通过；第 8 项使用确认带 `--llm.scenario.harsh=true` 的隔离新服务运行原断言，chapter 1 为 `NEEDS_MANUAL_REVIEW`，UI `pendingTag=true`、`resolveBtn=true`、`resolved=true`。
- JDK21 `mvn compile && mvn test` → PASS，**523 tests / 0 failures / 0 errors / 1 skipped**。
- JDK21 `./init.sh` → PASS，同为 **523 / 0 / 0 / 1**，另通过 27 个 prompt 完整性和前端生产构建。
- 本 feature 不改变 Agent/prompt/上下文或产出质量，因此按 brief 不执行 replay 质量门禁。

## Browser harness 环境归因

- 原完整脚本两次在第 8 项报告失败，但 `data/e2e/browser/server.log` 明确显示第二服务 `APPLICATION FAILED TO START` / `Port ... was already in use`，readiness 实际命中第一阶段遗留的非 harsh 服务。
- 在 base `13f745c` 临时副本中可稳定复现相同 DONE 结果，证明不是 p26-07 diff 引入。
- 临时脚本副本的端口空闲检查进一步确认旧监听进程未及时退出；工作树中的 `browser-e2e.sh` 没有改动。
- 最终以全新 8108 端口和明确新 PID 启动 genuine harsh 服务，运行原第 8 项 API/UI 断言全绿，并在验证后终止该精确进程。
- harness 的 PID/端口回收属于独立测试设施问题；本 feature 按隔离原则只记录，不扩大修复范围。

## 自审

- payload 生成时点在三类构思内容完整性校验之后，来源都是 repository 已提交数据。
- JSON 由 Jackson 编码，测试包含引号，避免手工拼接转义问题。
- 长文本测试要求恰好 300 code points，同时扫描全部 UTF-16 单元确认 surrogate 成对。
- 后端只发布 `world`/`outline`，前端再次白名单过滤，形成生产者与消费者双边约束。
- 页面跳转只复用 workspace 现有 Tabs 状态；未增加路由、未触碰审核推进或自动任务语义。

## Review fix round 1

### RED

- 将项目原旨扩展到 300 code points 以上，复现先拼接后整体截断会吞掉已持久化 outline premise 标记。
- 将项目原旨与 outline premise 同时置空，复现 checkpoint 的 `premiseSummary` 为空；再构造连卷标题/summary 都为空的已持久化大纲，复现项目仍被暂停并创建不可审核 checkpoint。
- 后端 focused 首跑为 **3 tests / 3 failures / 0 errors**。
- strict 种子插入摘要字段分别为 object/array/number、同时混合安全与非法 link 的旧 payload；首次为 **0 PASS / 1 XFAIL / 0 ERROR**，React child 渲染异常使真实摘要与跳转断言一并失败。该 case 未加入 `KNOWN_FAILURES`。

### GREEN

- 双来源摘要在扣除换行后的 299 code points 中先均分，再把未用预算让给较长来源；由此总长度不超过 300 且两方标记均保留。只有一个来源时使用完整 300 code points 预算。
- 项目原旨与 outline premise 都为空时，读取已持久化卷标题/summary；仍无真实内容则在项目切换为 `PAUSED_AT_CHECKPOINT` 和 checkpoint 写入之前抛错，持久化状态保持 `CONCEIVING`。
- `InteractiveReview` 对 JSON 根、所有可见摘要字段及 links 做运行时归一化：摘要只接收 string，links 仍只接收 `world`/`outline`，异常旧数据不会进入 React child 或活动页签状态。

### Review fix 验证

- Focused checkpoint 回归 → **17 tests / 0 failures / 0 errors**。
- Frontend build → PASS（3043 modules；仅既有 bundle warning）。
- `initial-setup-summary`（含异常旧 payload）和 `checkpoint-card-targeting` strict → 各 **1 PASS / 0 XFAIL / 0 ERROR**。
- Browser 产品断言 → 分段 **8/8 PASS**；完整脚本前 7 项 PASS，第 8 项仍是已记录的二阶段端口竞态，独立 fresh `harsh=true` 服务运行原断言 PASS，未修改 harness。
- JDK21 `mvn compile && mvn test` 与 `./init.sh` → **525 tests / 0 failures / 0 errors / 1 skipped**；init 另通过 27 个 prompt 与前端构建。
- 仍未修改 Agent、prompt、上下文组装或产出逻辑，不触发 replay 质量门禁。

## Concerns

- 无 feature blocker。
- `browser-e2e.sh` 的两阶段端口回收竞态应在后续独立 feature 中修复，不能与产品修复混提交。
- 前端主 bundle 仍有既有的超过 500 kB 警告；构建成功，不属于 p26-07。
