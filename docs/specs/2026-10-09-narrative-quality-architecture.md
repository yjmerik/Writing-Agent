# Spec: 叙事质感架构——从"开环单向开关"到"闭环区间控制"

> 2026-10-09 会话产出。证据链：`docs/reports/2026-10-09-novel-eval-shanhezhiqiren/`（五维评测 + 三链根因审查）+
> 本会话三轮代码探查 + 全书文本实测。解决《山河执棋人》暴露的系统性质量缺陷：
> 剧情推进过快、对话乒乓、缺背景交代/场景描写/转场。

## 背景与动机

### 症状（全书 55 章实测，两种口径）

| 指标 | 实测值 | 说明 |
|---|---|---|
| 对话段占比（含引号段落/总段落） | **77%**（ch55 达 88%） | 阅读感受"全是对白"的直接来源 |
| 引号内字符占比（StyleMetricsService.dialogueDensity 同口径） | 均值 **36.8%**，中位 37% | **低于指令要求的"≥50%"却从未被发现**——约束写了但无人测量 |
| 平均段长 | **17.3 字/段** | 叙述被切成说话人标签式短句，无成段描写 |
| 场景分隔线/转场文字 | 全书集中在一个 GLM 生成段（ch36-39），其余章节≈0 | 全代码库"转场"零命中，无任何机制要求转场 |
| 句长 | 25.1 字/句；GLM 段行长 45.9 vs qwen 段 25.3 | 同栈不同模型差异巨大：质感未被系统锁定 |

### 根因的综合：一个开环控制系统

历史证据表明系统在两个失败模式间振荡：7 月加 DialogueEnrichment（对话丰富化，commit fda4e2f）治"干巴说明文"，8 月加 ExpositionDetector（罚连续 5 段无对话）+ preset dialogueDensity=0.5 治"说明文堆砌"，10-03 评测又发现"短句对白乒乓与说明文密度过高两头摆"。振荡的结构性原因——系统只有**单向开关**，没有**恒温器**：

1. **不测量**：`StyleMetricsService`（NCS-104）每章确定性算出 dialogueDensity/句长/重复率，但只进人看的报告和 golden 基线，不进任何门禁、不触发任何纠正。约束要求 ≥50%、实际 37%，从头到尾无人知道。
2. **设定点单向**：对话只有下限无上限；超字数时"精简次要场景和描写"（描写是第一裁撤对象）；前 5 章禁止铺陈；历史题材节奏规则"背景铺陈 20%"。全部朝"快+对话"单向发力。
3. **设定点算术上不可达**：大纲要求每章 3-5 个节拍（volume-outline.st:23，只有下限语义、解析端不校验），写作端要求"节拍必须全部覆盖"，同时字数目标 3500-4500——每节拍只摊 ~1000 字，只够对话赶场；且与"核心事件不超过 4 件"自相矛盾（ContextAssembler.java:991-1004）。**计划层塞入的剧情质量超过一章文字的承载容量，压缩成对话是必然结果。**
4. **无自身文风记忆**：写手只看到上一章末尾 2000 字符（ContextAssembler.java:24），风格档案只有基调/叙事/注意事项 3 字段，historical preset 无 styleSamples 范本——模型每章在"失忆"状态下凭约束清单写作。
5. **反馈回路断裂**：卷 1 已产生 PACING 警告 6 次/STRUCTURE 18 次，但警告只注入写手（还截断到 80 字）到不了 Plotter；卷级趋势只发事件不落库；volume-readback 的 pacing 字段被 buildReadbackContext 丢弃；下一卷规划 tryExtendLayeredOutline 的卷梗概输入是占位串"（延迟生成，根据总纲规划）"（NovelOrchestrator.java:2931-2935）。检测到"过快"却永不转化为计划修订。

### 为什么不能再打 prompt 补丁

chapter-writing.st 里其实**已经存在**好指令（铁律 12 场景锚点、"信息密度控制：一章只讲好一件事"、"感官沉浸"），它们被矛盾的覆盖性约束（节拍全覆盖+对话下限+超字数砍描写）系统性压制。7-8 月的振荡史证明：在同一层继续加指令只会来回摆。根治需要补齐控制系统的全部五要素。

## 设计原则（控制论五要素）

| 要素 | 现状 | 设计 |
|---|---|---|
| 测量（P） | 指标已算但无人消费 | TextureGate：确定性指标进事件、进 issue、进基线 |
| 设定点 | 单向、散落 5 处、互相矛盾 | NarrativePolicy：区间化、单一事实源 |
| 可达性 | 3-5 节拍 > 4000 字容量 | 计划侧算术校验 + ScenePlan 场景预算 |
| 执行（分级） | 只有整章重写一条路 | 定点扩写 → 整章重写的修复阶梯 |
| 闭环（I+D） | 警告只到写手、卷间断链 | VolumePacingController 卷级积分 + 卷内迟滞；VoiceAnchor 文风微分锚定 |

**迟滞原则（防振荡关键）**：设定点只在卷边界调整（VolumePacingController），卷内各章指令保持稳定——杜绝"这章罚对话多、下章罚叙述多"的风格鞭打。

## 目标架构（6 个组件）

### 1. NarrativePolicy 叙事策略（单一事实源）

新增 domain 对象，题材默认 + 项目级覆盖（扩展 WritingPreferences 持久化）。字段（全部为**区间**）：

```
dialogueParagraphRatio   对话段占比区间      如历史 [0.30, 0.55]
dialogueCharRatio        引号内字符占比区间   如历史 [0.30, 0.50]
narrationRunMinChars     成段叙述最短块       如 ≥120 字（防标签化碎片）
beatsPerChapter          节拍数区间（按章型） 标准 [2,3]、喘息 [1,2]、开篇 [2,3]
charsPerBeatMin          每节拍最低字数预算   如 1200
transitionMinChars       场景转场最短字数     如 80（时间/空间/感官落点）
sceneBreaksPerChapter    场景数区间          如 [2,4]
paragraphMaxChars        单段上限（保留 200）
openingSettingAllowance  章首定场上限许可    如 ≤150 字（替代"禁止铺陈"）
sensoryAnchorPerScene    每场景感官锚点下限   如 ≥2 处
```

消费方：`buildWritingConstraints`、`buildChapterLengthGuide`、`GenreRuleProvider`（硬编码文案退役，改为渲染 policy）、Plotter 校验、TextureGate、VolumePacingController、preferences-chat（作者自然语言可调）。**快节奏题材（wuxia/cultivation）默认值≈现状**，golden 基线不回归。

### 2. 计划侧节奏算术（PlotMassValidator）

- `PlotterAgent.parseChapterDetails` 增加确定性校验：节拍数 ∈ policy 区间；`节拍数 × charsPerBeatMin ≤ maxChapterLength`，超限则要求 LLM 合并重出或自动裁剪节拍（标记 issue）。
- 大纲模板改版：节拍"3-5 个"→按 policy 渲染（慢节奏 2-3）；喘息章豁免为 1-2 节拍；章梗概增加"承接提示"字段（上一章→本章的时间/空间跳转说明）。
- 修复 preset 路径 bug：genre 已被 preset 设置导致 `maxBeatsPerChapter` 题材默认（HISTORICAL=3）从未应用（NovelOrchestrator.java:425-436 只在 genre==null 分支生效）。
- 解除写作端矛盾指令："节拍必须全部覆盖"→"按 ScenePlan 场景预算展开，相邻节拍可合并"。

### 3. ScenePlan 场景规划层（生产单元重构——核心）

现状章是"一坨 beats 塞进 4000 字"。改为：**写前新增一个轻量场景规划步骤**（新 Agent + `scene-plan.st`，小输出 ~1000 token，GENERAL 路由），输入 = 章梗概节拍 + 上一章尾部 + NarrativePolicy，输出 2-4 个场景：

```
场景i：时间/地点/出场人物 | 承接上一场景的转场方式（硬切/省略/过渡段+最低字数）
      | 字数预算 | 质感提示（本场景的感官锚点/描写重点）| 因果约束（吸收 scene-contract 字段）
```

- ChapterWriter 按场景顺序写作，每场景有**字数下限**和转场规格；场景间输出分隔标记（`※`），导出/入库侧 sanitize。
- **算术从此成立**：4000 字 ÷ 2-3 场景 = 每场 1300-2000 字，足够"定场+描写+对话+收束"。
- 现有 scene-contract.st/Agent/手动 REST 路径被吸收 supersede（因果字段并入 ScenePlan）。
- 成本：每章 +1 次小调用（<2%）；后续可选每场景独立调用做并行/提质（非首期）。

### 4. TextureGate 质感门禁（确定性、零 LLM 成本、双向）

在 ChapterProduction.write 之后、读者评审之前运行（毫秒级）。扩展 StyleMetricsService 指标（按场景段分段计算）：

| 指标 | 口径 | 治什么 |
|---|---|---|
| dialogueParagraphRatio | 含引号段落占比 | 对话乒乓（77%→≤55%） |
| dialogueCharRatio | 引号内字符占比 | 双向：过低=说明文回归，过高=戏不够 |
| narrationRunStats | 最长连续非对白段字数 | 叙述标签化（17 字碎片→存在 ≥120 字成段叙述） |
| sceneBreaks + transitionChars | 分隔标记数与其周边字数 | 转场缺失 |
| avgParagraphLength / avgSentenceLength | 现有 | 电报体短行 |
| dialoguePingPong | 连续 >3 段对白无锚点行次数 | 铁律 12 首次有检测端 |

行为：区间内→PASS；**质感单项越界**（结构完好）→ 触发定点扩写修复；**结构性越界**（节拍未覆盖/字数不足）→ 走既有 rewriteWithCorrection（纠偏指令携带具体质感目标，如"对话段占比 78%→55% 以下，3 处转场各 ≥80 字"）。ExpositionDetector 并入本 gate 作为反方向检测。事件 `TEXTURE_GATE` 落 events.jsonl + golden 基线新维度。

### 5. 定点扩写修复通道（修复阶梯的轻量级）

复用 PassageEditor 的 EXPAND 模式（已有"1.5-2 倍、增加感官细节/心理/动作描写"指令）做自动版：TextureGate 挑出最弱的 1-2 个场景段 → 扩写 → 快速连续性复验（ConsistencyGuard）→ 通过则替换。只动质感不动情节，比整章重写便宜且不引入新矛盾。

### 6. VoiceAnchor 文风锚定 + VolumePacingController 卷级闭环

**文风锚定（微分项）**：
- RECENT_TEXT_CHARS 2000 → **上一章全文**（预算充足：写作输入预算 40800 token，上一章 ≈2.7k token）；隔章再带前章尾部。
- 卷末范本收割：从读者分 ≥8 的章节抽 1-2 段进 StyleProfile.samples（cap 3，滚动刷新）——奖励式 few-shot，让系统向自己最好的段落收敛。
- 7 个 preset 补 styleSamples（historical 用 GLM 段 ch36-39 提炼——评测已证实为全书质量峰值）；style-establish 增加描写密度/转场手法/叙述对话配比三个维度字段。

**卷级节奏控制器（积分项，闭环咽喉）**：
- 挂在既有卷末钩子（volumeGate，NCS-123 位置）。输入：本卷 TextureGate 指标聚合 + 检测趋势计数（PACING/STRUCTURE/DENSITY）+ 读者子分 + readback.pacing（修复当前被丢弃的问题）。
- 输出：持久化 `VolumePacingDirective`（下一卷设定点偏移 + 明确指令：喘息章数量、节拍上限、对话区间移动、必需场景类型）。
- 注入两处：① tryExtendLayeredOutline 的 planVolume 调用——**替换占位卷梗概**"（延迟生成，根据总纲规划）"为真实 directive + 完整 previousSummary；② 下一卷各章的 writingConstraints。
- 与 NCS-183（卷间计划修订器）关系：本组件实现其节奏维度；若 NCS-183 另立，伏笔/弧线维度归它，节奏维度归此，避免重复建设。

## 目标

1. 新历史权谋项目 E2E：全书对话段占比 ≤55%、每章含成段叙述（≥120 字叙述块）与转场（场景数-1 处）、均段长较《山河执棋人》基线（17.3 字）提升 ≥40%，人工盲评确认质感改善。
2. 快节奏题材（wuxia）golden 基线零回归（policy 默认保持现状）。
3. 每项质感目标都有：确定性指标、门禁消费、闭环反馈三件套——不再出现"约束写了但 37%<50% 无人知晓"。

## 非目标

- 不做模型微调 / 外部范文库 RAG（范本收割已近似）。
- 不改多模型路由架构（write 通道换模型是独立决策，见 NCS-181/182 记录）。
- 不在本 spec 内解决伏笔兑付/钩子检测（NCS-184 方向）与检查输出爆炸（NCS-179）。
- 不追补《山河执棋人》旧章（收尾项目，验证向前看）。

## 验收标准（整体，分 feature 验收见拆分表）

- [ ] 新建历史权谋项目 record 跑 ≥10 章，`events.jsonl` 含 TEXTURE_GATE 事件且指标落区间；replay 连续两次一致
- [ ] `python3 scripts/compare-baseline.py` 新语料 bless 后 PASS；wuxia 旧 golden 不回归
- [ ] `mvn compile && mvn test` 全绿
- [ ] 卷边界 events.jsonl 含 VOLUME_PACING_DIRECTIVE；下一卷大纲 prompt（fixture 指纹可见）包含 directive 内容
- [ ] 人工盲评 3 章新旧对比（作者确认）

## 影响面

- **涉及模块**: domain（NarrativePolicy/ChapterSynopsis/StyleProfile 扩展）、agent（ScenePlanAgent、ChapterWriterAgent、PlotterAgent、StyleGuardianAgent、PassageEditor）、memory（ContextAssembler）、orchestrator（ChapterProduction、NovelOrchestrator）、service（GenreRuleProvider 退役硬编码、StyleMetricsService 扩展、VolumeReadbackService、新 VolumePacingController）、web（policy REST）
- **涉及 prompt 模板**: volume-outline.st、chapter-writing.st、style-establish.st、新 scene-plan.st、passage-edit.st（扩写自动版变量）；**变量名不改**，新增走条件块
- **数据库 schema 变更**: WritingPreferences 增列（区间字段）或新表 narrative_policy；VolumePacingDirective 新表；StyleProfile.samples 已有
- **质量基线影响**: 大——多个 .st 变更按 p32-15 规则需 record 重建 fixture + 人工确认后 bless；集中在 P2/P3/P4 批次做，减少重建次数

## Feature 拆分

> 按依赖排序；Phase1 不改文风产出（先看见），P2 起改。每 feature 独立闭环（分支→验证→合并）。

| id | name | 依赖 | acceptance | verify_cmd |
|----|------|------|------------|------------|
| NCS-187 | 纹理指标遥测：TextureMetrics 进事件+issue（observe-only） | [] | 写一章后 events.jsonl 出现 TEXTURE_METRICS 事件，含 dialogueParagraphRatio/narrationRun/sceneBreaks 等字段 | `mvn test -Dtest=ChapterProductionTest` |
| NCS-188 | 计划侧节拍算术校验 + preset genre/maxBeats bug 修复 | [] | beats>上限的卷大纲解析被裁剪并落 issue；preset 创建的历史项目 maxBeats=3 | `mvn test -Dtest=PlotterAgentTest` |
| NCS-189 | 反馈链小修：feedback 80→200 字截断、readback pacing 透传 | [] | 单测断言新长度与字段保留 | `mvn test -Dtest=ContextAssemblerTest,VolumeReadbackServiceTest` |
| NCS-190 | NarrativePolicy 域对象+题材默认+持久化+REST | [] | REST 读写区间字段；历史/wuxia 默认值断言 | `mvn test -Dtest=NarrativePolicyTest` |
| NCS-191 | 约束统一读 policy（双向区间、解除节拍矛盾、转场/定场指令） | [190] | 组装出的 writingConstraints 含区间双向文案与转场要求；快节奏题材输出与现状 golden 一致 | `mvn test -Dtest=ContextAssemblerTest` + e2e replay |
| NCS-192 | 大纲/写作模板节奏改版（volume-outline 节拍区间+承接字段、chapter-writing 转场+定场许可） | [190] | 模板测试过；e2e record 重建 fixture 两遍 replay 一致 | `./scripts/e2e.sh --mode record` ×2 |
| NCS-193 | ScenePlanAgent + scene-plan.st（吸收 scene-contract） | [190] | 给定梗概产出 2-4 场景 JSON（时间/地点/预算/转场/质感/因果）；单测覆盖解析 | `mvn test -Dtest=ScenePlanAgentTest` |
| NCS-194 | 场景预算注入写作 + 分隔标记 sanitize | [193,191] | 正文含场景分隔标记且导出侧被正确处理；每场景字数≥预算×0.8 | `mvn test -Dtest=ChapterWriterAgentTest` |
| NCS-195 | TextureGate 双向门禁 + 对白乒乓检测 + 分级路由 | [187,190] | 越界章节落 TEXTURE 类 issue 并按质感/结构分级路由；ExpositionDetector 并入 | `mvn test -Dtest=TextureGateTest` |
| NCS-196 | 定点扩写修复通道（PassageEditor EXPAND 自动版） | [195] | 构造对话超标样本→扩写后复验通过且情节事实不变 | `mvn test -Dtest=PassageEditorTest,ChapterProductionTest` |
| NCS-197 | 上一章全文注入文风锚定 | [] | recentTextSection 含上一章全文；token 预算裁剪单测通过 | `mvn test -Dtest=ContextAssemblerTest` |
| NCS-198 | 范本收割 + 7 预设 styleSamples（historical 取 GLM 段） | [] | 卷末高分章段落入库 StyleProfile.samples；preset 加载断言 | `mvn test -Dtest=StyleGuardianAgentTest,PresetRegistryTest` |
| NCS-199 | style-establish 质感维度扩展 | [] | 新项目档案含描写/转场/配比三字段；旧档案兼容 | `mvn test -Dtest=StyleGuardianAgentTest` |
| NCS-200 | VolumePacingController 卷级闭环（directive 持久化+注入下一卷） | [187,189,190] | 卷末产生 directive；下一卷 planVolume prompt 与写作约束可见其内容；卷内设定点不变（迟滞） | `mvn test -Dtest=VolumePacingControllerTest` + e2e |

## 风险与备选方案

**成本/时延**：ScenePlan +1 小调用（<2%）；上一章全文 +~2.7k 输入 token（预算 40800 充足）；扩写触发时 +30-50% 输出 token（仅质感越界章）。对照《山河执棋人》225s/¥0.064/章，预计 +10-20%。可接受；北京 GLM 混合路由留作可选。

**振荡复发**：这正是本设计的首要目标。防线=区间（同时约束两个失败模式）+ 迟滞（卷内不变）+ 卷级积分（趋势才调设定点）+ 双向门禁（对白乒乓与说明文同 gate）。

**题材回归**：NarrativePolicy 分题材默认，快节奏题材默认≈现状；wuxia golden 是回归门禁。

**扩写破坏一致性**：扩写限定场景内 + 扩写后必跑 ConsistencyGuard 复验；失败回退原文并升级整章重写。

**fixture 重建成本**：p32-15 规则要求每次 prompt 改动重建；P2/P3/P4 各批一次，feature 顺序安排已把 .st 改动聚堆。

**被否决的方案**：
1. *纯 prompt 调优*——7-8 月振荡史已证伪；好指令已存在且被覆盖性约束压制，缺的是测量与闭环。
2. *全文两遍生成（plot 草稿 pass + 全文扩写 pass）*——成本×2，全章重生成一致性风险大于定点扩写。
3. *微调/外部范文库*——超出当前工程边界，范本收割（NCS-198）以极低成本近似其收益。
4. *只调 preset 参数（dialogueDensity 降档）*——治标：37% 实测已低于 50% 指令却仍显"对话过多"，说明段结构（标签化碎片）才是主病灶，参数调不动结构。
