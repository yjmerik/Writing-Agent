# 原方案补实施 — 2026-09-20

Last Updated: 2026-09-20

## Current

NCS-031/032已完成且verified；唯一active_feature=NCS-033，in-progress。
分支codex/ncs-033-intent-writing，约定接入4cb8f7c、纠偏原稿修复4c26c55已合本地main。
用户允许后真实录制确认完整原稿/版本及8字段已注入；另发现首轮守卫问题被重复扫描覆盖。
本轮主动中断以避免对已知缺陷继续消耗：27条已完成调用、1章4470字；首章后处理未完成，任务计数0，目标3章未达。
漏传修复只复用首轮报告，保留纠偏后新稿复检；分支完整验证通过，准备合入。
NCS-034..060未启动，未放行人审、未bless。

## Files

- src/main/java/com/writing/novel/orchestrator/ChapterProduction.java：保留首轮守卫报告。
- src/test/java/com/writing/novel/orchestrator/GuardFindingRetentionTest.java：真实编排/SQLite行为回归。
- docs/harness/evidence/ncs033/guard-retention-validation.json：RED/GREEN及合并证据。
- docs/harness/evidence/ncs033/correction-recording-verification.json：本轮27语料指纹、原稿/约定注入。
- docs/harness/evidence/ncs033/correction-record-result.json：中断快照，非完整E2E报告。
- docs/harness/evidence/ncs033/correction-quality-review.md：质量结论和全文链接。

## Verification

原稿修复分支与干净main init 1104/0F/0E/1S已通过。
本次漏传测试RED 1项1失败0错误；相关GREEN 10项通过；分支init 1105/0F/0E/1S，编译和前端build通过。
本次真实录制约63分钟；27次调用无最终错误，初次写作有一次EOF重试。
录制因主动停止退出56，遗留e2e-report.json不能作为本轮结果；未运行本轮双replay。
/tmp/ncs033-clean-main当前含候选修复，不再是干净导出；合并后使用新的独立main导出复验。

## Blockers

BROKEN（质量验收/匹配语料）：新代码尚无完整匹配录制，不能宣称最终3章或模型语义问题通过。
本次单轮录制授权已使用；新一轮真实调用需另外明确授权，尚未启动。
人工质量确认仍是bless前的独立门禁。T12真实长篇费用和真人评阅安排也未确认。
NCS-059/060验收/诊断误报只登记，不借当前特性顺带实现。

## Next Steps

1. 完成漏传修复的分支验证、提交、本地main合并和新的干净main复验，状态保持in-progress。
2. 最终代码需要新语料录制、两次一致replay、新旧质量比较；人工质量确认后才能bless及完成NCS-033。
3. 保留两轮失败/中断样本；不重采样刷过，不降低门槛。
4. worktree=/Users/erik/.codex/worktrees/ncs-completion/WritingAgent；测试服务已关闭。
5. 原主目录两个未跟踪路径保留；无远端推送；完整小说正文不进入Git。
