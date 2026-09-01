# Spec: 回放确定性——上下文顺序/主角锚点确定化 + fixture 完整请求与 miss 诊断

> 触发事件：p15-07 排查 E2E replay 指纹失配时暴露两个盲区——prompt 组装存在非确定性来源，且 miss 发生后无完整请求可离线比对。实现与测试已在工作树完成（未提交），按需求拆分协议补登记。

## 背景与动机

- 章节写作 prompt 的内容在两次组装之间可能漂移，直接改变 record→replay 依赖的 prompt 指纹。已定位两类来源：
  1. **同级反馈截断不稳定**：ContextAssembler 反馈段在"同级（同 severity）反馈超过 8 条上限"时仅按 severity + 章节倒序排序，平局项依赖 SQLite 返回序，截断集合不稳定（见 `ContextAssembler.buildFeedbackSection`）。
  2. **主角锚点漂移**：RelevanceGateway 与 WritingContext 用 substring（`role.contains("主角")`）判定主角，"重要配角（主角盟友线）"这类角色可抢走 `add(0)` 首位与 `toUserPrompt` 的锚点提醒行；relevantCharacters 若继承向量检索的相似度排序，平局时顺序也会漂移。
- 指纹失配一旦发生，replay 只抛出缺失 fixture 的文件名，且 fixture 仅存截断 preview（`systemPromptPreview`/`userMessagePreview`），无法离线比对完整请求定位漂移来源。

## 目标

1. 同一项目数据两次组装产出稳定上下文：同级反馈截断有全链 tie-break（severity → 章节倒序 → type → description）；relevantCharacters 顺序规范化为角色卡全集顺序，不随检索排序漂移。
2. 主角锚点确定：精确 `role=="主角"` 独占首位与锚点提醒行；substring 匹配的配角追加队尾，无精确主角时锚点行回退 substring 匹配。
3. replay miss 时把完整请求（systemPrompt/userMessage 全文）转储为 `.miss.json`；record fixture 新增完整请求字段，供离线指纹诊断。

## 非目标

- **不改 p15-07 的 fixture 目录隔离**：`E2E_FIXTURE_DIR` / `--llm.replay.dir` 传递、默认目录 `data/e2e-fixtures/llm` 及 `scripts/e2e-fixture-dir-test.sh` 的行为保持原样，p15-07 的登记范围原样保留。
- 不改请求指纹算法（taskType + 归一化 prompt 的 SHA-256）与 fixture 命名规则。
- 不迁移、重录或删除既有 fixture corpus（旧 fixture 无全文字段时 preview 字段仍在）。
- miss 后不自动重试或回退真实 LLM——保持 fail-fast 语义。

## 验收标准

- [ ] `mvn test -Dtest='ContextOrderDeterminismTest,RelevanceGatewayAnchorTest,ContextAssemblerTest'` 通过：检索命中顺序不影响 relevantCharacters 规范序（按角色卡全集顺序）；cast 命中与无 cast 两种情况下精确"主角"居首；锚点提醒行取精确主角、substring 配角不得成为锚点；同级反馈超过 8 条上限时按 description 稳定截断。
- [ ] `mvn test -Dtest=RecordReplayLlmGatewayTest` 通过：record fixture JSON 含完整 systemPrompt/userMessage；replay miss 在 fixture 目录转储恰好一个含完整请求的 `.miss.json`（含 taskType/missedAt/expectedFixture）且仍抛 IllegalStateException。
- [ ] 实现提交后跑 `./scripts/e2e.sh` + `python3 scripts/compare-baseline.py` 确认无质量退化（上下文组装变更的基线门禁）。

## 影响面

- **涉及模块**: memory/（ContextAssembler、RelevanceGateway、WritingContext）、llm/gateway/（RecordReplayLlmGateway）
- **涉及 prompt 模板**: 无（.st 模板零改动）
- **数据库 schema 变更**: 无
- **质量基线影响**: p15-08 触及上下文组装（同级反馈截断 tie-break、锚点行选择收窄），需在实现提交后重跑 e2e + compare-baseline 确认无退化；p15-09 仅改 fixture 文件字段与诊断产物，不影响线上产出

## Feature 拆分

| id | name | 依赖 | acceptance | verify_cmd |
|----|------|------|------------|------------|
| p15-08 | 上下文顺序与主角锚点确定化 | [] | 检索命中顺序不影响 relevantCharacters 规范序；cast 命中/无 cast 时精确"主角"居首；锚点行取精确主角；同级反馈超限按 severity→章节倒序→type→description 稳定截断 | `mvn test -Dtest='ContextOrderDeterminismTest,RelevanceGatewayAnchorTest,ContextAssemblerTest'` |
| p15-09 | replay fixture 完整请求保留 + miss 诊断 | [] | record fixture JSON 含完整 systemPrompt/userMessage；replay miss 转储同名 `.miss.json`（含完整请求与 expectedFixture）且仍抛 IllegalStateException | `mvn test -Dtest=RecordReplayLlmGatewayTest` |

## 风险与备选方案

- **风险**：锚点候选从 substring 收窄到精确"主角"后，主角 role 写作"男主角"的项目可能失去精确锚点。缓解：WritingContext 已实现回退——无精确"主角"时按 substring 匹配取锚点；RelevanceGateway 中这类角色仍会入选（追加队尾）。
- **风险**：fixture 新增全文字段使单个 fixture 文件体积约翻倍。缓解：纯文本字段无结构化开销；旧 fixture 不迁移、preview 字段保留。
- **回滚**：memory 三文件与 gateway 改动相互独立，可单独 revert；`.miss.json` 仅在 miss 路径写入，不影响正常 replay。
