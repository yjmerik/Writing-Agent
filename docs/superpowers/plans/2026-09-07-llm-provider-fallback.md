# LLM provider fallback Implementation Plan

> **For agentic workers:** Implementation authorized. Execute inline with executing-plans; no delegation or additional approval is needed. Steps use checkboxes.

**Goal:** 多 provider 按配置有序故障切换，并对不可用目标冷却与恢复。

**Architecture:** 保留旧路由；新路由在 DefaultLlmGateway 中选取命名 ModelConfig。客户端新单次有界调用路径负责 HTTP/SSE 错误分类，ProviderHealthTracker 负责并发冷却。响应附实际模型计价信息并兼容录制回放。

**Tech Stack:** Java 17 / Spring Boot 3 / OkHttp 4 / JUnit 5 / MockWebServer。

## Global Constraints

- 保留当前工作目录及已有未提交修改，只修改LLM调用相关文件。
- 用户已确认 docs/specs/2026-09-07-llm-provider-fallback.md；不再询问相同审批。
- 旧配置维持原重试与超时，新链每候选一次、默认180秒、冷却60秒；不切换embedding。
- 内容审查、普通参数错误、取消和程序异常不降级；token输出后失败不拼接。
- 不加入真实key，不启用付费备用，不提交/推送/重启。
- 本地HTTP测试需获准正常执行，完整compile/test、init、replay E2E和质量基线为收尾门禁。

---
### p30-01: 命名 provider 与路由配置

**Files:** llm/config/LlmProperties.java, llm/config/ModelConfig.java, test/llm/LlmProviderConfigurationTest.java（main 路径以 src/main/java/com/writing/novel/ 为根；test 路径以 src/test/java/com/writing/novel/ 为根。）

**Interfaces:** LlmProperties.providerIdsForTask(String): List<String>; forTaskType(String): ModelConfig; ModelConfig.copy(): ModelConfig。

- [x] 写测试，覆盖该feature验收中的独立行为；关键断言：

```java
assertEquals("backup-model", props.forTaskType("CHAPTER_WRITING").getModel());
```

- [x] 运行 `mvn test -Dtest=LlmProviderConfigurationTest,LlmConfigTest,LlmPropertiesValidateTest`，先确认新增行为因未实现而失败并保存日志。
- [x] 实现：Spring Binder绑定 providers/routing/fallback；validateKeys按有效任务链校验重复、引用、URI、凭据、窗口与参数。复制完整ModelConfig。
- [x] 重跑上述命令，确认通过；feature完成后记录evidence并更新progress。

### p30-02: 服务不可用分类及请求预算

**Files:** llm/client/LlmUnavailableException.java, llm/client/OpenAiCompatibleClient.java, test/llm/LlmAvailabilityTest.java（main 路径以 src/main/java/com/writing/novel/ 为根；test 路径以 src/test/java/com/writing/novel/ 为根。）

**Interfaces:** LlmUnavailableException(reason,status); OpenAiCompatibleClient.complete / streamComplete读取resolved config.singleAttempt。

- [x] 写测试，覆盖该feature验收中的独立行为；关键断言：

```java
assertThrows(LlmUnavailableException.class, () -> client.complete(cfg, request));
assertEquals(1, server.getRequestCount());
```

- [x] 运行 `mvn test -Dtest=LlmAvailabilityTest,OpenAiCompatibleClientTest`，先确认新增行为因未实现而失败并保存日志。
- [x] 实现：新单次路径按requestTimeoutSeconds构建OkHttp调用，禁用透明重试/重定向；解析HTTP与SSE error/终止/空内容。IOException归网络，InterruptedException归取消；保留旧重试路径。
- [x] 重跑上述命令，确认通过；feature完成后记录evidence并更新progress。

### p30-03: 有序切换与故障冷却恢复

**Files:** llm/gateway/DefaultLlmGateway.java, llm/gateway/ProviderHealthTracker.java, test/llm/LlmProviderFallbackTest.java（main 路径以 src/main/java/com/writing/novel/ 为根；test 路径以 src/test/java/com/writing/novel/ 为根。）

**Interfaces:** ProviderHealthTracker.acquire/fail/succeed/release; DefaultLlmGateway.complete。

- [x] 写测试，覆盖该feature验收中的独立行为；关键断言：

```java
assertEquals("backup", gateway.complete(request).getContent());
assertEquals(List.of("primary", "backup", "backup"), attempts);
```

- [x] 运行 `mvn test -Dtest=LlmProviderFallbackTest,DefaultLlmGatewayRoutingTest`，先确认新增行为因未实现而失败并保存日志。
- [x] 实现：按有序ID选取候选，克隆并应用请求覆盖，记录故障/恢复事件；状态同步实现半开单探针，Clock注入控制时间；全失败抛脱敏汇总。
- [x] 重跑上述命令，确认通过；feature完成后记录evidence并更新progress。

### p30-04: 流式切换边界

**Files:** llm/gateway/DefaultLlmGateway.java, test/llm/LlmStreamingFallbackTest.java（main 路径以 src/main/java/com/writing/novel/ 为根；test 路径以 src/test/java/com/writing/novel/ 为根。）

**Interfaces:** DefaultLlmGateway.streamComplete(LlmRequest,StreamHandler)。

- [x] 写测试，覆盖该feature验收中的独立行为；关键断言：

```java
assertEquals(List.of("partial", "error"), callbacks);
assertEquals(List.of("primary"), attempts);
```

- [x] 运行 `mvn test -Dtest=LlmStreamingFallbackTest`，先确认新增行为因未实现而失败并保存日志。
- [x] 实现：token回调前可切换，之后失败只报一次；保护handler抛出的异常与取消不被当上游故障；终态至多一次。
- [x] 重跑上述命令，确认通过；feature完成后记录evidence并更新progress。

### p30-05: 实际 provider 观测与计费

**Files:** llm/LlmResponse.java, llm/gateway/ObservedLlmGateway.java, test/llm/LlmFallbackTelemetryTest.java（main 路径以 src/main/java/com/writing/novel/ 为根；test 路径以 src/test/java/com/writing/novel/ 为根。）

**Interfaces:** LlmResponse.Execution(providerId,model,inputPrice,outputPrice); ObservedLlmGateway读取execution。

- [x] 写测试，覆盖该feature验收中的独立行为；关键断言：

```java
assertEquals(3.0, event.path("estimatedCostCny").asDouble());
assertEquals("backup", event.path("provider").asText());
```

- [x] 运行 `mvn test -Dtest=LlmFallbackTelemetryTest,LlmCostTelemetryTest`，先确认新增行为因未实现而失败并保存日志。
- [x] 实现：通过响应的不可变元数据记录实际目标，用该价格计费，旧响应仍按旧配置；切换日志只含ID、分类和状态。
- [x] 重跑上述命令，确认通过；feature完成后记录evidence并更新progress。

### p30-06: 回放元数据兼容

**Files:** llm/gateway/RecordReplayLlmGateway.java, test/llm/RecordReplayLlmGatewayTest.java（main 路径以 src/main/java/com/writing/novel/ 为根；test 路径以 src/test/java/com/writing/novel/ 为根。）

**Interfaces:** RecordReplayLlmGateway.saveFixture/loadFixture携带可选execution对象。

- [x] 写测试，覆盖该feature验收中的独立行为；关键断言：

```java
assertEquals("backup", replay.complete(request).getExecution().providerId());
```

- [x] 运行 `mvn test -Dtest=RecordReplayLlmGatewayTest`，先确认新增行为因未实现而失败并保存日志。
- [x] 实现：写入/恢复可选metadata，旧fixture返回null；回放不访问真实delegate。
- [x] 重跑上述命令，确认通过；feature完成后记录evidence并更新progress。

### p30-07: 配置示例与完整回归

**Files:** src/main/resources/application.yml, docs/llm-provider-fallback.md（main 路径以 src/main/java/com/writing/novel/ 为根；test 路径以 src/test/java/com/writing/novel/ 为根。）

**Interfaces:** docs/llm-provider-fallback.md; application.yml注释入口。

- [x] 写测试，覆盖该feature验收中的独立行为；关键断言：

```java
assertEquals("backup-model", new DefaultLlmGateway(props, client).complete(request).getContent());
```

- [x] 运行 `./init.sh && ./scripts/e2e.sh && python3 scripts/compare-baseline.py`，先确认新增行为因未实现而失败并保存日志。
- [x] 实现：文档给出环境变量地址/凭据/模型、任务路由和冷却完整示例，明确重启生效、禁用方式和全部冷却时失败；执行所有回归。
- [x] 重跑上述命令，确认通过；feature完成后记录evidence并更新progress。

## Review and delivery

- [x] 复查全部新测试覆盖配置、错误分类、顺序、恢复并发、token边界、价格与回放。
- [x] 加载 scripts/load-env.sh 后运行 mvn compile && mvn test；运行 ./init.sh。
- [x] ./scripts/e2e.sh；python3 scripts/compare-baseline.py；失败先诊断，不能bless。
- [x] 记录指纹/验证结果，更新feature_list、progress、session-handoff与spec状态，交付配置文档。

## 实施细拆（2026-09-07，设计范围不变）

为遵守每feature最多3文件，将p30-05分为：p30-08（StreamHandler向后兼容的onComplete(LlmResponse)、客户端传usage及HTTP测试）→p30-05（响应执行元数据、网关赋值、元数据测试）→p30-09（Observed实际价格/流式usage计费及事件测试）→p30-06（回放兼容）。p30-08先运行新增usage断言确认失败，再修改客户端/回调接口并回归；p30-09先断言备用模型价格与事件归属，RED后修改计费。最终验收范围未变。

## 完成记录

2026-09-07：全部12个拆分feature完成。671项全量测试、编译、前端构建、3章10191字replay与质量基线通过；证据data/verification/llm-provider-fallback/verification.json。工作保留在当前分支和工作目录，未提交/推送。
