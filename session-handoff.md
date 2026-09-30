# 交接：旅程三修复批次 — 2026-09-30

Last Updated: 2026-09-30

## Current

NCS-139/140/141/142/143/145/146/148/149/151 共 10 项 completed 并合入 main
（各项 evidence 见 feature_list.json，分支/提交/全量计数齐全）。
NCS-150 in-progress（分支 codex/ncs-150-outline-free-planning，90c7728）：
代码+prompt 完成，e2e 确定性达标，仅剩人工 --bless 后 ff 合并。
active_feature=NCS-150。

## 环境注意

- mvn test 必须 `bash -c 'source scripts/load-env.sh && mvn test ...'`
- 18095 实例运行新包（16:06 构建，去代理）：《剑冢守夜人》停在第 10 章检查点
  （批准可用协作栏或 API）；禁止运行中重建 jar
- data/logs/events.jsonl 多实例共享，按 projectId 过滤
- e2e 语料：mystery-20260930 为 NCS-150 新 prompt 语料；旧 mystery-20260929
  与旧 prompt 配套，勿混用

## Verification

- 全量 mvn test 1435/0F/0E/1S（main）
- 分支 codex/ncs-150：全量绿 + replay×2 指纹一致 + compare-baseline 差异呈报

## Blockers

- NCS-150 bless 待人工（--bless 后 ff 合并、注册 completed、bump phase 收尾）

## Next Steps

1. 人工确认 compare-baseline 差异 → `python3 scripts/compare-baseline.py --bless`
   → 合并 codex/ncs-150-outline-free-planning → 登记 NCS-150 completed
2. backlog：144（框架意见点名修卡）/147（偏好竞态，先复现）
3. 可选：152/153（prompt 类，各自独立 e2e 基线）
4. 批准《剑冢守夜人》第 10 章续跑（UI 或 /tmp/approve_ch.py 10）
