# 交接：旅程三修复批次 — 2026-09-30

Last Updated: 2026-09-30

## Current

phase 53 全部 11 项 completed 合入 main（active_feature=None）：
139/140/141/142/143/144/145/146/147/148/149/150/151。
main=1936911+；合并后验证 compare-baseline PASS + 全量 1443/0F/0E/1S。
18095 实例已换含 NCS-150 的新包重启（17:4x）。

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

- NCS-150：用户已确认 bless（golden 已在分支更新），但 replay 在第 2 章
  fixture miss——漂移=第2章第二次写作调用的质检反馈注入清单不同
  （record=桥段重复清单，replay=时代用语清单，重写链长不确定性），
  compare-baseline 硬零门 FAIL。需语料取证专项后重建语料才能合并。

## Next Steps

1. backlog：152（人物性格母题去重）/153（场景锚点约束）——各自独立 e2e 基线
2. 批准《剑冢守夜人》第 10 章续跑（UI 或 /tmp/approve_ch.py 10）
3. 经验：多 worktree/分支共用一个物理目录时，切分支后必须 mvn clean compile
   再跑 e2e（本轮 replay 假分叉即 target/classes 陈旧所致）
