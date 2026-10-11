# Spec: 质感重写输出预算标定（CHAPTER_WRITING 20k→32k）

## 背景与动机

2026-10-11 HISTORICAL 预设 1 章录制（质感链路端到端实战验证）发现：TextureGate 正确拦截
（TEXTURE 对白乒乓 + PACING 场景数超区间）并按设计路由整章重写，但重写调用
`LLM_TRUNCATED`（deepseek-v4-flash 输出在 20000 max-tokens 顶格）→
`rewriteForCorrection` 拒绝截断稿 → 内容未替换 → 质感问题滞留终稿（MEDIUM 非阻断）。
replay×2 确定性复现同链。初写同参未截断——纠偏上下文（原稿+问题清单+场景规划注入）
放大了 reasoning 消耗。

与 NCS-186 的 EXTRACTION 16k→32k 同型：推理模型的输出预算标定问题。

## 目标

1. CHAPTER_WRITING 路由 max-tokens 20000→32000：质感重写在同场景下不再截断，
   重写稿完整落库（LLM_TRUNCATED 消失）
2. 输入预算联动收缩（budgetForModel=窗口-输出-5%）不改变既有语料的装配结果
   （各 section cap 不 binding）

## 非目标

- 不改写重写触发逻辑/门禁阈值
- 不做截断后自动升档重试（NCS-185 升级链已覆盖 THINKING_BUDGET；截断属预算标定）
- 不调其他路由预算

## 验收标准

1. `mvn test -Dtest=TokenPlanModelConfigurationTest` 断言 32000 通过
2. HISTORICAL 预设 1 章录制：TEXTURE_GATE 拦截后的重写稿完整落库（无 LLM_TRUNCATED）
3. replay×2 --chapters 1 一致且 acceptancePassed=true；compare-baseline PASS
