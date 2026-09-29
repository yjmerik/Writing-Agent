# 架构质量深化实现收尾（NCS-096..137 全部完成）— 2026-09-29

Last Updated: 2026-09-29

## Current

架构质量深化 42 项 feature（NCS-096..137，P0×13 + P1×20 + P2×9）全部实现、
合入 main 并 verified 登记；测试 1257→1399（净增 142），全程严格门禁
（BUILD SUCCESS 检查，一次 grep 掩盖回归事故后修正）。

收尾 e2e（真实 LLM record）暴露并修复三个收尾期缺陷：
1. 命名 provider 路径 180s 默认超时低于真实生成耗时（CHAPTER_WRITING 实测 312s）
   → 分层 request-timeout-seconds（write 900 / plan+worldbuild+extract 600 / check+general 600）
2. NCS-120 回收检测 prompt 嵌入随机 UUID 破坏 record→replay 指纹确定性
   → 稳定 F 标号（埋设章+描述排序派生），JSON 契约兼容 F 标号/真实 UUID
3. NCS-104 期合成验证脚本的 --bless 泄漏覆写真实 golden.json
   → 恢复 2026-09-22 生产 bless 值 + 设计的宽松风格上限

回放语料已重建：data/e2e-fixtures/mystery-20260929（¥0.2 量级/次），
连续两次 replay 完全一致（4592 字/风格指标全等）——确定性达标。

## Files

- 实现提交 42 份（codex/ncs-096..137-* 分支，全部 ff-only 合入 main）
- evidence：docs/harness/evidence/2026-09-29-ncs-09*.json … 137（42 份 verified）
- 语料：data/e2e-fixtures/mystery-20260929（record 第4次，冒烟通过）
- 基线：baselines/golden.json 恢复正确值

## Verification

- 干净 main 全量：mvn test 1399/0F/0E/1S 严格门禁
- e2e：record 冒烟通过（1 章 4592 字入账，第 1 章触发复验 UNRESOLVED
  人工复核屏障——fail-loud 设计行为，replay 确定性复现同一暂停点）
- compare-baseline：FAIL 于 章节数(1<2)/总字数(4592<5865)——结构差异源于
  新语料在第 1 章即停人工复核（旧语料 2 章）；风格维度全过
  （n-gram 0.0557<=0.10，cliche 0<=15）

## Blockers

- compare-baseline --bless 需人工确认：新语料第 1 章复验屏障是真实质量
  判断（伏笔提前回收但重写未通过逐项复验），按 DoD 不得自动 bless
- ncs-070 检查器 schema 遗留待用户裁决（沿用）

## Next Steps

1. 人工决策 A：认可新语料行为（第 1 章屏障为 fail-loud 正常形态）→
   python3 scripts/compare-baseline.py --bless 更新基线；或
   重录一次语料期望无屏障 3 章流（约 ¥0.2-0.5）
2. 人工决策 B：ncs-070 schema 遗留裁决
3. 18100 《沉钟记》人工 UI 验收仍待执行（沿用）
