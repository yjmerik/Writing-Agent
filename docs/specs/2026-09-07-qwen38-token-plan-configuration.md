# Spec: 百炼 Token Plan Qwen3.8 Flash 配置与续测

## 背景与动机

原纯UI评测在20章/66880字时遇写作模型HTTP 402余额不足。用户明确要求参考本机ZCode百炼Token Plan配置，切换qwen3.8-flash，验证后继续原200章全流程。

## 目标

- 默认及全部8类LLM任务使用Token Plan北京OpenAI兼容地址和qwen3.8-flash。
- 专用环境变量优先，缺失时只读复用本机ZCode匹配Token Plan的既有密钥；不回显或把密钥写入仓库。
- 打包并仅重启18098评测实例，保留同一SQLite、向量索引和作者稿，从21章继续纯UI验证。

## 非目标

不修复此前体验缺陷，不改Agent、prompt或数据库；不修改ZCode配置，不充值，不使用业务API推进小说。向量模型及其按量凭据保持原配置。保留64000上下文预算及现有输出上限，避免把换模型与上下文扩张混在一起；现有费用估算不是套餐Credits账单。

## 验收标准

- `mvn test -Dtest=TokenPlanModelConfigurationTest`：全部路由及缺密钥隔离、环境覆盖、本地模拟流式请求通过。
- `./init.sh`通过，`mvn package -DskipTests`通过；启动加载只输出凭据是否存在。
- ego-browser在原项目继续后观察到第21章正文，原1—20章与首卷计划保留；遇新故障保留实际UI证据。

## 影响面

模型配置、启动凭据加载、配置契约测试。无prompt/schema变更，不更新质量golden；真实质量在后续UI报告单独评价。

## Feature 拆分

| id | name | 依赖 | acceptance | verify_cmd |
|---|---|---|---|---|
| p29-01 | Token Plan Qwen3.8 Flash路由与ZCode凭据加载 | [] | 配置测试及完整init通过，原项目UI越过第21章生成 | `mvn test -Dtest=TokenPlanModelConfigurationTest` |

## 配置依据与风险

本机`~/.zcode/v2/config.json`的Qwen Token Plan条目已有指定模型，使用Anthropic协议。本系统客户端走OpenAI兼容协议，使用官方对应地址：
https://help.aliyun.com/zh/model-studio/token-plan-personal-quick-start

Token Plan、Coding Plan与通用按量密钥不可混用。仅接受匹配Token Plan主机的ZCode条目；多个不同匹配密钥时不自动挑选。恢复旧服务需明确指定旧模型路由，不能把旧DeepSeek环境变量当成Token Plan凭据。
