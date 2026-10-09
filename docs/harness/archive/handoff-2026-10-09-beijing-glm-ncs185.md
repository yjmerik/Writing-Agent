# 交接：北京服务器部署 + GLM 全通道接入 + NCS-185 思考预算过滤 — 2026-10-09

Last Updated: 2026-10-09

## Current

北京轻量服务器部署完成并真实跑通写作全链路；GLM 空响应根因（思考吃满输出预算）以
NCS-185 修复合入 main：

- **部署**：39.107.255.155 = 北京 SWAS（主账号，swas-open 云助手可管），/opt/writingagent +
  systemd `novel-writing`，80 端口。布局/env/重部署流程见记忆 beijing-swas-deployment
- **模型**：LLM 全通道 = GLM coding plan glm-5.3-flash（用户专用 key，经 DASHSCOPE_TOKEN_PLAN_*
  env 覆盖写入 /opt/writingagent/writingagent.env）；embedding = 业务空间专属端点
  qwen3.7-text-embedding-flash（NCS-169 闭环，服务器与本地 .zshrc/load-env 均已配齐）
- **NCS-185**（b5e9810，completed）：GLM 强制思考（不可关闭，仅 low/high/max 档），思考吃满
  max_tokens 时旧代码报 EMPTY_RESPONSE 并触发熔断（NCS-182 回切 qwen 的"空响应率高"根因）。
  现四路径精准识别 THINKING_BUDGET，网关同 provider ×2 升级（≤2 次、窗口余量封顶、
  LLM_THINKING_BUDGET_ESCALATION 事件），探针 64→512。新增 ThinkingBudgetFilterTest 8 用例
- **生产实测**：探针 ok；项目《冒烟测试·剑试新服》（587a6bba，24 章目标）构思在 GLM 上完成，
  第 1 章《验尸人》3756 字落库，CHAPTER_WRITING 20000→40000 / CONTINUITY_CHECK 4096→8192
  两次升级自动生效，向量索引 .idx 生成（embedding 链路通）。任务已 PAUSED 保护配额
- **NCS-186**（backlog）：e2e 回放 1 章后被章节人审屏障暂停——A/B 实测 main 同样失败，
  与本轮无关

## Files

- spec：docs/specs/2026-10-09-thinking-budget-filter.md
- main：79bb12a(load-env BASE_URL)/47a92ed(yml EMBEDDING_MODEL 占位)/bb62dde(登记)/
  b5e9810(NCS-185 实现)/9f1412b(完成记录)
- 归档：docs/harness/archive/{progress-2026-10-09-rootcause-review,handoff-2026-10-07-50w-and-eval}.md
- 服务器现状：jar=b5e9810 打包；env 六变量（TOKEN_PLAN key/BASE_URL/MODEL + EMBEDDING key/BASE_URL/MODEL）

## Verification

- 全量 mvn test 1492/0F/0E/1S（2026-10-09，load-env 后）；compare-baseline PASS；回放 0 LLM 错误
- 服务器探针 {"ok":true,"sample":"通"}；第 1 章 GLM+embedding 真实链路完成（24 分钟/3756 字）

## 环境注意

- mvn test 必须 bash -c 'source scripts/load-env.sh && mvn test'
- 新加坡 47.237.206.215 自 2026-10-09 起 HTTP/SSH 全超时（疑已释放）；其角色由北京服务器接替
- Mac 代理绕过列表已补 39.107.255.155 与 47.237.206.215（xray 明文复用 bug）
- 18095 本地实例仍在跑 qwen 旧包（PID 88352，与北京服务器互不影响）
- GLM 通道 yml 配置在 git 历史（99fe6e0），env 覆盖方案失效时可回溯

## Blockers

- 无（qwen Token Plan 配额 10-10 00:00 重置，仅影响《山河执棋人》续写，与北京服务器无关）

## Next Steps

1. backlog 排期：NCS-183/184（根因审查组件，最高杠杆）> NCS-186（e2e 屏障）> NCS-179/180 > NCS-177
2. 观察点：GLM 每章 15-25 分钟、CONTINUITY_CHECK 4096 档升级翻倍拖慢节奏——
   可评估 thinking.type=low 档支持或上调检查基线预算
3. （可选）配额恢复后继续《山河执棋人》50 万字（18095/qwen 通道）
