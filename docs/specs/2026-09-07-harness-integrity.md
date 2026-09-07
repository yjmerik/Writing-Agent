# Spec: Harness 状态核验与持久交接

## 背景与动机

2026-09-07 用户要求检查特性、交接、完成记录并补齐缺失。审计发现原工作区226项均标completed，但main只有212项；14项Phase27–29实现未提交。8项早期记录缺少phase/dependencies/files，路径存在历史简称。progress.md有1190行，当前交接混入失效指令；近期完成证据仅在忽略的data/目录。

## 目标

1. 统一登记全部已知特性，明确已合并、历史证据有限、待恢复的本地实现。
2. 当前进度和交接简短可恢复，原记录完整归档，近期完成摘要随Git保存。
3. 提供离线检查，拒绝重复ID、依赖环、多活跃特性、虚假完成和缺失证据；接入init。

## 非目标

不合并Phase27–29业务实现，不改Agent、prompt、模型配置或用户数据；不补造无法恢复的历史提交/日志，不推送远端。

## 验收标准

- `python3 scripts/test_check_harness.py` 覆盖状态、依赖、证据及Git归属的负面用例并退出0。
- `python3 scripts/check-harness.py --check-git` 退出0；所有已完成新特性有可读证据，且实现/验证提交为main祖先。
- `./init.sh` 在隔离分支通过；合并后干净main编译、测试和专属验收通过。
- 原工作区非harness文件的SHA256与审计前一致，原状态文件逐字归档。

## 影响面

- 涉及模块：开发harness脚本及文档/状态；实现文件3个，必要spec/交接/归档另列。
- Prompt、数据库schema、质量基线影响：无。

## Feature 拆分

| id | name | 依赖 | acceptance | verify_cmd |
|----|------|------|------------|------------|
| p31-01 | Harness状态核验与持久交接 | [] | 负面用例、离线核验、编译和测试通过 | `python3 scripts/test_check_harness.py && python3 scripts/check-harness.py --check-git` |

## 风险与备选方案

历史证据缺失不能通过当前全量测试倒推补造；200项采用明确的historical完成记录。14项本地实现转deferred并保留原始证据，按依赖逐个恢复。状态修改影响脏文件，先备份与完整归档，合并时只处理本任务接管的三个状态文件。
