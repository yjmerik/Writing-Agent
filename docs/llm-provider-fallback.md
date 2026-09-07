# 配置多个 LLM provider 与自动故障切换

系统支持任意多个 **OpenAI Chat Completions 兼容** provider。每个 provider 是一套独立的地址、凭据、模型和生成参数；同一服务商的不同模型或账户可以使用不同 ID。

当前默认运行配置继续使用既有模型。要启用 fallback，需要显式配置下面的 provider 和路由，并重启应用。没有新路由时仍使用原 `default-model` / `models` 配置及旧重试策略。

## 配置示例

将下面内容合并到 `src/main/resources/application.yml` 已有的 `llm:` 节点；不要创建第二个同名顶层节点。也可以保存为独立配置文件，并通过 Spring Boot 的 `spring.config.additional-location` 加载。

```yaml
llm:
  providers:
    primary:
      base-url: ${PRIMARY_LLM_BASE_URL}
      api-key: ${PRIMARY_LLM_API_KEY}
      model: ${PRIMARY_LLM_MODEL}
      context-window: 64000
      max-tokens: 20000
      streaming: true
      request-timeout-seconds: 180
      # 按目标模型的实际价格填写；单位为元/千 token。
      input-price-yuan-per-k-tokens: 0.001
      output-price-yuan-per-k-tokens: 0.002
    backup:
      base-url: ${BACKUP_LLM_BASE_URL}
      api-key: ${BACKUP_LLM_API_KEY}
      model: ${BACKUP_LLM_MODEL}
      context-window: 64000
      max-tokens: 20000
      streaming: true
      request-timeout-seconds: 180
      input-price-yuan-per-k-tokens: 0.001
      output-price-yuan-per-k-tokens: 0.002
  routing:
    default-providers: [primary, backup]
    tasks:
      CHAPTER_WRITING: [primary, backup]
  fallback:
    cooldown-seconds: 60
```

在启动环境中设置对应地址、密钥和模型名；`base-url` 是兼容 API 根地址（例如以 `/v1` 结尾），不要包含 `/chat/completions`。不要将真实密钥提交到仓库。示例价格仅为占位估算，需替换为所选模型价格；上下文窗口与输出上限也必须按所用模型能力填写。

- `default-providers` 的顺序决定默认主备优先级。
- `tasks.<TASK_TYPE>` 覆盖该任务的完整候选列表，不会再追加默认列表。
- 支持 `CHAPTER_WRITING`、`CONTINUITY_CHECK`、`PLOTTING`、`WORLDBUILDING`、`CHARACTER_DESIGN`、`STYLE_GUARDIAN`、`MEMORY_SUMMARY`、`GENERAL`。
- 未设置默认列表时，没有任务专属路由的任务继续使用旧配置。
- provider 可以有不同的 `style-guide`、`thinking-enabled`、`max-tokens` 和价格；这些参数会完整保留。不要对不支持 `thinking` 参数的模型随意设置 `thinking-enabled: false`。
- 若不同任务需要不同生成参数，可为它们注册独立 provider ID。请求中的 temperature 按既有规则覆盖 provider 配置；输出 token 预算取请求值与 provider 配置值的较大者。
- 移除 `routing` 中的新列表并重启，即可恢复旧路由。没有隐式备用服务发现、后台探测或自动启用付费模型。

## 什么情况下切换

新路由的每个候选每次请求最多调用一次；禁用底层连接重试、重定向和 HTTP 503 的自动追加请求。单个候选的完整请求超时默认 180 秒，长正文可以增加 `request-timeout-seconds`。若列表含 N 个目标，总等待预算最多约为各候选超时之和，另加本地处理开销。

触发切换的情况：

- 连接、DNS、TLS 或读写错误、请求超时。
- HTTP 401/402/403/404/408/429/5xx。
- 明确的配额/余额错误代码，例如 `insufficient_quota`、`insufficient_balance`、`quota_exceeded`。
- 空有效响应、SSE 错误事件、流式响应异常结束。

内容审查错误优先于 HTTP 状态处理，不触发切换。普通参数错误、上下文超限、用户取消/线程中断、调用方程序异常也不触发切换。

对于 `complete` 内部的 SSE 聚合，失败尝试的半截内容会被丢弃，备用模型重新处理完整请求。对于逐 token 回调，**首个正文 token 发出后发生故障，不在本次流中切换**，而是报告一次失败；后续请求仍会避开故障目标，防止两次生成的正文混在一起。

## 冷却与恢复

provider 不可用后进入冷却期，默认 60 秒。期间后续请求直接跳过该 provider。到期后由下一次真实请求尝试恢复，只允许一个并发请求探测，其余请求继续走备用。成功恢复主模型优先级，失败则重新冷却。状态仅保存在当前应用进程中，重启后清空。

全部 provider 失败或都在冷却时，立即报告汇总错误，包含 provider ID、错误类别和下次尝试时间。不无限重试、不在请求内等冷却结束。现有写作编排器可以按自己的重试/暂停机制处理最终错误。

## 启动校验与兼容范围

引用不存在的 provider、重复 ID、空任务列表、未知任务名、缺少有效地址/模型/凭据、非正超时或冷却值，会在启动时失败。超时和冷却允许 1–86400 秒。只校验有效路由引用的 provider 凭据，未使用的配置无需填写；离线 replay/scenario 不要求真实密钥。

每个命名 provider 必须明确设置正数 `context-window`，且 `max-tokens` 小于窗口。备用窗口不能小于主模型；备用的输入余量（窗口减输出 token 配置）也不能小于主模型。系统不会为了切换而静默裁剪小说正文。

仅支持 OpenAI 兼容的文本接口；embedding 使用现有独立配置，避免不同向量模型混用索引。本期通过配置文件管理，不提供热更新或配置 UI。

## 日志、费用和验证

结构化事件记录在 `data/logs/events.jsonl`：

- `LLM_PROVIDER_UNAVAILABLE`：provider、模型、错误类别、HTTP 状态、尝试序号、耗时和恢复时间。
- `LLM_FALLBACK`：当前请求实际发生的从/到 provider 切换。
- `LLM_PROVIDER_RECOVERED`：冷却到期探测成功。
- `LLM_CALL`：实际成功 provider/模型、token 用量和按该 provider 价格估算的费用，支持普通调用及携带 usage 的流式调用。

日志和执行元数据不包含 provider 密钥、Authorization、请求正文或供应商原始错误体。费用是根据返回 usage 的估算；失败尝试缺少 usage 时无法核算其潜在费用，供应商账单可能更高。

录制文件会保存成功 provider 的价格快照和 token 用量；旧录制文件继续兼容。回放只读取文件，不访问 provider。现有录制模式本身会保存请求/正文以便回放，这与新增的脱敏事件日志不同。

```bash
# 配置、HTTP/SSE、路由、冷却、流式边界及真实本地HTTP组合验证
mvn test -Dtest=LlmProviderConfigurationTest,LlmAvailabilityTest,LlmProviderFallbackTest,LlmStreamingFallbackTest,LlmProviderIntegrationTest

# 费用与录制回放
mvn test -Dtest=LlmFallbackTelemetryTest,LlmProviderCostTest,LlmCostTelemetryTest,RecordReplayLlmGatewayTest

# 完整回归（init 自动加载项目环境）
./init.sh
./scripts/e2e.sh
python3 scripts/compare-baseline.py
```

本地模拟测试和回放验证切换可靠性，不替代真实备用模型的小说质量与服务兼容性验收。
