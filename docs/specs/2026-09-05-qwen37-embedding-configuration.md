# Spec: 配置 Qwen3.7 文本向量模型

## 背景与动机

用户指定百炼北京区域 qwen3.7-text-embedding 模型。前次真实界面体验在向量调用时因专用凭据为空报 HTTP 401；本机通用 DASHSCOPE_API_KEY 已配置。证据见 docs/reports/2026-09-05-ui-experience/14-retry-status.txt。

## 目标

1. 默认向量模型切换为 qwen3.7-text-embedding，沿用 OpenAI 兼容协议与模型默认 1024 维。
2. 优先读取 DASHSCOPE_EMBEDDING_API_KEY，未设置时回退到 DASHSCOPE_API_KEY；启动脚本遇通用密钥缺失或为套餐密钥时，复用本机百炼CLI配置中已有的按量付费密钥。密钥不写入仓库、不输出。
3. 使用新模型独立索引文件，保留旧索引；重启独立体验实例并通过页面恢复生成验证向量调用不再被 401 阻断。

## 非目标

- 不改变 Agent、Prompt、编排和质量策略；不直接调用业务 API 代替界面体验。
- 不迁移或删除既有旧模型索引；旧项目完整重建索引不属于本次配置变更。
- 不为获取业务空间 ID 新建云资源或更改云账号权限。

## 文档依据

- 用户模型页：https://bailian.console.aliyun.com/cn-beijing?tab=model#/model-market/detail/qwen3.7-text-embedding
- https://help.aliyun.com/zh/model-studio/text-embedding-synchronous-api ：模型默认1024维、单批最多20条、float向量。
- https://help.aliyun.com/zh/model-studio/base-url ：北京现有共享域名 https://dashscope.aliyuncs.com/compatible-mode/v1 继续受支持；业务空间专属域名可通过环境变量覆盖。

## 验收标准

- [x] `mvn test -Dtest=EmbeddingModelConfigurationTest` 通过：官方模型名、受支持北京地址、专用/通用凭据优先级及独立索引配置均正确。
- [x] `mvn compile && mvn test` 通过，前端构建与打包成功。
- [x] 独立体验实例保留项目数据重启；通过页面继续协作，确认不再因向量401停在首个世界观条目。

## 影响面

- 模块：application.yml、scripts/load-env.sh 与配置契约测试；不修改 Java 业务实现。
- Prompt：无；数据库 schema：无。
- 新索引默认 `data/vectors-qwen3.7-text-embedding-1024.idx`，旧 `data/vectors.idx` 保留。若已有旧项目切换新索引，其历史向量需单独重建才能完整召回，不能混用同为1024维但模型不同的向量。
- 文学质量基线：不改Agent/Prompt/上下文逻辑；原回放使用stub，不能证明新向量模型召回质量。以真实界面生成验证运行兼容，长篇质量另行评估，不bless。

## Feature 拆分

| id | name | 依赖 | acceptance | verify_cmd |
|---|---|---|---|---|
| p28-01 | Qwen3.7向量配置与标准凭据回退 | [] | 配置契约测试通过、完整测试通过、真实界面不再报向量401 | `mvn test -Dtest=EmbeddingModelConfigurationTest` |

## 风险与备选方案

通用API key需与北京按量付费接口配套；若专用key已设置但无效，不自动降级到其他key。凭据未设置与设置为空是不同状态，显式空值不覆盖。保留原索引与数据库，可恢复旧模型配置后继续使用旧索引。

本机确认：DASHSCOPE_API_KEY 和 ALIYUN_LLM_API_KEY 都是 sk-sp- 套餐密钥，不能调用当前按量接口；`~/.bailian/config.json` 已有按量付费key，实测qwen3.7-text-embedding返回200与1024维。启动时在 bash 中先 `source scripts/load-env.sh`；不把密钥作为命令行参数或提交到仓库。CLI配置缺失、损坏或仍为套餐密钥时不自动导入；不替换已显式设置的专用密钥或普通通用密钥。

## 完成证据（2026-09-05）

配置测试4/4，最终init 619 tests/0 failures/0 errors/1 skipped，build/package PASS。真实调用qwen3.7-text-embedding返回200、2条1024维有效向量。加载正确本机凭据重启后，从ego-browser界面继续协作，世界观由3条增至10条，任务继续运行且无向量401。证据：data/verification/qwen37-embedding/。此项完成代表向量配置与运行兼容通过，不代表200章小说完成。
