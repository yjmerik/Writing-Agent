# Spec: 写作流水线韧性加固与人审协作 UX 修复

## 背景与动机

2026-10-03 以创作者身份对 18095 完成一轮 50 万字长篇验证（《山河执棋人》，历史权谋·200章，12 章 / 55,273 字，166 次 LLM 调用 ¥4.37）。验证中发现 1 个 P1 级流水线挂死与 5 个 UX/环境问题：

1. **P1 流水线挂死（两次实际发生，18:42–18:57 本地）**：LLM 上游流式响应停滞时，`OpenAiCompatibleClient.singleAttempt` 阻塞在读（callTimeout/readTimeout=30min 兜底过长），同时 `ChapterPostProcessor.awaitFanout`（ChapterPostProcessor.java:170）以无超时 `f.get()` 等待扇出子任务 → 整条写作循环冻结；期间任务保持 RUNNING 并持有项目工作权，创作者的批准/重写请求全部 409（WORKER_FINALIZING），前端仅短暂 toast 后无出口。实测约 15 分钟后网关熔断（NETWORK）才自愈。
2. **「让 Agent 修改」预览重试静默失效**：预览首次被并发守卫（WORKER_FINALIZING/SYNC_WORK_BUSY）拒绝后，前端状态不复位，后续点击「生成预览」不发任何网络请求、无任何提示。
3. **「N 个待采纳候选」无可发现入口**：主页状态条显示计数但不可点击；版本记录面板显示"暂无历史版本"；实际入口在审查面板的「候选线索」区，需多步才能发现。
4. **「创作要求」textarea 刷新即丢**：主页"给 Agent 的创作要求"输入后无保存动作，页面刷新内容丢失。
5. **审批 409 原因未常驻展示**：`review-decisions` 返回结构化 409（ApprovalBlockedException，NCS-081 引入），但前端批准路径仅做 15 秒自动重试 toast（WORKER_FINALIZING 场景），门禁拒绝原因（未解决 HIGH / 作者要求未落实）未被常驻呈现。
6. **环境隐患（已处理）**：9/19 与 9/22 遗留的两个孤儿 JVM（8080 / 18099）与验证实例并存，已于本轮启动前全部停止。

## 目标

1. 上游 LLM 停滞时，单条扇出子任务在可配置时限内被放弃并标记阶段失败，写作循环不再无限冻结；暂停（停车）期间作者批准/编辑不被 409 死锁。
2. 创作者在审阅工作区的每一步操作（批准、补查、Agent 修改预览）在遇到冲突/门禁拒绝时，能看到**常驻、可读的原因**并可重试成功。
3. 候选线索可从状态条一键到达；「创作要求」内容在页面刷新后保留。

## 非目标

- 不改变质量门禁判定逻辑、重写预算与 ledger 语义（阶段失败仍=未完成，不视为无问题）。
- 不改变「Agent 间不直接调用」「工作权单持有者」等架构约束。
- 不处理 LLM 上游服务本身的稳定性（ provider 侧熔断已存在，LLM_PROVIDER_RECOVERED 验证有效）。
- 不在本 spec 内做章节写作风格（对白乒乓/说明文密度）的 prompt 调优——另行登记为评测类 feature（NCS-170 方向）。

## 验收标准

- [ ] `mvn test -Dtest=ChapterPostProcessorFanoutTimeoutTest`：模拟永不完成的扇出子任务，`process()` 在配置时限内返回，且该阶段在 ledger 中标记为失败/未完成（不得视为无问题）。
- [ ] NCS-174（实现期修订：生产 named-provider 路径已按 `request-timeout-seconds` 生效，15 分钟冻结即 900s 预算到期，故不做全局收紧）：`qwen-flash-extract.request-timeout-seconds` 600→300；默认构造器三超时可经系统属性覆盖（默认值不变，escape hatch）；`mvn test -Dtest=OpenAiCompatibleClientTimeoutTest` 验证属性生效。
- [ ] 前端：预览请求失败（任何 409 冲突）后，再次点击「生成预览」必然重新发起网络请求（vitest 单测覆盖重试状态复位）。
- [ ] 前端：批准收到结构化 409 时，页面出现常驻错误条（含 reason），不随 toast 消失；解除后自动消失。
- [ ] 前端：状态条「N 个待采纳候选」可点击，直达当前章审查面板候选线索区。
- [ ] 前端：「创作要求」输入失焦即持久化（localStorage 草稿 + 恢复），刷新不丢。
- [ ] `mvn compile && mvn test` 全绿；`cd frontend && npm run build` 通过。

## 影响面

- **涉及模块**: orchestrator/ChapterPostProcessor、llm/client/OpenAiCompatibleClient（+LlmClientConfig 装配）、web/（如需补充 409 结构体字段）、frontend/src 审阅工作区相关组件、application.yml
- **涉及 prompt 模板**: 无
- **数据库 schema 变更**: 无
- **质量基线影响**: 无 prompt/上下文组装变化；E2E replay 语料不受影响。超时默认值变化不影响 replay（确定性网关）。

## Feature 拆分

| id | name | 依赖 | acceptance | verify_cmd |
|----|------|------|------------|------------|
| NCS-173 | 后处理扇出阶段超时防护 | [] | 永不完成的扇出 Future 在时限内被放弃，process() 返回，ledger 阶段失败；超时可配置默认 600s | `mvn test -Dtest=ChapterPostProcessorFanoutTimeoutTest` |
| NCS-174 | LLM 客户端超时可配置化 | [] | connect/read/call 三项超时由配置驱动，默认收紧；application.yml 提供开关 | `mvn test -Dtest=OpenAiCompatibleClientTimeoutTest` |
| NCS-175 | 审阅冲突与 409 原因透明化 | NCS-173 | 预览重试状态复位（必然重新发请求）；批准 409 原因常驻展示 | `cd frontend && npx vitest run src/**/*review*` + `npm run build` |
| NCS-176 | 候选入口可发现性与创作要求持久化 | [] | 状态条候选计数可点击直达；创作要求 localStorage 持久化 | `cd frontend && npx vitest run` + `npm run build` |

## 风险与备选方案

- 超时放弃后底层线程仍占用至 callTimeout 到期——NCS-174 收紧 callTimeout（默认 900s）后占用窗口可控；放弃的 Future 一并 cancel(true) 尽力中断。
- 扇出线程池被停滞调用占满的累积风险：阶段放弃即返回，后续章节扇出仍可提交；如观测到饥饿，可将停滞检测前移至网关层（backlog，不在本 spec）。
- 前端无既有测试基建时，为改动的纯函数/状态机抽离测试（不引入重型组件测试）。
