# 原方案补实施 — 2026-09-20

Last Updated: 2026-09-20

## Current

NCS-031/032已完成且verified。唯一active_feature=NCS-033，in-progress。
用户要求先修复真实样本问题；纠偏修复4c26c5524a9cd25d9c068a133fd6a6f3efbbf572已合本地main。
分支codex/ncs-033-intent-writing，初始约定接入提交4cb8f7ccd9759de37f8598fae8f663dc69a91f22。
纠偏现在携带当前完整原稿及版本；缺稿/预算不足时拒绝模型请求。
外置规则区分当前能力与潜力、已知与猜测、道具交接、语言样本和故事事实；允许修正违规的计划动作。
尚不能断言4项模型语义问题都已消失：修复后真实验收未运行。NCS-034..060未启动。

## Files

- src/main/java/com/writing/novel/agent/ChapterWriterAgent.java：纠偏原稿/版本/预算。
- src/main/resources/prompts/chapter-correction.st：有边界的纠偏要求，旧模板变量未改。
- src/test/java/com/writing/novel/service/AuthorIntentWritingTest.java：新增3项行为回归。
- docs/harness/evidence/ncs033/correction-validation.json：RED/GREEN、主分支验证与阻塞证据。
- docs/harness/evidence/ncs033/correction-recording-scope.md：再次录制的具体范围与审批状态。
- docs/harness/evidence/ncs033/quality-review.md：修复前真实样本和4项人审问题。

## Verification

本轮基线init PASS（1101项）；新增RED 6项3失败0错误；相关GREEN 15项通过。
修复分支与独立main导出init PASS：1104 tests / 0 failures / 0 errors / 1 skipped，编译/build通过。
修复后旧非空语料在CHAPTER_WRITING-e079faeab2f39ac1-1.json处分叉，最终E2E FAIL（预期不兼容）。
日志/tmp/ncs033-correction-{red,green,init,main-init,old-replay}.log；harness --check-git PASS。
修复前record1章2706字、27调用0错误，双replay一致，但4项人审屏障且baseline FAIL。
所有旧失败语料保留，未降低检查门槛、未更新golden、未手工放行人审。

## Blockers

BROKEN（E2E语料兼容性）：新纠偏prompt需要匹配录制，旧回放不能代表修复后验收；编译/单测绿。
修复后真实record自动审批拒绝：上一轮授权未明确覆盖新增完整原稿/问题描述与再次支出。命令未启动。
已向用户请求同百炼Token Plan/qwen3.8-flash、独立测试作品、一次3章目标授权；可能再耗额度/API费用。
等待授权，不得通过其他入口绕过拒绝。人工质量确认仍是bless前的独立门禁。
T12真实长篇费用上限与真人评阅安排仍未确认。

## Next Steps

1. 若获本次录制授权，在隔离worktree使用Java21，source scripts/load-env.sh后执行下项；否则保留本地修复。
2. E2E_FIXTURE_DIR=data/e2e-fixtures/ncs033/mystery-20260920-correction python3 docs/harness/evidence/ncs033/run-intent-e2e.py --mode record --chapters 3 --port 18102。
3. 固定目的地/模型已由启动器锁定；遇人审暂停不自动放行，不重复采样刷过；记录新旧语义差异并双replay。
4. 质量达标且人工确认后才能bless并完成NCS-033；NCS-059/060验收诊断误报仍只登记。
5. worktree=/Users/erik/.codex/worktrees/ncs-completion/WritingAgent；干净main导出=/tmp/ncs033-clean-main。
6. 原主目录两个未跟踪路径保留，E2E服务已退出。完整小说正文不进入Git证据。
