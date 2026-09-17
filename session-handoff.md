# 协作复验后续修复 — 2026-09-18

Last Updated: 2026-09-18

## Current

p41-01～09 已合并 main；当前唯一进行中项 p41-10，分支 codex/p41-10-stale-candidate-conflict。
用户已要求继续修复。过期候选专用异常映射已实现，全量及浏览器通过，待提交合并与主分支复验。
工作目录 /tmp/writing-agent-audit3-design；主工作区原有两处未跟踪内容保留。

## Files

- docs/specs/2026-09-18-stale-candidate-http-status.md
- src/main/java/com/writing/novel/web/GlobalExceptionHandler.java
- src/test/java/com/writing/novel/web/StaleRevisionCandidateApiTest.java
- qa/collaboration/revision-instruction-draft.spec.ts
- 上次状态已归档 docs/harness/archive/2026-09-18-pre-p41-10-*.md

## Verification

基线 ./init.sh 通过：931 tests，0 failures，0 errors，1 skipped；前端构建通过。
新增真实 API 测试修复前4项中2项失败（CHAPTER/PASSAGE期望409实际500）；修复后4项通过。
专项命令：mvn test -Dtest=StaleRevisionCandidateApiTest。
分支全量编译测试通过：935 tests，0 failures，0 errors，1 skipped（/tmp/p41-10-full.log）。
浏览器9项回归通过，含真实来源修改后409、冲突提示、新稿版本不变及草稿保留。
日志 /tmp/p41-10-qa-branch.log；尚未登记完成。

## Blockers

无本项实施阻塞。p40-05/p40-06仍deferred：真实文学实验缺严重连续性错误计数。
两题材回放分别在第2/1章等待人工复核，整体验收仍INCOMPLETE。

## Next Steps

完成全量及浏览器检查，提交并合入main，干净检出复验后登记p41-10完成。
不绕过人工复核，不以软件回归通过代替完整文学实验验收。
