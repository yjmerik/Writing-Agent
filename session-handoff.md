# 原方案补实施 — 2026-09-20

Last Updated: 2026-09-20

## Current

NCS-031/032已合本地main且verified。唯一active_feature=NCS-033，in-progress。
NCS-033业务实现4cb8f7ccd9759de37f8598fae8f663dc69a91f22已合main；分支codex/ncs-033-intent-writing。
原方案整体未完成；NCS-034..058待逐项执行，不能跳过当前质量验收闭环。

## Files

- ContextAssembler.java / MemoryConfig.java：生产写作上下文接入约定，不使用旧约定缓存。
- AuthorIntentWritingTest.java：保存接口→实际写作请求、更新版本、作品隔离、预算阻断。
- docs/harness/evidence/2026-09-20-ncs-033-pending.json：合入及干净main验证，明确待验收。
- docs/harness/evidence/ncs033/recording-scope.md：待授权录制的目的地、输入、规模和费用边界。
- docs/harness/evidence/ncs033/run-intent-e2e.py：固定百炼目的地和模型的非空约定验收启动器。

## Verification

基线1098/0F/0E/1S；RED 3项2失败；GREEN及相关回归15项通过。
分支及干净main init PASS：1101 tests / 0 failures / 0 errors / 1 skipped，编译、前端build通过。
分支及干净main旧replay PASS：3章9730字，0章节失败/LLM错误；baseline PASS。
日志/tmp/ncs033-final.log、/tmp/ncs033-main-init.log、/tmp/ncs033-main-replay.log、/tmp/ncs033-main-compare.log。

## Blockers

真实录制命令被自动审批拒绝：外发约定/正文及潜在费用缺明确授权。未启动record，未改golden。
已向用户请求仅本次3章样本授权：百炼Token Plan，qwen3.8-flash，隔离验收作品，无已有用户作品数据。
授权后仍须record、连续两次replay、新旧质量对比和人工确认，再bless；当前不得completed。
另已询问T12真实长篇费用上限与真人评阅安排，尚无答复，不混同于本次3章授权。

## Next Steps

1. 用户同意本次录制后，在隔离worktree source scripts/load-env.sh，使用Java21，E2E_FIXTURE_DIR=data/e2e-fixtures/mystery-20260920-intent，运行python3 docs/harness/evidence/ncs033/run-intent-e2e.py --mode record --port 18102。
2. 同一启动器双replay，展示新旧结果；用户质量确认后bless，再完成NCS-033证据与状态。
3. 之后从main新建NCS-034分支，接通构思/规划；不推送远端。
4. 隔离worktree /Users/erik/.codex/worktrees/ncs-completion/WritingAgent；干净main导出/tmp/ncs033-clean-main。
5. 主目录原有两个未跟踪路径保留。E2E服务已退出；录制未执行。旧样本位于本任务ncs033-quality/before.md。
