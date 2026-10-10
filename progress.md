# NCS-186 e2e 通道修复——代码完成，语料重建被配额阻塞 — 2026-10-10

Last Updated: 2026-10-10

## Current

- 上午完成 NCS-190/191（叙事策略事实源+约束统一，main 1512 全绿，见 ac15840 交接）
- 下午 NCS-186（e2e 回放修复）进行中，分支 codex/ncs-186-e2e-replay-fix（7ad9b46）：
  - 根因实证：**本地全部回放语料已过期**——prompt 演进（NCS-188 节拍裁剪等）使现行
    指纹与所有已录语料失配（mystery 最新语料仅存 2 个写作 fixture 且当日 record 即中断；
    wuxia 语料在 CHARACTER_DESIGN 阶段就失配）
  - e2e.sh 修复已实施并实证：质量屏障自动放行（正式 review-decisions 端点+幂等键+
    409 不强行绕过）+ PAUSED 续写回退；真实 record 运行中 ch1 屏障被自动放行、
    回退续写生效
  - **硬阻塞：qwen token-plan 月配额耗尽**（API 实测 insufficient_quota，
    重置 2026-11-09 16:00 UTC = 北京 11-10 零点）——语料重建需真实 LLM，本月本地不可行
- 待用户决策（三选一）：①部署当前分支 jar 到北京服务器用 GLM 录制（需部署授权，
  ~1-1.5h 服务器占用 + 约 ¥1 GLM 配额）；②提供本地可用 LLM key；③等 11-10 配额重置

## Files

- 分支：codex/ncs-186-e2e-replay-fix（scripts/e2e.sh + feature_list，未合并）
- main 最新：ac15840（NCS-190/191 已闭环）

## Verification

- e2e.sh bash -n 语法过；自动放行/回退在真实 record 运行中实证生效（data/e2e 留痕）
- main 全量 1512/0/0/1skip（ac15840 后未再变更 main）

## Blockers

- qwen 月配额（11-10 北京零点重置）；语料重建方案待用户决策（上述三选一）

## Next Steps

- 用户选定语料重建方案后：record → replay×2 → compare-baseline（bless 需人工确认）→
  合并 NCS-186 → 解锁 NCS-192（模板改版，验收依赖 e2e）
- 等待期可做不依赖 e2e 的 feature（但注意：凡改注入上下文的 feature 最终验收都需 e2e，
  建议攒到语料恢复后统一补验收）

__zcode_status=$?
if [ "$__zcode_status" -eq 0 ]; then pwd -P > '/var/folders/n1/0rdqrqbj0tvfcgjbgds9rmlc0000gn/T/zcode-fe59b5a1-4764-44c0-9593-dc5c9c10c85b-cwd'; fi
exit "$__zcode_status"