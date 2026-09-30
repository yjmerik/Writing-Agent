# 交接：旅程三修复批次（NCS-139..153）— 2026-09-30

Last Updated: 2026-09-30

## Current

phase 53 已开批：NCS-139..153 登记完毕（not-started），按方案
docs/reports/2026-09-30-ui-journey-3/优化建议与方案.md 实施。
main=face356（报告+登记）。此前 NCS-096..137 全部 completed（见 progress.md 归档）。

## Files

- feature_list.json（NCS-139..153，phase 53）
- docs/reports/2026-09-30-ui-journey-3/（报告+方案）
- /tmp/approve_ch.py（旅程现场批准脚本，临时）

## 环境注意

- mvn test 必须 `bash -c 'source scripts/load-env.sh && mvn test ...'`，否则 Spring 上下文全错
- 实例 18095 运行旧包（journey 现场保留）：禁止在运行中重建 fat jar；
  全部完成后 stop → mvn package → 重启
- data/logs/events.jsonl 为多实例共享，按 projectId 过滤

## Verification

- 尚无新 feature 完成；每项完成时在此登记分支/提交/验证

## Blockers

- ncs-070 历史条目 FAIL + --bless 待人工确认（上批遗留，与本批无关）

## Next Steps

1. NCS-139 统一长任务轮询端点（LongTaskController）
2. NCS-141 锁 TTL/启动清扫 → NCS-140 后处理重试上限 → NCS-142 模型自检
3. Phase2: 143 带簿放行 / 144 定向修卡 / 145 冷却重试 / 146 批准自恢复
4. Phase3: 147/148/149/150（大纲自由规划，用户已决策）/151（卷边界呈现）/152/153
