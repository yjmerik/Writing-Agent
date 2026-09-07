# 2026-09-07 AGENTS.md 特性开发约束已完成

- 用户要求每次一个特性、新开分支、完成后合并。AGENTS.md新增完整硬规则，并同步Startup、Working Rules、Definition of Done和交接步骤；完成状态只在合并及合并后验证通过后登记。
- 每个feature从main创建codex/<feature-id>-<slug>；已有其他未提交工作时独立worktree开发；每个feature逐个完成闭环，不积压在长期分支。授权实现默认包含本地提交/合并，远端push和部署另需授权，用户的明确例外优先。
- 本次文档维护分支codex/docs-feature-workflow，实现提交05f7f2e已合入main。独立分支./init.sh通过，合并后干净main重新编译并运行636 tests / 0 failures / 0 errors / 1 skipped通过；未改业务实现，无需新增业务测试或质量E2E。
- 证据data/verification/agents-feature-workflow/verification.json。纯文档维护不新增业务feature，feature_list及原有业务状态保持；其他工作区文件已核对内容指纹保留。
- 当前分支main，本地合并完成，无blocker；没有推送远端或重启服务。下一特性直接遵循AGENTS.md的新流程。

以下为历史记录。

---

# 2026-09-07 合并完成：本地 main

- fallback实现提交c81bbcb6e499f0af43454cf5efa108551402d938已快进合并到main，并已切换当前工作区到main。该分支此前44个提交一并合入，没有冲突。
- 合并后的干净主分支独立验证：./init.sh编译通过，636 tests / 0 failures / 0 errors / 1 skipped，前端构建通过；3章replay共10191字/54次调用/0错误；compare-baseline PASS。
- 合并前完整开发工作区671项测试通过；两处计数差异来自未提交的Phase27–29功能，不是删除fallback测试。main提交的backlog为212 completed，原工作区完整backlog226 completed仍保留。
- 当前其他未提交修改全部保留；合并前后已逐文件核对内容指纹一致。本轮仅提交fallback相关实现和状态记录，没有提交其他模型切换/协作功能；也没有推送远端、重启服务或推进小说。
- 合并证据：data/verification/llm-provider-fallback/merge-verification.json；配置说明：docs/llm-provider-fallback.md。
- 下一步：如需远端发布，再执行push；如需运行中服务启用备用，按配置文档设置provider并重启。合并任务已完成，无blocker。

以下为历史记录，旧“未提交/未合并”的说明已被本记录取代。

---

# 2026-09-07 当前交付：多 LLM provider 自动 fallback 已完成

## 目标与结果

用户已确认方案并要求“开始实现”。本轮完成新provider配置、默认/任务有序路由、HTTP/SSE不可用分类、有界单次调用、冷却/并发恢复、流式防重复、实际模型计费和回放兼容。共新增49项测试（原622→671）。Phase30 p30-01至p30-12全部completed，项目共226 completed。

## 验证证据

- ./init.sh：mvn compile、mvn test全部通过，671 tests / 0 failures / 0 errors / 1 skipped；frontend build通过。
- 两个真实本地HTTP模拟provider验证402→备用成功、后续流式跳过冷却主模型，以及SSE半截结果丢弃后换模型。
- 使用匹配悬疑预设的data/e2e-fixtures/canary-mystery-20260831运行./scripts/e2e.sh：replay写3章10191字，54次调用，0 LLM错误、0章节失败。
- python3 scripts/compare-baseline.py：PASS；没有bless或修改golden基线。
- 证据入口data/verification/llm-provider-fallback/verification.json（输入指纹、命令与退出码）；同目录保存RED/GREEN、完整回归、E2E和对比日志。
- 首轮全量2个旧mock初始化错误已通过改用真实配置修复，原429有/无fallback断言保留并加强，最终全量已绿。

## 当前配置与下一步

- 使用说明：docs/llm-provider-fallback.md；设计：docs/specs/2026-09-07-llm-provider-fallback.md；计划：docs/superpowers/plans/2026-09-07-llm-provider-fallback.md。
- 新能力需要配置llm.providers与llm.routing，再重启生效。当前默认仍是既有Token Plan模型；仅加了说明入口，不自动启用付费备用，不自动重启现服务。
- 旧配置格式继续兼容；embedding独立。新provider每次请求最多一次，默认180秒请求预算、60秒冷却，全部冷却快速失败。
- 已发送正文token后失败只报告终态，不拼接另一个provider输出；complete内部SSE聚合可以丢弃半截响应再切换。
- 下一步若用户给出实际主备provider，按文档配置并验其协议和真实输出质量。当前没有开发blocker，也没有待确认的设计审批。

## 工作区与保留事项

在/Users/erik/ZCodeProject/WritingAgent原codex/product-design-consistency-fixes分支工作；没有创建/切换分支、提交、推送、部署。已核对先前非LLM源码修改指纹未改变。当前小说项目、数据库、运行中服务及先前Phase27–29未提交修改保留，没有推进小说写作。

以下均为历史记录，旧“方案待确认/尚未实施”状态已由本交付取代。

---

## 2026-09-07 最新任务：LLM provider 自动 fallback（方案待确认）

- 用户新增开发需求：允许配置多个 LLM provider，检测不可用时自动切换；本任务可以开发该能力，历史小说体验“不修复”约束不应误用为本任务禁令。
- 已调研配置、三条 HTTP/SSE 调用路径、旧 429 降级、观测计费和回放链。当前配置均为相同 Token Plan 模型，旧默认降级实际无不同目标。
- 方案：docs/specs/2026-09-07-llm-provider-fallback.md，命名 provider + 默认/任务有序链 + 不可用分类 + 请求预算 + 冷却恢复 + 流式防重复 + 实际 provider 计费。embedding 独立，旧配置兼容，不自动启用付费备用。
- feature_list 已进入 Phase 30，共221项：214 completed、7 not-started（p30-01至p30-07）。尚未修改功能实现，不宣称 fallback 已完成。
- 基线 ./init.sh PASS：Tests run: 622, Failures: 0, Errors: 0, Skipped: 1；编译、前端构建通过。日志 /tmp/writingagent-fallback-init-unrestricted.log，输入指纹 /tmp/writingagent-fallback-baseline-evidence.json。首次沙箱运行因本地 socket 和 Mockito attach 限制产生214 errors，已获工具授权正常重跑通过，无基线代码修补。
- Blocker：brainstorming 技能明确要求书面 spec 确认，待用户确认上述方案；下一步读取 writing-plans 技能生成实施计划，逐个 feature 写测试/开发。涉及现有缺陷修复先补 failing test；结束需全量compile/test和E2E/compare-baseline，不自动bless。
- 保留此前未提交改动与运行中的小说服务/项目，没有提交、重启或推进小说。

---

# 会话交接 — 2026-09-07

## 当前目标与用户最新范围

用户明确“不依赖200章完全写完，能发现功能和结构的共性问题就可以收敛”。本次已经从空项目体验到23章，正在结束观察并交付共性问题报告。**不要自动继续写24—200章，不创建定时观察，不进入修复阶段。** 先前原200章全流程目标已由这条新指令调整；项目本身仍保留200章目标和首卷25章计划，没有删除或缩改内容。

## 本轮已完成

- 按用户授权，默认及8类文本任务切换为百炼Token Plan qwen3.8-flash，使用OpenAI兼容专用地址；scripts/load-env.sh只读复用本机ZCode匹配套餐密钥，专用环境变量优先。没有回显/落盘密钥，不修改ZCode。
- 配置实现文件只有application.yml、load-env.sh、新增TokenPlanModelConfigurationTest。向量仍qwen3.7-text-embedding/1024；Agent、prompt和既有体验缺陷未修复。
- 配置3测试通过，init全量622（0失败/0错误/1跳过）、前端构建及package通过；22:03:21加载启动环境后mvn compile test再次同数全绿。直接未加载环境跑过一次，88个缺套餐key导致的上下文启动错误，已通过正确加载方式排除，无实现修补。
- p29-01 completed，feature_list Phase29，共214项completed。本次体验问题不算已修复，也未伪登记成completed功能。
- 原项目从20章余额不足恢复到23章82750字。第21章v3/3658字、第22章v3/6681字、第23章v3/5531字。重复场景、检查版本失配、计划失效和长期记忆污染已留逐版证据。
- 主报告已重写为共性问题收敛版，8类优先问题、复验标准、结构性改进顺序和未覆盖边界。旧过程报告及交接历史另存，不能将历史“继续200章”当当前要求。

## 最终状态（22:10）

22:08:21界面已交给作者，23章82750字，第23章v3/5531、B7/43项1HIGH，待复核状态保留；未标处理、未继续24章。接手耗时约10分21秒。首章v6/2769与确认稿逐字一致，1—23章连续，字数合计一致；编辑弹窗已取消。证据137-integrity、138-final-ui。

全部只读观察已结束：94523通过停止标志正常结束，55860在交还后自然退出，没有定时任务或活动生成。仅应用服务33508/68383保留。ego task142已完成清理，completeTaskSpace返回done:true；主报告和验证材料已齐全。

## 项目与工具

- 工作区：/Users/erik/ZCodeProject/WritingAgent。
- URL：http://localhost:18098/#/project/3307c2d7-6aa9-4b63-9448-0fc9108afbf2。
- 运行PID33508、启动会话68383；保留现服务，无需再次重启。8080未动。
- 数据库data/ui-experience-20260905/novel.db及对应qwen3.7索引，不直接读写。
- ego task142及其两页已按技能完成关闭。若后续开启新的修复回归，先确认是否属于新任务，再按技能选择/建立工作区；业务体验仍只用UI。
- 仓库已有大量先前Phase27/28未提交修改；不要reset或将它们归成本轮模型改动。无提交/合并/部署动作。

## 证据入口

- docs/reports/2026-09-05-ui-experience/体验报告.md：最终主报告。
- 同目录全流程续测-2026-09-07.md、章节观察索引.md、chapter-NNN-vX-visible-first.txt：当日逐版证据，章节卡含UI文字，不是小说导出。
- docs/specs/2026-09-07-qwen38-token-plan-configuration.md：配置范围与验收。
- data/verification/qwen38-token-plan/convergence-verification.json、final-loaded.log：最终工程验证。
- 原作者首章校验基准：39-before-successful-edit.txt前加“2025年6月，”，应2769字/v6；20章时已逐字通过111证据。

## 后续（需新的工作指令）

本次按共性问题发现收敛；卷末/跨卷/FINAL/全书导出未验证，不声称通过，也不据未覆盖断言缺功能。若后续修复，优先作者稿保护、计划约束、评审版本与重写闭环，再处理记忆分级和长任务状态，按AGENTS先登记spec/bug和复现测试。
