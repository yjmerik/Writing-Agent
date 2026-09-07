# Spec: 多 LLM provider 自动故障切换

日期：2026-09-07。状态：用户已于2026-09-07确认“开始实现”，实施及回归已完成。

## 背景与动机

DefaultLlmGateway.complete 目前仅捕获 RateLimitException，并最多切到不同的 default-model 一次；streamComplete 直接委托客户端。当前默认及 8 类任务均指向同一 Token Plan 端点/模型，因此现有降级没有可用备用目标。客户端分别实现普通 HTTP、SSE 聚合和 token 回调，错误类型与重试行为不一致，单次超时固定为 30 分钟。需要统一不可用判定和有界切换。

## 目标

1. 配置任意多个命名的 OpenAI 兼容 provider（独立地址、凭据、模型、生成参数和超时），按默认或任务专属有序列表选择主备。
2. 当前请求遇到服务不可用时自动依次尝试备用；后续请求跳过冷却中的目标，冷却到期由实际请求尝试恢复。
3. 同步、SSE 聚合及 token 回调遵守同一错误策略，切换可观测且保留旧配置和离线回放兼容性。

## 非目标

本期不增加配置 UI、热更新、后台健康探测、负载均衡、原生 Anthropic/Gemini 协议、embedding 切换。不同 embedding 模型会使现有索引不兼容，仍使用现有独立配置。实施和验证使用本地模拟服务及离线回放；配置示例不会自行启用付费备用服务或替换当前主模型。

## 设计

### 配置与优先级

新增 llm.providers，条目复用完整 ModelConfig：base-url/api-key/model/temperature/max-tokens/context-window/streaming/style-guide/thinking-enabled/价格及可配置请求超时。
新增 llm.routing.default-providers 与 llm.routing.tasks.<TASK_TYPE> 有序 provider ID 列表；任务列表优先于默认列表。列表首项为主模型，其余为备选。provider ID 表示一套具体端点、凭据与模型，可在同一服务商注册不同模型或账户。

未使用新路由时保留 default-model/models 旧行为；显式新列表不隐式追加 default-model。启动校验有效路由的引用、重复 ID、必填字段、正数超时和冷却时间。只要求被有效路由引用的远程 provider 有凭据；replay/scenario 不要求真实凭据。

请求级参数按现有规则解析并完整复制目标配置，不能在切换中丢失 thinking/style/context/价格等字段或修改共享配置。针对新链要求备用 context-window 不小于主模型，避免给较小窗口发送已经组装好的长上下文；不静默裁剪正文。未经新路由选择的旧配置继续兼容。

拟定配置形式（需填写真实模型和地址，不直接写入运行配置）：

```yaml
llm:
  providers:
    primary:
      base-url: ${PRIMARY_LLM_BASE_URL}
      api-key: ${PRIMARY_LLM_API_KEY}
      model: ${PRIMARY_LLM_MODEL}
      context-window: 64000
      request-timeout-seconds: 180
    backup:
      base-url: ${BACKUP_LLM_BASE_URL}
      api-key: ${BACKUP_LLM_API_KEY}
      model: ${BACKUP_LLM_MODEL}
      context-window: 64000
      request-timeout-seconds: 180
  routing:
    default-providers: [primary, backup]
    tasks:
      CHAPTER_WRITING: [primary, backup]
  fallback:
    cooldown-seconds: 60
```

### 不可用判定与切换

统一为有类型的调用异常，不依赖完整错误文案匹配。网络连接/DNS/TLS/读写超时、HTTP 401/402/403/404/408/429/5xx、供应商明确的余额/配额错误和空有效响应可触发切换。供应商内容审查（即使外层 HTTP 403）、普通参数错误 HTTP 400/422、用户取消/线程中断和程序异常不触发切换。HTTP 成功但 SSE 含 error 对象、未收到正常终止即断流也必须识别；上下文超限归参数错误。

新配置路由由网关控制总预算，每个候选每次请求最多尝试一次，避免客户端内部 3 次重试叠乘候选数量。原路径保留旧重试兼容行为。新 provider 默认单次 180 秒，可为长正文显式增加；旧配置维持原超时。没有可用候选时快速抛出汇总异常，列出脱敏 provider ID、错误分类和下一次可尝试时间；不无界重试或等待。

不可用后按 provider ID 冷却 60 秒（可配置），作用域为当前进程。冷却期跳过；到期仅允许一个并发请求试用该目标，其余走备用，成功清除状态，失败重新冷却。不额外产生后台请求。全部候选冷却时立即报错，不破坏冷却规则。

### 流式一致性

complete 的 SSE 聚合结果在完整成功前不交给调用者，因此可丢弃失败尝试的临时结果并切换。streamComplete 在尚未对调用者发送 token 前可以切换；一旦发送正文 token 后失败，则只报告一次错误，不拼接另一模型重新生成的内容。成功或失败终态只通知一次。调用者 handler 抛出的异常不能被识别为 provider 故障。取消/中断停止整个链。

### 观测和费用

记录 LLM_FALLBACK/不可用与恢复事件，字段包括 taskType、from/to provider ID、model、错误分类、状态码、尝试序号和耗时。不输出 API key、Authorization、完整请求或原始供应商错误体。成功响应携带实际执行 provider/model 与价格元数据，ObservedLlmGateway 按实际成功模型估算费用；失败尝试没有 usage 时不虚构费用。RecordReplay 保留可选元数据，旧 fixture 仍能读取，回放不执行真实 fallback 或访问网络。

## 验收标准

- [x] 配置绑定/有效路由校验/旧配置兼容测试通过：`mvn test -Dtest=LlmProviderConfigurationTest,LlmConfigTest,LlmPropertiesValidateTest`。
- [x] 本地 HTTP/SSE 模拟验证状态码、超时、空响应、SSE 错误和取消分类：`mvn test -Dtest=LlmAvailabilityTest,OpenAiCompatibleClientTest`。
- [x] 主失败→备成功、三级链、任务覆盖、全部失败、完整参数复制、冷却和并发恢复测试通过：`mvn test -Dtest=LlmProviderFallbackTest,DefaultLlmGatewayRoutingTest`。
- [x] token 前失败切换、token 后失败无重复/拼接、终态一次、回调异常不切换：`mvn test -Dtest=LlmStreamingFallbackTest`。
- [x] 成本归属实际成功 provider、日志脱敏、录制回放新旧响应兼容：`mvn test -Dtest=LlmFallbackTelemetryTest,LlmCostTelemetryTest,RecordReplayLlmGatewayTest`。
- [x] 加载项目环境后 `mvn compile && mvn test` 和 `./init.sh` 通过；`./scripts/e2e.sh` 与 `python3 scripts/compare-baseline.py` 通过，不自动 bless。

## 影响面

- **涉及模块**：llm/config、llm/client、llm/gateway、LlmResponse、配置文档与测试。
- **涉及 prompt 模板**：无。完整复制原模型参数可能使此前丢失的 style-guide/thinking-enabled 生效，必须先补测试并执行质量回归。
- **数据库 schema 变更**：无。
- **质量基线影响**：主路径与备用模型的生成能力不同；离线回归只能验证兼容性，真实备用输出质量需在用户提供并启用相应模型后另行验证。

## Feature 拆分

状态文件/spec 更新不计入每个 feature 的 1–3 个实现/测试文件。

| id | name | 依赖 | acceptance | verify_cmd |
|----|------|------|------------|------------|
| p30-01 | 命名 provider 与路由配置 | [] | 配置绑定、旧格式、引用/凭据/窗口校验测试通过 | mvn test -Dtest=LlmProviderConfigurationTest,LlmConfigTest,LlmPropertiesValidateTest |
| p30-02 | 服务不可用分类及请求预算 | p30-01 | HTTP/SSE/网络错误分类及超时测试通过 | mvn test -Dtest=LlmAvailabilityTest,OpenAiCompatibleClientTest |
| p30-03 | 有序切换与故障冷却恢复 | p30-02 | 多级切换、完整参数、冷却和并发试用测试通过 | mvn test -Dtest=LlmProviderFallbackTest,DefaultLlmGatewayRoutingTest |
| p30-04 | 流式切换边界 | p30-03 | token 前后失败、取消、终态一次测试通过 | mvn test -Dtest=LlmStreamingFallbackTest |
| p30-05 | 实际 provider 观测与计费 | p30-03 | 切换元数据与实际价格、脱敏测试通过 | mvn test -Dtest=LlmFallbackTelemetryTest,LlmCostTelemetryTest |
| p30-06 | 回放元数据兼容 | p30-05 | 新旧 fixture 回放兼容且无网络 | mvn test -Dtest=RecordReplayLlmGatewayTest |
| p30-07 | 配置示例与完整回归 | p30-04,p30-06 | init、E2E 和质量基线对比通过 | ./init.sh && ./scripts/e2e.sh && python3 scripts/compare-baseline.py |

## 风险与备选方案

仅顺序切换实现最少，但故障主模型会让每次调用重复等待；推荐增加短期冷却。后台健康探测和负载均衡增加调用费用及复杂度，当前不采用。不同模型生成风格与费用不同，备用必须显式配置，不能从本地其他应用自动发现或启用。可以移除新 routing 配置退回旧路径；不改数据库与原小说。

## 当前进度

设计已确认；依照配套实施计划逐 feature 开发，运行中服务不自动重启。

## 实施细拆（2026-09-07，设计范围不变）

为遵守每feature最多3文件，将p30-05分为：p30-08（StreamHandler向后兼容的onComplete(LlmResponse)、客户端传usage及HTTP测试）→p30-05（响应执行元数据、网关赋值、元数据测试）→p30-09（Observed实际价格/流式usage计费及事件测试）→p30-06（回放兼容）。p30-08先运行新增usage断言确认失败，再修改客户端/回调接口并回归；p30-09先断言备用模型价格与事件归属，RED后修改计费。最终验收范围未变。

## 实施边界校验

p30-10（client及测试）验证认证/余额具体code与状态优先于宽泛invalid_request_error type；验证HTTP503 Retry-After:0也不引起底层透明重复调用。p30-11（配置及测试）要求备用有效输入余量（context-window减max-tokens）不小于主模型，确保长上下文切换不因输出预算更大而溢出。均为原有单次预算/长上下文兼容性的验收补充，先failing test复现后修复。

p30-12：全量回归中的 WritingJobReliabilityTest 两个旧配置mock返回null，改为真实LlmProperties，并核对实际fallback模型选择。验证命令mvn test -Dtest=WritingJobReliabilityTest；不修改业务实现。

## 最终验证（2026-09-07）

- ./init.sh：编译通过，671 tests / 0 failures / 0 errors / 1 skipped，frontend build PASS。
- E2E_FIXTURE_DIR=data/e2e-fixtures/canary-mystery-20260831 ./scripts/e2e.sh：3章10191字，54次回放调用，0 LLM错误、0章节失败。
- python3 scripts/compare-baseline.py：PASS，未修改golden基线。
- 12个原子feature全部完成（p30-01至p30-12），全项目226 completed。配置说明：docs/llm-provider-fallback.md。
- 证据：data/verification/llm-provider-fallback/verification.json，含验证命令/退出码/输入指纹与对应日志。实际付费备用未启用，未重启当前服务；接入真实provider后须另验真实模型质量。
