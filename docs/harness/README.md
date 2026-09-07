# 开发 Harness 导航

[本次审计报告](audits/2026-09-07.md)列出发现、修复及结构评分。

## 当前事实与历史证据

- [feature_list.json](../../feature_list.json)：唯一特性状态源；`active_feature`为当前唯一开发项或null。
- [progress.md](../../progress.md)：本轮完成与下一步，最多150行。
- [session-handoff.md](../../session-handoff.md)：恢复入口、涉及文件、验证、阻塞项，最多150行。
- [completions.json](completions.json)：完成索引。feature的`completion_record`对应记录ID；近期记录链接Git内的验证摘要，原始本地日志只作补充。
- [审计前进度](archive/2026-09-07-progress.md)、[审计前交接](archive/2026-09-07-session-handoff.md)、[审计前特性快照](archive/2026-09-07-feature_list.json)：逐字归档，仅供追溯。历史日期、完成数量、待批准提示和操作指令不是当前状态。

## 状态语义

| status | 含义 | 下一步 |
|---|---|---|
| not-started | 已登记、尚未实现 | 依赖完成后，从main新建独立特性分支 |
| in-progress | 当前唯一正在实现/待合并验证的特性 | 复用对应分支完成验证与合并 |
| deferred | 已有工作暂停、尚不能登记为完成 | 阅读deferred_reason；按依赖选择一项，从main新分支恢复该项 |
| completed | 已交付；查看completion_record判定证据等级 | 新工作另开分支 |

14项Phase27–29采用deferred并标注`delivery_state: local-unmerged`：业务内容仍在原工作区，过去的测试不等于主分支验证。恢复时把该项改in-progress并设置active_feature；其余项保持deferred。合并前清除local-unmerged标记，保留历史证据。

## 完成证据格式

`verified`记录包含feature_ids、branch、implementation_commit、main_commit和evidence路径。验证摘要必须含实际验证的`commit`、`cleanCheckout: true`和非空`checks`（command、result、exitCode）。完成后先确认实现是main祖先，再提交状态回执；回执提交与已验证实现提交分开记录，避免自引用哈希。

`historical`只适用于本次审计迁移的Phase0–26：原始evidence保留，但逐项分支/合并/日志不能完整追溯。本次未补造证明，未把一次全量回归当成所有历史专项验收。Phase31起必须使用verified。早期未记录的dependencies/files暂填空数组并加audit_notes；不能将其解释为已证明没有依赖/文件变化。

files已尽量统一为仓库相对路径；历史丢失信息在审计快照中保留。未实现特性的目标文件允许暂不存在。deferred所引用的三份spec本次补入Git，业务源码和UI体验材料仍留原工作区。

## 检查与维护

```bash
python3 scripts/check-harness.py              # 离线结构/状态/依赖/记录/入口检查
python3 scripts/check-harness.py --check-git  # 另核对完成提交在本地main中的祖先关系
python3 scripts/test_check_harness.py         # 故意损坏状态的回归用例
./init.sh                                   # 先核验harness，再编译、测试、前端构建
```

检查器不会执行feature中的verify_cmd，不联网、不改文件。Git归属检查显式启用，供合并前/后和交接使用；浅克隆应先取足main历史，不将缺少历史解释为验证通过。历史证据有限会产生WARN，不冒充已补齐逐项历史证据。

结束会话时：更新feature_list.updated_at和两个入口日期；把旧当前状态移到archive，再写本轮入口。验证摘要提交到docs/harness/evidence，敏感凭据、小说正文、完整应用日志不放入记录。当前入口不复制长历史，也不靠静态测试总数定义完成。
