# 共创方案界面验收未通过 — 2026-09-12

Last Updated: 2026-09-12

## Current

Phase33 的 8 个实现切片保留原完成记录，不能视为原设计 P0–P4 全部交付。本轮按用户要求启动最新服务并通过 ego-browser 验收；业务代码未改。Phase34 新登记 6 项 bug，均 not-started，active_feature=null；共 256 项，250 completed。

## Files

- 验收报告：docs/reports/2026-09-12-collaboration-ui-acceptance.md。
- 回流 spec：docs/specs/2026-09-12-collaboration-ui-acceptance-followups.md。
- 当前状态源：feature_list.json；原 Phase33 当前入口归档至 docs/harness/archive/2026-09-12-before-ui-acceptance-*。
- 本轮文档分支：codex/docs-collaboration-ui-acceptance-20260912；验收实现基准 main 4437b5a；文档提交 facd33d 已快进合入 main，合并后 compile/test 再次通过（727/0失败/0错误/1跳过）。

## Verification

./init.sh 本轮通过：compile、727 tests / 0 failures / 0 errors / 1 skipped、frontend build。界面验证：原样保存幂等、编辑及候选应用增稿、版本比较、初始设定批准、人审屏障/待办直达、处理后续写到第2章停点并刷新保留。明确失败与未验证范围见报告。

## Blockers

验收不绿：空标签启动 NPE、草稿刷新丢失、旧报告冒用当前、新稿与旧屏障/批准错位。意见落实、事实时间线、24→20 与成书版本/导出仍缺界面。截图工具不可用；真实改写外发审批待用户答复，仅本地确定性写入流程已测。未宣称业务修复完成。

## Next Steps

从 main 选择 p34-01 等单一待修项，先 failing test；更大缺口继续按原方案拆分。8081 为最新代码保留作品副本，8082 为本地场景副本；原 8080 旧服务保留，不能作为最新验收入口。保留用户原有两项未跟踪文件，不推送远端。
