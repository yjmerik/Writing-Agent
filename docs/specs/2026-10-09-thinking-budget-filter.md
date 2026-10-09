# Spec: 思考预算过滤（THINKING_BUDGET）——思考模型吃满输出预算的精准识别与预算升级重试

- 日期：2026-10-09
- Feature ID：NCS-185
- 类型：bug（部署 GLM coding plan glm-5.3-flash 后实测暴露）
- 来源：北京服务器部署功能验证（用户指令"预算过滤思考内容"）

## 背景

glm-5.3-flash 为强制思考模型（端点明示"该模型始终思考，不支持关闭思考；请使用 low、high 或 max"）。
当 `max_tokens` 较小（探针 64；CONTINUITY_CHECK/STYLE_GUARDIAN 4096）时，reasoning_content
可能独占输出预算：响应 `finish_reason=length`、`content` 为空、`reasoning_content` 非空。

现状缺陷：
- `OpenAiCompatibleClient` 非 流式/流式 两路径都只聚合 `content`，此场景一律抛
  `EMPTY_RESPONSE`；
- `DefaultLlmGateway` 把 `LlmUnavailableException` 一律按供应商健康处理（recordFailure →
  熔断冷却 → 切下一路由），而单 provider 路由立即 exhausted——同参重试必然同败；
- `LlmProbe` 硬顶 64 tokens，思考模型在探针预算下结构性必败，UI 探测永远红灯。

## 目标

1. 精准识别"思考吃满输出预算"：`finish_reason=length` + content 空 + reasoning_content 非空
   → 新 `LlmUnavailableException.Reason.THINKING_BUDGET`（非流式与流式两路径一致）。
2. 网关按"预算标定问题"处理 THINKING_BUDGET：同 provider 升级 `max_tokens`（×2）重试，
   至多 2 次升级；升级上限 = `contextWindow - 请求预算 - 1024` 余量；不计入熔断冷却、
   不算 provider 失败；预算升级重试耗尽才进入原有失败路径。
3. 探针预算 64 → 512（成本仍可忽略），使思考模型可通过连通性探测。
4. 流式路径仅在尚未向 handler 发射任何 content token 时升级重试（已发射则保持现状上抛）。

## 非目标

- 不支持 thinking.type=low/high/max 注入（现网预算充足，观察后再议）。
- 不改 usage/费用统计口径（reasoning tokens 是真实成本，照实计入）。
- 不改各任务 max_tokens 基线。

## 验收标准

- 客户端单测：非流式/流式 mock 响应（length + 空 content + reasoning）抛
  `Reason.THINKING_BUDGET` 而非 EMPTY_RESPONSE。
- 网关单测：首个 attempt 返回 THINKING_BUDGET、升级 max_tokens 后成功 → complete() 成功，
  且断言第二次调用的 config.maxTokens = 2×；provider 健康未进入冷却。
- 升级仍 THINKING_BUDGET → exhausted 抛出（信息含 THINKING_BUDGET）。
- `mvn test` 全绿；`./scripts/e2e.sh`（回放）PASS——录制语料均为有 content 响应，不受影响。

## 影响面

- `src/main/java/com/writing/novel/llm/client/LlmUnavailableException.java`（新 Reason）
- `src/main/java/com/writing/novel/llm/client/OpenAiCompatibleClient.java`（两路径识别）
- `src/main/java/com/writing/novel/llm/gateway/DefaultLlmGateway.java`（升级重试）
- `src/main/java/com/writing/novel/llm/LlmProbe.java`（探针预算 64→512）
- 测试：`OpenAiCompatibleClientTest`、`LlmProviderFallbackTest`（或新测试类）
