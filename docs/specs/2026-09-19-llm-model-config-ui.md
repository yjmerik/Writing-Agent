# Spec: LLM 模型配置界面化（模型配置页面）

## 背景与动机

LLM 模型配置（`llm.default-model` + `llm.models.<TASK_TYPE>`）目前只能编辑 `application.yml` + 环境变量后重启生效。用户希望在页面上直接增加、修改、删除模型配置，并支持三种主流协议：OpenAI Chat Completions、OpenAI Responses API、Anthropic Messages API。

关键架构事实：`DefaultLlmGateway` 每次请求动态读取 `LlmProperties`（`forTaskType`），因此运行时更新该 bean 内的配置 Map 即时生效，无需重启，也无需改动网关代码。

## 目标

1. 新增「模型配置」页面：表格展示 DEFAULT + 8 个任务类型的有效模型配置（现有 yml 配置必须正常展示），支持编辑、删除（回退 yml 值）与新增（为未单独配置的范围添加覆盖）。
2. 页面改动持久化到 `data/config/llm-models.json`，重启后保持；删除覆盖后运行时与展示均回退到 yml 基线。
3. 模型配置支持 `protocol`（OPENAI_CHAT_COMPLETIONS / OPENAI_RESPONSES / ANTHROPIC_MESSAGES）、`base_url`、`api_key`（界面脱敏展示）、`model_id` 及常用参数（temperature / max_tokens / context_window / streaming / 超时）。
4. 新协议客户端可真实调用对应 API（请求形状、鉴权头、流式/非流式解析均按协议规范）。

## 非目标

- 不管理 embedding 配置（`persistence.embedding-cfg`）与 replay/scenario 配置。
- 不管理 `llm.providers` / `llm.routing` 多 provider fallback 路由（现有 yml 未使用该机制；本特性覆盖 `default-model` + `models` 层）。
- 不回写 `application.yml`；页面配置是运行时覆盖层，yml 保持只读基线。
- 不做连通性测试按钮（后续 backlog 可加）。

## 验收标准

- [ ] `mvn test -Dtest=LlmModelConfigStoreTest` 通过：JSON 持久化、启动合并、删除回退 yml 基线。
- [ ] `mvn test -Dtest=ModelConfigControllerTest` 通过：GET 展示 yml 现有配置；PUT 新增/修改（api_key 空值保留原值、返回脱敏）；DELETE 回退。
- [ ] `mvn test -Dtest='AnthropicMessagesClientTest,OpenAiResponsesClientTest,ProtocolRoutingLlmClientTest'` 通过：URL/请求头/请求体按协议规范，流式与非流式解析正确，默认协议分发到现有 OpenAI 兼容客户端。
- [ ] `mvn compile && mvn test` 全量通过；`cd frontend && npm run build` 通过。
- [ ] ego-browser 端到端：页面展示现有 qwen3.8-flash 配置 → 修改 GENERAL（换 model_id）→ 新增覆盖（Anthropic 协议示例）→ 删除恢复，`data/config/llm-models.json` 状态正确，最终无残留覆盖。
- [ ] 未配置 `llm.model-config-file`（或为空）时行为与现状完全一致（默认协议零行为变化）。

## 影响面

- **涉及模块**: llm/config（LlmProperties、ModelConfig、新增 LlmProtocol/LlmModelConfigStore）、llm/client（新增 AnthropicMessagesClient/OpenAiResponsesClient/ProtocolRoutingLlmClient）、llm/LlmConfig 装配、web（新增 ModelConfigController）、frontend（新增页面/路由/API）
- **涉及 prompt 模板**: 无
- **数据库 schema 变更**: 无（JSON 文件持久化，不动 SQLite）
- **质量基线影响**: 无——默认协议路径零变化，不注入/修改任何 prompt；E2E 回放不经过新代码路径

## Feature 拆分

| id | name | 依赖 | acceptance | verify_cmd |
|----|------|------|------------|------------|
| p43-01 | LLM 模型配置页面（协议/增删改/持久化/展示） | [] | 上述全部验收项通过，浏览器 E2E 留证 | `mvn test -Dtest='LlmModelConfigStoreTest,ModelConfigControllerTest,AnthropicMessagesClientTest,OpenAiResponsesClientTest,ProtocolRoutingLlmClientTest'` |

（协议客户端与配置 store/控制器逻辑耦合在同一装配链上，拆分反而引入中间态不可验证的分支，故单 feature 闭环。）

## 风险与备选方案

- **生产同机测试污染**：`mvn test` 与生产服务共用工作目录，生产覆盖文件可能改变测试断言。对策：断言原始配置值的 `LlmConfigTest` / `LlmPropertiesValidateTest` 显式置空 `llm.model-config-file`；store 对空路径禁用 I/O。
- **api_key 泄露**：API 只返回掩码；PUT 空 api_key = 保留原值；JSON 文件权限同 SQLite（本机单用户）。
- **回滚**：删除 `data/config/llm-models.json` + 回退代码即完全恢复现状；yml 从未被改写。
- **被否决方案**：管理 `llm.providers`+`routing` 命名档案池——现有 yml 未用该机制，启用会切换启动校验路径（validateKeys 进入 provider 模式），风险与收益不成比例；直接回写 application.yml——破坏环境变量占位符与注释，不可行。
