# LLM 模型配置页面完成 — 2026-09-19

Last Updated: 2026-09-19

## Current

p43-01「LLM 模型配置界面化」已完成并合入本地 main（实现提交 97dad38 = 当前 main HEAD，ff-merge）。
完成记录 p43-01-verified（docs/harness/evidence/2026-09-19-p43-01.json），active_feature=null。
8080 正式服务已切换为 main 构建的 target/manual-review/writing-agent-p43-01-97dad38.jar，
以 start_new_session 脱离进程组启动；验证后模型配置零残留（data/config/llm-models.json entries 为空，
运行时 9×YML 与 yml 完全一致）。
主目录两处未跟踪材料（.zcode/plans/plan-sess_8dbf5c3f*.md、docs/reports/2026-09-05-ui-experience/）
按此前约定保留不动。

## Files

- 后端新增：llm/config/LlmProtocol、LlmModelConfigStore；llm/client/AnthropicMessagesClient、
  OpenAiResponsesClient、ProtocolRoutingLlmClient；web/ModelConfigController
- 后端修改：llm/config/ModelConfig（protocol 字段）、LlmConfig（协议分发装配 + store 依赖先于启动校验）、
  gateway/DefaultLlmGateway（cloneConfig 带协议）
- 前端：pages/ModelConfigPage.tsx（新增）、App.tsx（/models 路由 + Header 导航）、api/client.ts、types.ts
- 测试：6 个新测试类（store/controller/校验/两协议客户端/分发）+ LlmConfigTest/LlmGatewayWiringTest 适配
- 文档：docs/specs/2026-09-19-llm-model-config-ui.md、docs/reports/p43-01-e2e-model-config-page.png
- 本轮归档：docs/harness/archive/2026-09-18-p42-01-progress.md、2026-09-18-p42-01-session-handoff.md

## Verification

干净检出（git worktree --detach 97dad38）复验，均为本轮实际执行：
- mvn compile && mvn test：968 项，0 失败 / 0 错误 / 1 跳过（既有）（/tmp/p43-01-clean-main-test.log）
- verify_cmd（6 个测试类）：33 项 0 失败（/tmp/p43-01-verifycmd-clean.log）
- npm --prefix frontend run build：通过（干净检出 + 主工作区各一次）
- ego-browser 浏览器 E2E（8080 服务）：现有 yml 配置 9 范围正确展示（含掩码 key、
  章节写作 0.8/20000 等差异值）；编辑 GENERAL 生效并落盘；新增风格守护 Anthropic Messages
  协议配置完整落盘；删除后两处均回退「配置文件」来源且 JSON 清空、运行时恢复原状
- python3 scripts/check-harness.py --check-git：PASS（见交接 Verification）

## Blockers

无。已知环境注记：
- mvn test 需 DASHSCOPE_TOKEN_PLAN_API_KEY（scripts/load-env.sh 从 shell 配置提取；本轮该变量
  仅存在于生产进程环境，已提取到 data/.prod-env-keys 供本地复用，gitignore）
- 全新检出需先 npm build 生成 static（p42-01 已知约束，本轮照做）

## Next Steps

- 下一特性从 main 新建分支；backlog 无 not-started 项，新需求按需求拆分协议入口
- 模型配置页后续可选增强（未登记）：连通性测试按钮、embedding 配置管理、providers/routing 路由管理
