# Spec: E2E 质量暂停的验收与诊断

## 背景与动机

NCS-033 真实3章录制只产出1章2706字并因4项人工审查问题暂停，但scripts/e2e.sh返回0并显示通过；diagnose.sh虽然列出质量问题，仍建议“系统运行正常，无需修复动作”。证据：docs/harness/evidence/ncs033/recording-verification.json、record-diagnosis.txt、record-result.json。两次replay稳定复现。

## 目标

严格区分调用成功、流程完成与质量通过。完整验收必须达到请求章数且无未解决的人审拦截；诊断必须明确质量暂停并给出复核动作。

## 非目标

不降低质量门槛、不自动放行人审、不修改小说prompt或重建golden。不在NCS-033中夹带实施。

## 验收标准

- [ ] NCS-059：先以本次1/3章PAUSED报告建立失败测试；严格模式非零退出；3/3章完成且无待审则通过；明确命名的smoke模式可仅验证管道，输出不可冒充质量通过。运行`python3 scripts/test_e2e_acceptance.py`。
- [ ] NCS-060：0 LLM错误但存在CONSISTENCY_ISSUE / REVIEW_BARRIER_RAISED / WRITING_PAUSED时，诊断明确质量阻断与人工复核动作，不输出“无需修复动作”；正常样本保留正确结论。运行`python3 scripts/test_diagnose_quality.py`。

## 影响面

- 涉及模块：scripts/e2e.sh、诊断脚本及对应离线测试。
- 涉及prompt模板：无。
- 数据库schema变更：无。
- 质量基线影响：提高验收准确性，不bless退化结果。

## Feature 拆分

| id | name | 依赖 | acceptance | verify_cmd |
|----|------|------|------------|------------|
| NCS-059 | E2E完整验收与冒烟分离 | [] | 1/3章PAUSED严格验收失败、完整样本通过 | `python3 scripts/test_e2e_acceptance.py` |
| NCS-060 | 人工审查暂停诊断 | [] | 质量阻断不得输出无需修复 | `python3 scripts/test_diagnose_quality.py` |

## 风险与备选方案

历史允许PAUSED的管道冒烟用途需要显式保留，不能无意破坏已有回放；严格模式必须成为宣告质量验收完成的依据。每项1–3实现/测试文件，独立特性分支与合并验证。
