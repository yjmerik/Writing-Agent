# 交接：phase-54 review 整改批次 — 2026-09-30

Last Updated: 2026-09-30

## Current

phase 54 全部 11 项 completed 合入 main（active_feature=None），spec 见
docs/specs/2026-09-30-phase53-review-remediation.md。行为性修复：
NCS-154 人物卡修订改为预览-确认链（确认前不落库，confirm/discard 端点挂
CheckpointController）；157 冷却耗尽终态 FAILED_RETRYABLE + retry 端点 +
ReaderReviewCard 重试横幅；162 模型库保存探测门禁（force 逃生门）；
156 网关类型化 LlmProviderUnavailableException（LongTaskService 不再匹配消息文本）。
卫生类：158 查询族单 SQL、159 静默 catch 记日志、160 LlmProbe 契约 + merger、
161 桥段章号整数比较。164 登记：10 处 verify_cmd 修正、13 条台账补建、
ncs-070 记录修复、scripts/check-verify-cmds.py 防再发。

## 环境注意

- mvn test 必须 `bash -c 'source scripts/load-env.sh && mvn test ...'`
- 切分支后先 mvn clean compile 再跑 e2e（target/classes 陈旧 = replay 假分叉）
- e2e 语料 mystery-20260930 为 golden 配套；replay 的 acceptance-full 屏障失败
  是语料稳态（golden minChapters=1），判分以 compare-baseline 为准
- 18095 实例仍是 phase-53 旧包（预览-确认链等未部署）；《剑冢守夜人》停第 10 章
- 多 feature 共用 data/e2e 目录：e2e.sh 每次自清（rm -rf data/e2e），无残留风险

## Verification

- 全量 1462/0F/0E/1S（main=81885b9 干净检出）；npm build 过
- check-harness PASS / check-verify-cmds 285 引用全过
- 154/161 e2e 对照干净 main：产出逐字节一致，零分叉，语料未重建

## Blockers

- 无

## Next Steps

1. NCS-152/153（O-2/O-3 prompt 项）：独立分支 + e2e record/对比/bless
2. 155/163 实机走查补录（下次真实 checkpoint 修订 / 批准被拒场景）
3. 18095 重载新包前须先批准/暂停当前写作（禁止运行中重建 jar）
4. 经验沉淀：登记时 verify_cmd 先跑 `python3 scripts/check-verify-cmds.py`
   再标 completed；完成记录必须当场写 completions.json（phase-53 漏写教训）
