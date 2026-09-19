# NCS-030 联合验收报告

Last Updated: 2026-09-20

**T01–T11 的确定性功能旅程已走通。** 本报告验证版本、操作、恢复、门禁和导出，不把合成模型输出当作文学质量或全书语义修复的证明。分支 `codex/ncs-030-browser-journey`；基线 main `6480704`。合并及干净主分支的验证证据见 `docs/harness/evidence/2026-09-20-ncs-030.json`。

用户于 2026-09-20 授权补齐验收所需的原计划实现。仅补齐该范围；首次失败与旧阻塞保留在 [before-repairs.md](before-repairs.md)，不代表修复后的状态。

## 环境与样本

- Java 21，前端生产构建，ego-browser TaskSpace 23；真实浏览器操作。
- localhost:18097，ScenarioLlmGateway + stub embedding，独立 SQLite `/tmp/writingagent-ncs-browser/novel.db`；没有调用真实模型。
- A：`114381af-52ce-43a1-8a0e-6b96248619f0`，通过 UI 新建并生成19章，用于起步、任务、局部改文、人物时点与恢复。
- B：`f48efcbf-ea03-4eb5-9efa-b1faa9c7bc5b`，隔离成书样本，用于并发、单次要求与冻结版本导出。旧版通过报告的前置由 API 准备。
- C：`7bc6ca3a-aae4-4cda-91d4-b4f3cb2e2e7d`，通过 API 准备四章及密信来源证据。修改起点为焚信，后两章分别引用名单和原件，第四章为无关支线。范围批准、影响分析、候选比较、整组采纳和组合采纳均通过 UI 操作。
- A 的来源证据与第10章获知记录通过 API 准备；第8/12章时点选择、查看和恢复通过 UI 操作。没有把数据准备冒充自然模型抽取。

## T01–T11 结果

| 场景 | 实际观察与断言 | 证据 |
|---|---|---|
| T01 空作品起步 | UI 仅填标题/构思，选填约定合法为空；6条世界设定、8人物和大纲均属于本作品；第一章已持久化。planRef 与当前章纲 SHA-256 前12位精确匹配。 | journey-results.json/startup；NCS-029 起步截图 |
| T02 局部改文与保护 | 第一章 v2→v3，候选前文与来源段落匹配；落稿精确等于候选替换首段，未选区逐字不变；来源与接受回执指纹匹配。 | persisted-audit.json；t02-preview.png |
| T03 跨章修订 | C 第2/3章 MUST_REPAIR、第4章不受影响；范围批准与接受独立；生成候选不改变主稿，逐章对照后整组接受。关系闭包、旧稿来源锚点及事务中断另由真实 SQLite 专项覆盖。 | t03-t05-group-retry.png；journey-results.json/group；ImpactAnalysisTest、GroupAcceptanceTest |
| T04 并发与旧候选 | B 候选基于v5，另一浏览器页作者保存为v6；采纳旧候选返回预期409，按钮禁用，原基准/候选/最新稿均保留；作者补记未丢失。 | t04-stale-three-way.png；journey-results.json/stale |
| T05 恢复与结果未知 | 任务停止/继续保留历史；跨章刷新恢复同一请求的影响列表与已读位置。CDP 在整组采纳服务端返回200后故意丢弃响应，再点采纳得到同commandId幂等收据；两章仅v2→v3。 | t03-t05-group-retry.png；journey-results.json/group；NCS-029 任务报告 |
| T06 人物时点 | 第8章读者可见正文引文，人物尚无知情；第12章人物显示第10章获知及来源第8章v3；作者状态独立显示，来源可定位。按人物ID读取，避免姓名误配。 | t06-reader-before-character.png、t06-character-knows-later.png；journey-results.json/asof |
| T07 单次要求 | UI 登记ONCE逐字写入要求；预览时仍OPEN，采纳为v7后证据绑定章/稿/hash/原句；作者确认该证据后显示已确认。核对器另有UNMET记录仍如实展示，未伪装通过。 | t07-version-evidence-confirmed.png；journey-results.json/directive |
| T08 未知不误报 | 旧报告明确标旧稿、INVALID不当通过；DONE章可手动补查连续性。恢复后v4的补查得到VALID_EMPTY且正文指纹相符，其他读者报告仍保留原稿号。坏输出与零重写预算不放行由全量中的CheckResultEnvelopeTest等覆盖，未声称浏览器演练了全部模型故障。 | t07-t08-review.png；journey-results.json/recheck；SelectiveAcceptTest |
| T09 恢复旧稿 | A第8章恢复v2形成v4，正文精确相等、v3及其新增引文仍在历史；撤回旧来源支持后，不再是人物当前已知事实，旧信念仍保留。随后实际补查当前连续性。B先前恢复v2形成v5亦有持久证据。 | t09-history-preserved.png、t09-restored.png；journey-results.json/restore；persisted-audit.json |
| T10 组合候选 | UI从两份同章候选各选一段，形成新组合；检查前禁止采纳，独立重检后允许；落稿精确等于组合且v2→v3，原两份候选仍READY。第二组合不继承第一组合检查；INVALID阻断，重复接受幂等。 | t10-combined-accepted.png；journey-results.json/combo；SelectiveAcceptTest 7项 |
| T11 同一份全书定稿 | 旧v2报告与新v5报告分离，新稿未继承批准；真实浏览器download事件取得3185字节文件，含冻结manifest及v2，不含当前v7新增要求和v6作者补记。 | t11-fixed-report.png、t11-new-report.png；t11-downloaded-book.md；journey-results.json/download |

## 必需修复与先失败后通过

- 组合检查绑定候选ID和正文指纹，保留UNKNOWN/INVALID信封，采纳复用原子版本边界与回执。
- 整组正文、版本、候选状态、请求收据在同一 SQLite 事务提交。第二次章节更新强制失败的测试，先复现残留APPLIED，再验证所有写入回滚。
- 人物时点按ID查知情记录，并分开显示读者已见正文；影响分析补入旧版本来源与有效关系闭包。
- 旧候选冲突三方对照、修订已读恢复、稳定采纳命令、组合候选界面、版本化要求证据确认、DONE章连续性补查与批准入口、真正的文件下载。
- Scenario 仅对明确的“原文写入”执行并报告证据；不能执行的要求返回UNMET。清洁连续性输出遵守已有协议，没有放宽生产解析器。
- 最终采样发现浏览器默认favicon请求500；补上内嵌图标后，复验无该错误。没有改全局异常处理。
- 针对性回归34项通过；读者知情新增复现后专项6项通过。完整回归统计及合并后结果见下方验证记录。

## Console / Network

[browser-events.jsonl](browser-events.jsonl) 是采集窗口摘要，不是无遗漏HAR。最后一轮三作品页面加载采集40条响应，0条4xx/5xx、0条console warning/error或Runtime exception。修复前favicon的500及修复后的结果均保留。T04的409、T05主动丢弃成功响应属于预期负向场景；测试脚本的旧定位/等待错误及数据准备时的非法枚举400不当作产品通过证据。

## 验证记录

分支已完成：1093 tests / 0 failures / 0 errors / 1 skipped，编译和前端构建PASS；replay 3章9730字、0章节失败/0LLM错误，baseline PASS。

分支与合并后干净main均已通过 `./init.sh`（编译、全量测试、前端构建）、专属报告存在检查、replay E2E及baseline对比。实际计数见完成证据：两处均1093项、0失败/0错误/1跳过；回放两处均3章9730字，基线PASS。

- 针对性先失败后通过日志：`/tmp/ncs030-selective-red.log`、`/tmp/ncs030-protocol-red.log`、`/tmp/ncs030-asof-red.log`、`/tmp/ncs030-impact-red.log`、`/tmp/ncs030-group-red2.log`、`/tmp/ncs030-reader-red.log`；对应GREEN见`/tmp/ncs030-repairs-green.log`、`/tmp/ncs030-reader-green.log`。
- 分支最终完整验证：`/tmp/ncs030-final-init.log`；端到端：`/tmp/ncs030-final-e2e.log`。合并后：`/tmp/ncs030-clean-main.log`、`/tmp/ncs030-clean-main-e2e.log`。
- 未修改prompt模板、注入上下文或golden；沿用匹配回放语料，无需重新录制或人工bless。

## 限制与只记录事项

1. Scenario整章候选的普通改写仅改变标题标记，T03证明隔离、影响范围、比较、事务与恢复，不证明真实模型已写出有文学说服力的“告密失败”。确定性门槛与真实模型质量不可混为一谈。
2. 手动连续性补查不刷新ReaderReview，界面已明确说明；旧报告不升级为当前稿报告。
3. 已知关系/词面覆盖不等于完整语义召回；未知保留。组合片段仍需作者审阅语义。
4. 既有前端大包构建警告仅记录，不进行无关性能重构；原有未跟踪文件保留。
5. T12真人盲评和真实长篇实验不在NCS-030；p40-05/06继续deferred，不据此声称百万字质量已验证。

干净main首次直接init因静态页面尚未生成，ApiCacheControlTest出现1项500断言失败；先在该干净目录执行前端构建，再完整init复验通过。此为已有构建顺序限制，仅记录，未复制工作区静态输出或修改无关实现。
