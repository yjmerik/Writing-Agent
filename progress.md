# 叙事质感架构 Phase2 前两步（NCS-190/191） — 2026-10-10

Last Updated: 2026-10-10

## Current

- NCS-190（7a85ccd，completed）：NarrativePolicy 叙事策略域对象——17 个区间字段，
  题材默认两档（HISTORICAL 质感区间 vs 其余现状等效宽松），项目级覆盖 SQLite
  持久化（narrative_policy_overrides 表）+ REST GET/PUT/DELETE（非法值 400）
- NCS-191（bd135e2，completed）：约束统一读 policy——HISTORICAL 题材的
  writingConstraints/章长指导/题材节奏规则改区间双向文案（对话段 30%-55%、
  场景预算制解除"全覆盖 vs 不超N"矛盾、转场/定场/成段叙述指令、解除
  "超字数先砍描写"与"禁止铺陈开场"）；**非质感题材保持现状文案字节等价**——
  A/B 实测干净 main 与本分支 CHAPTER_WRITING promptFingerprint 完全一致（799687fd7325），零分叉
- 装配：MemoryConfig/AgentConfig 注入 NarrativePolicyService（late-set，宽松策略自动回退现状）

## Files

- main 链：7a85ccd(NCS-190) → e44cc76(回执) → bd135e2(NCS-191) → 5c6145e(回执)
- 新增：domain/NarrativePolicy、persistence/sqlite/NarrativePolicyRepository、
  service/NarrativePolicyService、web/NarrativePolicyController、
  NarrativePolicyTest/NarrativePolicyApiTest

## Verification

- 每 feature TDD 先红后绿；分支与合并后 main 全量均绿
- 最终 main：1512 tests / 0 failures / 0 errors / 1 skipped（既有）
- e2e/compare-baseline FAIL 为 main 既有问题（NCS-186：回放 fixture 缺失，记录时 0 次）——
  A/B 干净 main 同签名同指纹失败，与本轮无关；修复归 NCS-186
- check-harness PASS（531 features：487 completed / 33 not-started / 11 deferred）

## Blockers

- 无

## Next Steps

- NCS-192（模板节奏改版）依赖 NCS-190 已满足；触碰 .st → p32-15 需 record 重建
  fixture（注意：NCS-186 修复后 e2e 才能作为验收通道，建议先修 NCS-186）
- 之后 NCS-193/194（ScenePlan 层）、NCS-195/196（TextureGate+扩写）
- 既有 backlog：NCS-186（e2e 屏障，已两次被 A/B 证实）> NCS-183/184 > NCS-179/180/177
