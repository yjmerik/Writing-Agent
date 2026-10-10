# 交接：NCS-190/191 完成——叙事策略事实源与约束统一 — 2026-10-10

Last Updated: 2026-10-10

## Current

叙事质感架构（docs/specs/2026-10-09-narrative-quality-architecture.md）Phase 2 前两步完成，
Phase 1（NCS-187..189）已于昨日合入。设计：测量(NCS-187 遥测)→设定点(NCS-190 policy)→
指令(NCS-191 约束统一) 已就绪，待 ScenePlan(NCS-193/194)、TextureGate(NCS-195/196)、
卷级闭环(NCS-200)。

- **NCS-190**（7a85ccd）：NarrativePolicy 17 字段区间（对话段/引号字符占比区间、成段叙述
  最短块、按章型节拍区间、每节拍字数、转场最短字数、场景数、定场许可、感官锚点）。
  forGenre 两档：HISTORICAL 质感默认（0.55 对话段上限/节拍 2-3/转场 80/叙述块 120），
  其余题材现状等效宽松（节拍上限镜像 GenreRuleProvider：MYSTERY/THRILLER=3）。
  NarrativePolicyRepository（narrative_policy_overrides 单行 JSON/项目）+ Service
  （resolve=默认+覆盖；save 合并校验：比例 [0,1]、min≤max、非负）+ REST
  GET/PUT/DELETE。装配 PersistenceConfig。
- **NCS-191**（bd135e2）：ContextAssembler.buildWritingConstraints / ChapterWriterAgent.
  buildChapterLengthGuide / GenreRuleProvider.getChapterWritingRules 三处消费 policy：
  HISTORICAL（textured=策略真实约束时）改区间双向/场景预算/转场定场成段叙述指令、
  解除砍描写与铺陈禁令；**其余题材字节等价现状**。A/B 证据：干净 main(e44cc76) 与分支
  同 promptFingerprint 799687fd7325——e2e 回放失败为 NCS-186 既有（fixture 缺失记录 0 次）
- textured 判定：policy.dialogueParagraphRatioMax < 1.0（作者覆盖放宽历史题材会自动回退现状文案）

## Files

- 新增 4 主类 + 2 测试类 + narrative_policy_overrides DDL + 2 装配点（MemoryConfig/AgentConfig
  late-set 注入）
- 修改 3 消费类 + 3 既有测试类补用例（ContextAssemblerTest/ChapterWriterAgentTest/
  GenreConditionalConstraintsTest）
- 状态入口已更新；旧内容归档 docs/harness/archive/{progress,handoff}-2026-10-09-phase1-ncs187-189.md

## Verification

- 合并后干净 main：`bash -c 'source scripts/load-env.sh && mvn test'` = 1512/0F/0E/1S
- `python3 scripts/check-harness.py` PASS；NCS-190/191 completion_record 均 verified
- e2e A/B 记录见 docs/harness/evidence/2026-10-10-ncs-191-verified.json 摘要与 NCS-186

## Blockers

- 无（e2e 回放通道被 NCS-186 阻塞——非本轮引入，已两次 A/B 实证）

## Next Steps

1. **建议先修 NCS-186**（e2e 回放 fixture 缺失/人审屏障）——NCS-192 的验收依赖 e2e record
   通道可用；诊断：data/e2e/events.jsonl，CHAPTER_WRITING-b1e79c68151298a7 记录 0 次
2. NCS-192 模板节奏改版（volume-outline 节拍按 policy 渲染+承接字段、chapter-writing 转场
   指令）→ p32-15：record 重建 fixture + 连续两次 replay 一致 + 人工 bless
3. NCS-193 ScenePlanAgent（新 agent+scene-plan.st，吸收 scene-contract）
4. 验证建议：historical preset 新项目 record ≥10 章，对比 TEXTURE_METRICS vs《山河执棋人》基线
