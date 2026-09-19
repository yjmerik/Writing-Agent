# LLM 模型配置页面交接 — 2026-09-19

Last Updated: 2026-09-19

## Current

无进行中特性（active_feature=null）。p43-01「LLM 模型配置界面化」已完成合入 main：实现提交 97dad38（当前 main HEAD，ff-merge，无夹带），
完成记录 p43-01-verified（docs/harness/evidence/2026-09-19-p43-01.json）。
恢复入口：从 main 为下一特性新建独立分支；一轮只推进一个 feature。

## Files

- 特性实现：llm/config/{LlmProtocol,LlmModelConfigStore,ModelConfig,LlmConfig}、
  llm/client/{AnthropicMessagesClient,OpenAiResponsesClient,ProtocolRoutingLlmClient}、
  gateway/DefaultLlmGateway、web/ModelConfigController、
  frontend/src/{pages/ModelConfigPage.tsx,App.tsx,api/client.ts,types.ts}
- 测试：LlmModelConfigStoreTest、ModelConfigControllerTest、ModelConfigControllerValidationTest、
  AnthropicMessagesClientTest、OpenAiResponsesClientTest、ProtocolRoutingLlmClientTest（新增）；
  LlmConfigTest/LlmGatewayWiringTest（适配：model-config-file 置空 + 协议分发装配断言）
- 文档：docs/specs/2026-09-19-llm-model-config-ui.md、docs/reports/p43-01-e2e-model-config-page.png
- 历史状态：docs/harness/archive/2026-09-18-p42-01-progress.md、2026-09-18-p42-01-session-handoff.md

## Verification

干净检出（worktree --detach 97dad38）复验全部通过：mvn compile && mvn test 968 项 0 失败/0 错误/1 跳过
（既有）；verify_cmd 33 项 0 失败；前端构建通过；check-harness --check-git PASS。
ego-browser E2E 全过：现有 yml 配置展示（9 范围/掩码 key/差异参数）、编辑即时生效并落盘、
新增 Anthropic 协议配置、删除回退且 JSON 清空、运行时恢复原状；截图入 docs/reports/。
8080 正式服务运行 main 97dad38 构建的 target/manual-review/writing-agent-p43-01-97dad38.jar（pid 37302），
模型配置零残留（/api/llm/models 9×YML，data/config/llm-models.json entries 空）。
服务运维注记（沿 p42-01）：重启须 `source scripts/load-env.sh`（或叠加 data/.prod-env-keys）并以
start_new_session 脱离进程组，否则工具会话回收连带 java 进程。
环境注记：shell 缺 DASHSCOPE_TOKEN_PLAN_API_KEY 时全量测试因启动校验失败（本轮从生产进程环境提取到
data/.prod-env-keys，gitignore）；全新检出先 npm build 再 mvn test。

## Blockers

无。
p40-05/p40-06 保持 deferred：缺真实严重错误计数、回放等待人工复核。

## Next Steps

新需求按需求拆分协议登记后另开分支；启动新会话先跑 ./init.sh 确认基线健康。
模型配置页可选增强（未登记）：连通性测试按钮、embedding 配置管理、providers/routing 多级路由管理。
日志 /tmp/p43-01-*.log。
