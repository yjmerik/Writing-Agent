# 北京服务器部署 + GLM 接入 + NCS-185 思考预算过滤 — 2026-10-09

Last Updated: 2026-10-09

## Current

- 北京轻量服务器 39.107.255.155 上线（main b5e9810 jar；/opt/writingagent；systemd novel-writing）
- LLM 全通道切 GLM coding plan glm-5.3-flash（用户提供的专用 key，/api/coding/paas/v4）；
  embedding 走业务空间专属端点 qwen3.7-text-embedding-flash（NCS-169 闭环）
- NCS-185 completed：THINKING_BUDGET 精准识别 + 网关×2 预算升级重试 + 探针 64→512
  （根因：GLM 强制思考，思考吃满输出预算被误报 EMPTY_RESPONSE 并触发熔断）
- 生产实测：第1章《验尸人》3756 字写完；CHAPTER_WRITING 20000→40000、
  CONTINUITY_CHECK 4096→8192 两次升级自动生效；向量索引生成（embedding 链路通）
- 本地 load-env/.zshrc 已带 embedding key+BASE_URL；yml 新增 DASHSCOPE_EMBEDDING_MODEL 占位符
- 新 backlog：NCS-186（e2e 回放被章节人审屏障暂停，1章后停写——A/B 实测为 main 既有问题）

## Files

- spec：docs/specs/2026-10-09-thinking-budget-filter.md
- main：47a92ed(embedding model 占位)/79bb12a(load-env BASE_URL)/bb62dde+9f1412b(NCS-185 登记+完成)
- 部署记忆：memory beijing-swas-deployment（swas-open 云助手路径/布局/重部署流程）

## Verification

- 全量 mvn test 1492/0F/0E/1S（NCS-185 后）；compare-baseline PASS；回放 0 LLM 错误
- 服务器探针 ok:true（原 EMPTY_RESPONSE）；第1章全程 GLM+embedding 真实链路

## Blockers

- 无（token-plan 套餐配额 10-10 00:00 重置，与北京服务器已无关）

## Next Steps

- backlog 排期：NCS-183/184 > NCS-186（e2e 屏障）> NCS-179/180 > NCS-177
- 观察点：GLM 每章 15-25 分钟（思考重）、CONTINUITY_CHECK 4096 档升级翻倍耗时时长；
  若吞吐不可接受可评估 thinking.type=low 档支持（需小改动）
- 《山河执棋人》50 万字：qwen 配额已重置，可续跑（18095 实例仍在）
