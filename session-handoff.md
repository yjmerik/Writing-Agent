# 交接：NCS-186 e2e 通道——修复完成、语料重建待决策 — 2026-10-10

Last Updated: 2026-10-10

## Current

上午 NCS-190/191 已合入 main（ac15840 交接）。下午推进 NCS-186，**代码修复完成但语料
重建被硬阻塞**，保持 in-progress（分支 codex/ncs-186-e2e-replay-fix，7ad9b46）：

- **根因实证（非最初假设的人审屏障单一问题）**：本地全部回放语料过期——
  mystery-20261002 仅存 2 个 CHAPTER_WRITING fixture（当日 record 中断遗留），
  现行 ch1 指纹 b1e79c68… 与之失配（NCS-188 节拍裁剪等 prompt 演进）；wuxia 语料
  CHARACTER_DESIGN 阶段即失配。即 e2e 通道自 NCS-188 合并起对全部语料失效。
- **e2e.sh 修复（7ad9b46）**：①PAUSED 时经正式 `review-decisions` 端点自动放行
  （identity/reviewSetHash/idempotencyKey 协议与 UI 一致，409 不绕过）；②无可放行且
  未写满时回退重发 write-all。真实 record 运行实证两者生效（ch1 屏障自动放行→续写）。
- **硬阻塞**：qwen token-plan **月配额耗尽**（API 直测 insufficient_quota，重置
  2026-11-09 16:00 UTC=北京 11-10 零点）。上午首次 record 消耗完本周期配额
  （构思+ch1 全链后 QUOTA）。

## Files

- 分支：codex/ncs-186-e2e-replay-fix（scripts/e2e.sh 自动放行+回退；feature_list
  evidence 含完整根因链；progress/handoff 本文件）——**续做时复用此分支**
- main：ac15840（未动）；data/e2e-fixtures/mystery-20261010 半成品已清理

## Verification

- 自动放行/回退：真实 record 运行实证（data/e2e/server.log + events.jsonl 留痕：
  APPROVE 后第二任务启动）
- 语料重建未做（配额）；replay×2/compare-baseline 待语料后执行

## Blockers

- 语料重建三选一待用户决策：①北京服务器 GLM 录制（需部署授权：当前服务器 jar=b5e9810
  落后 main 多个 prompt 改动，必须先部署当前分支 jar，否则指纹不匹配；服务器 SSH 直连
  可用，重部署流程见记忆 beijing-swas-deployment）②提供本地 LLM key ③等 11-10 重置

## Next Steps

1. 用户决策后：record（E2E_FIXTURE_DIR=data/e2e-fixtures/mystery-20261010）→ replay×2
   一致 → compare-baseline 展示对比（**bless 需人工确认**）→ 合并 NCS-186 → 回执
2. NCS-192（模板改版）依赖 e2e 验收通道，排在 NCS-186 之后
3. 注意：本月内凡改注入上下文的 feature（NCS-192/194/197/198/199）最终 e2e 验收都会
   撞同一堵墙——建议集中到语料恢复后统一补验收，或随做随 A/B 指纹留证
