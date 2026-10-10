# 交接：叙事质感架构 Phase1 完成（NCS-187/188/189） — 2026-10-09

Last Updated: 2026-10-10

## Current

《山河执棋人》暴露的系统性质量缺陷（剧情过快/对话乒乓 77% 段占比/叙述标签化 17.3 字均段/
零转场）已完成根因设计并落地 Phase 1。完整设计在
**docs/specs/2026-10-09-narrative-quality-architecture.md**（控制论五要素：测量/区间设定点/
场景预算可达性/分级执行/卷级闭环+迟滞；拆 NCS-187..200）。

- **NCS-187**（03a591b，completed）：`StyleMetricsService.computeTexture` 确定性纹理指标
  （dialogueParagraphRatio/narrationRunMaxChars/sceneBreaks/dialoguePingPong/
  avgParagraphLength），`ChapterProduction.write` 后发 TEXTURE_METRICS 事件——observe-only，
  TextureGate（NCS-195）与卷级闭环（NCS-200）的测量基线
- **NCS-188**（91ddf37，completed）：①`PlotterAgent.parseChapterDetails(cs, raw, maxBeats)`
  重载——超限节拍并入末段+OUTLINE_BEATS_TRIMMED 事件；`parseAndAppendVolume` 按项目
  prefs 传上限。②bug：preset 创建项目 genre 已设导致题材默认 maxBeats（HISTORICAL=3）
  从未生效——ProjectService 构造器注入 GenreRuleProvider 修复，OrchestratorConfig 已装配
- **NCS-189**（ab72f33，completed）：反馈注入非 STYLE 截断 80→200 字（PACING 纠正指令
  不再腰斩）；`VolumeReadbackService.buildReadbackContext` 透传 pacing「节奏观察」
- 登记与回执：b3575e6（spec+NCS-187..200 注册+补 NCS-185 完成记录+修 NCS-186 spec 指针）；
  dcce672/8e1fe3b/7594cea 三张 verified 回执

## Files

- 实现 StyleMetricsService / ChapterProduction / PlotterAgent / ProjectService /
  OrchestratorConfig / NovelOrchestrator / ContextAssembler / VolumeReadbackService
- 测试：StyleMetricsServiceTest / ChapterProductionTest / PlotterAgentTest /
  ProjectServiceTest / ContextAssemblerTest / VolumeReadbackTest（各 feature 均先红后绿）
- 状态三入口已更新；旧内容归档 docs/harness/archive/{progress,handoff}-2026-10-09-beijing-glm-ncs185.md

## Verification

- 合并后干净 main：`bash -c 'source scripts/load-env.sh && mvn test'` = 1501/0F/0E/1S
- `python3 scripts/check-harness.py` PASS；NCS-187..189 completion_record 均为 verified

## Blockers

- 无。E2E 屏障既有问题仍在 NCS-186（未动）；本轮不触碰 .st 模板，无 fixture 重建负担

## Next Steps

1. NCS-190（NarrativePolicy 域对象+题材默认+持久化+REST）——Phase 2 起点，无依赖；
   注意快节奏题材默认值须与现状语义等价（golden 不回归）
2. NCS-191/192 依赖 NCS-190；192/194/197 触碰 .st → p32-15：record 重建 fixture+两遍
   replay 一致+人工确认 bless
3. 新项目验证建议：historical preset 建 ≥10 章 record，用 TEXTURE_METRICS 事件对比
   对话段占比/叙述块/转场计数 vs《山河执棋人》基线（77%/17.3/≈0）
