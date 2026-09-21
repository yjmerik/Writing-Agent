# Spec: LLM 结构化输出稳定化（实体提取 / 真典收割 / 纠偏复验）

> 依据 AGENTS.md「需求拆分协议」登记。来源：`docs/harness/evidence/ncs033/llm-residuals-20260921.md`
> 五项模型侧遗留，及本轮代码取证新定位的两个传输层缺陷。

## 背景与动机

NCS-033/061/062/063/064 已修复合入 main：无效输出不再伪完成、账本可恢复、守卫输入补足、
伏笔时间线正确。剩余问题全部集中在「模型/传输侧输出无效或不完整」，代码修复无法单独消除，
需要一套**预算正确 → 截断可见 → 有界自愈 → 真实重录验收**的分层方案。

### 残留问题与根因对照

| 遗留问题（证据） | 代码层根因（本 spec 取证） | 模型/配置层根因 |
|---|---|---|
| 实体提取三章响应均截断（continued-entity-diagnosis-20260921.json，9387–9434 字符） | `LlmResponse` 不携带 finish_reason；`OpenAiCompatibleClient` 仅特判 `content_filter`，`length` 静默放行 | 有效预算 = max(请求4000, GENERAL配置4096) = 4096 token；qwen3.8-flash 为思考模型，reasoning 计入输出预算（application.yml 中 CHAPTER_WRITING 20000 / PLOTTING 16000 的注释即为此类先例）；单次调用要求实体+关系+description 全量输出，需求 ≈9.4k 字符，必然超限 |
| 第3章真典响应只有 `[`（continued-fact-harvest-diagnosis-20260921.json） | **`completeViaStream`（`OpenAiCompatibleClient.java:146`）读到 EOF 即返回，不校验 `[DONE]`/finish_reason**；`singleAttempt` 路径有 `terminated` 校验（`:362,:375`），两条路径不一致。流被干净地提前掐断（无 IOException）时，半截内容被当作成功响应返回 | Token Plan 端点流不稳定——E2E 录制期间实测 3 次流中断；NCS-063 之后该类输出会显式失败并保留可恢复账本，但每次都中断写作流水线 |
| 纠偏复验输入2项只返回1项 UNKNOWN（quality-closure-rerecord-20260921.json） | `ConsistencyGuard.verifyCorrections`（`ConsistencyGuard.java:249-252`）覆盖校验严格且保留——但缺项即整体失败 → `pauseForQuality` 屏障，没有"只补问缺失项"的降级路径 | 模型格式遵循不稳；多问题复验（实测单章 12 项 HIGH）时输出也可能撞 4096 预算 |
| 已有前文被当成凭空证据（continued-guard-diagnosis-20260921.json） | 输入缺陷已由 NCS-033 修复（补最近两章有界正文） | 补足输入后模型是否仍误判，**尚未重测**——归入 NCS-070 重录观察项，不预设结论 |
| 新线索/伏笔回收/文学质量判断 | 无代码缺陷登记 | 归 NCS-058 真人验收，本 spec 不触碰 |

### 方案原则（与"登记不掩盖"边界一致）

1. **不掩盖**：任何重试/补问都有事件落 `data/logs/events.jsonl`；账本 COMPLETED 仍只认有效输出
   （NCS-063/064 语义不变）；不自动 bless；诊断报告能分类截断/无效输出。
2. **先治本后兜底**：输出预算与输出需求匹配（路由+拆分）是主修复；有界重试只兜可判定瞬态失败，
   上限 1 次。
3. **不扩架构**：不新增 Agent/服务/数据库表；复用现有 taskType 路由、事件日志与账本恢复机制。

## 目标

1. 结构化提取（实体图、真典收割、纠偏复验）在真实 LLM 三章 E2E 中产出有效完整结果：
   实体表与关系表非零、真典逐章增长、复验覆盖完整。
2. 截断与无效输出全链路可观测：finish_reason 透传 + LLM_TRUNCATED 事件 + 未终止流按协议失败，
   不再靠 JSON 解析失败倒推。
3. 可判定失败有界自愈：截断/解析失败/复验缺项各有一次带因重试或补问；重试失败保持可恢复状态，
   绝不伪完成。

## 非目标

- 不更换默认模型/端点/套餐（qwen3.8-flash 与 Token Plan 保持；换模型需用户另行授权）。
- 不做前缀抢救（解析截断 JSON 的完整前缀部分落库）——会造成"半完成"账本，与 NCS-063/064
  的不伪完成原则冲突，明确否决。
- 不做多级/指数重试；不因重试成功而省略事件；不以任何方式改写旧失败样本。
- 不自动解除历史人工复核屏障；文学质量验收仍归 NCS-058（依赖链不变）。

## 验收标准

- [ ] NCS-065：`mvn test -Dtest=LlmClientTerminationTest` 通过——未终止流（无 `[DONE]` 且无
  finish_reason 即 EOF）不得作为成功返回，按可重试协议失败处理，重试耗尽显式失败；
  finish_reason=length 透传至 `LlmResponse.getFinishReason()` 且观测层发 `LLM_TRUNCATED`；
  stop 正常路径行为与现有事件不变；record/replay fixture 缺 finishReason 字段默认 stop。
- [ ] NCS-066：`mvn test -Dtest=ExtractionRoutingTest` 通过——EXTRACTION 路由预算/温度/思考开关
  独立于 GENERAL；DashScope 兼容端点关思考以 `enable_thinking:false` 注入，GLM 端点保持
  `thinking.type=disabled`，互不误发；实体与事实提取请求已切至 EXTRACTION。
- [ ] NCS-067：`mvn test -Dtest=EntityExtractorSplitTest` 通过——实体与关系两次调用各自独立预算与
  紧凑 schema；任一步失败保持可恢复、不部分标记；模板变量名兼容，对应 Agent 测试通过。
- [ ] NCS-068：`mvn test -Dtest=StructuredOutputRetryTest` 通过——无效输出（截断/解析失败/整批
  校验失败）恰好重试 1 次且事件可见；重试成功正常完成；二次失败保持未完成可恢复；合法空结果
  不触发重试。
- [ ] NCS-069：`mvn test -Dtest=CorrectionVerificationGapTest` 通过——2 项输入缺 1 项时补问缺失项
  后覆盖完整不屏障；补问仍缺则屏障照旧；复验事件含补问轨迹。
- [ ] NCS-070：重录验收 record→replay×2 三次报告一致；诊断无截断/无效输出类残留；实体/关系表
  非零、第 2/3 章真典增长；复验覆盖完整；新旧质量对比经人工确认后 `--bless`，理由记入证据。
- [ ] 每项特性：先失败测试复现，`mvn compile && mvn test` 0 failures / 0 errors；改 prompt 的项
  对应 Agent 测试通过并按 DoD 重跑 e2e。

## 影响面

- **涉及模块**: `llm/`（client、config、gateway）、`agent/`（EntityExtractorAgent、
  FactHarvesterAgent、ConsistencyGuard）、可观测性事件（EventLog 新增 LLM_TRUNCATED /
  STRUCTURED_OUTPUT_RETRY 分类）。
- **涉及 prompt 模板**: `entity-extraction.st`（拆分为实体/关系两段输出约束，变量名不变）、
  `fact-harvest.st`（新增输出上限约束）、`correction-verification.st`（补问变体，若需）。
- **数据库 schema 变更**: 无。
- **质量基线影响**: NCS-066/067/068/069 改变请求参数或产出 → 合入 main 后必须以
  `E2E_FIXTURE_DIR=data/e2e-fixtures/<preset>-<日期> ./scripts/e2e.sh --mode record` 重建语料，
  连续两次 replay 一致，展示新旧质量对比并经人工确认后 `compare-baseline.py --bless`
  （p32-15 教训）；重建理由记入证据。

## Feature 拆分

| id | name | 依赖 | acceptance | verify_cmd |
|----|------|------|------------|------------|
| NCS-065 | 流式终止校验与截断信号 | [] | 未终止流不得伪成功（先失败测试复现 `[` 场景）；finish_reason=length 透传并发 LLM_TRUNCATED；fixture 缺字段默认 stop；编译+全量通过 | `mvn test -Dtest=LlmClientTerminationTest` |
| NCS-066 | 结构化提取独立路由与预算 | [] | 新增 EXTRACTION 任务类型（初始 max-tokens 16000 / temperature 0.1 / thinking 关闭）；DashScope 关思考参数适配；实体与事实提取切换路由；编译+全量通过 | `mvn test -Dtest=ExtractionRoutingTest` |
| NCS-067 | 实体提取拆分与 schema 瘦身 | [NCS-066] | 实体、关系两次调用独立预算，实体紧凑 schema（description 限长）、关系仅引用规范名；任一步失败可恢复不部分标记；Agent 测试+e2e 门禁 | `mvn test -Dtest=EntityExtractorSplitTest` |
| NCS-068 | 结构化输出有界重试 | [NCS-065] | 实体/事实提取对可判定无效输出单次带因重试，STRUCTURED_OUTPUT_RETRY 事件；二次失败保持可恢复；合法空结果不重试 | `mvn test -Dtest=StructuredOutputRetryTest` |
| NCS-069 | 复验缺项补问 | [] | verifyCorrections 严格校验保留；缺项时一次补问缺失 issueId 并合并结论；补问仍缺才屏障；事件含补问轨迹 | `mvn test -Dtest=CorrectionVerificationGapTest` |
| NCS-070 | 真实重录稳定验收 | [NCS-066, NCS-067, NCS-068, NCS-069] | record 一次 + replay 两次一致；诊断无截断/无效输出残留；实体/关系非零、真典逐章增长、复验覆盖完整；人工对比后 bless 并记录理由；顺带重测守卫误判（NCS-033 修复后） | `./scripts/e2e.sh && python3 scripts/compare-baseline.py` |

实施顺序建议：NCS-065 → NCS-066 → NCS-067 → NCS-068 → NCS-069 → NCS-070（一次一项，
走完整合并协议；NCS-069 可与其余并行排期但不并分支）。

## 风险与备选方案

- **关思考可能降低复杂抽取质量**：NCS-066 的 thinking 开关是 yml 配置，实测劣化则只提预算
  （16000 已与 PLOTTING 同级）不关思考，验收标准不变。
- **拆分增加每章 1 次调用**：相比截断后整章账本卡住、人工恢复重跑的成本，净成本更低；
  重试上限恒为 1，成本可从事件账目核算。
- **早断流根因在网关侧**（Token Plan 端点）：终止校验只能检测并触发既有重试语义，不能消除
  端点抖动；若重录仍高频复现，升级为供应商问题交用户决策，不以加重试掩盖。
- **回滚**：全部为代码/配置级改动，逐 feature 独立分支可单独 revert；fixture 重建随 NCS-070
  一次进行，中途回滚不污染旧语料（新 E2E_FIXTURE_DIR 隔离）。
- **被否决方案**：截断 JSON 前缀抢救落库（伪完成风险）；多级重试/退避风暴（掩盖+成本）；
  换模型（超授权范围）。
