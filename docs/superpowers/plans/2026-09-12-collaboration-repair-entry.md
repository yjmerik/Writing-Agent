# 协作系统修复执行入口 Implementation Plan

> **For agentic workers:** When implementation is authorized, select an allowed execution method: use superpowers:subagent-driven-development when delegation is permitted and useful for independent tasks; otherwise use superpowers:executing-plans. Steps use checkbox (`- [ ]`) syntax for tracking. A plan-only request does not authorize implementation.

**Goal:** 外部工具只读取仓库即可定位复验缺陷、选择一个可开始的feature、实现并验证完整闭环。

**Architecture:** 新增明确版本身份、SQLite原子审计和持久候选/成书版；UI消费同一权威状态，真实浏览器和故障注入验证实际作者路径。

**Tech Stack:** Java17 / Spring Boot3 / React18 / TypeScript / SQLite / JUnit + MockMvc / 独立Playwright QA包。

## Global Constraints

- 本轮只设计与登记，58项均not-started；不声称已修复。
- 本入口可移植：所有路径从仓库根解析，不需要Codex会话、个人visualizations目录或旧验收数据库。
- 先读AGENTS.md、feature_list.json、progress.md、session-handoff.md、总规格，再读本项所在阶段计划。
- 单特性单分支、bug先RED、完整编译/测试、独立提交合入本地main、合并后验证再登记completed；不推送/部署。
- 不自动委派；执行者只有在用户或适用规则允许时才可使用子代理。顺序依赖优先，不能并行修改共享状态或数据库。

## 文档导航与优先级

| 顺序 | 文档 | 作用 |
|---|---|---|
| 1 | [复验事实](../../reports/2026-09-12-collaboration-ui-reverification.md) | 明确已通过、仍失败、未验证 |
| 2 | [总规格与契约](../../specs/2026-09-12-collaboration-repair-program.md) | 身份、API、数据库、状态与覆盖规则 |
| 3 | [Phase36](2026-09-12-collaboration-repair-p36.md) | 13项：误批准、导航、草稿、预览、诚实状态 |
| 4 | [Phase37](2026-09-12-collaboration-repair-p37.md) | 16项：持久候选、作者意见、逐章证据 |
| 5 | [Phase38](2026-09-12-collaboration-repair-p38.md) | 13项：计划、裁决、固定成书版本 |
| 6 | [Phase39](2026-09-12-collaboration-repair-p39.md) | 10项：事实来源、知识时点、人物影响 |
| 7 | [Phase40](2026-09-12-collaboration-repair-p40.md) | 6项：慢任务/重启、运行详情、真实实验与总验收 |

优先修复安全漏洞：p36-02→03→04→07；随后p36-01→05，p36-12/13迁移测试后→06完成前后端闭环。p36-08/09/10/11可按依赖逐个推进。之后按dependencies拓扑顺序，不能因为编号小就越过编号大的前置任务（例如p37-03依赖p37-14，p38-06依赖p38-10）。

原A1空tags和A3旧稿警示核心复现已通过，保留回归；p35-02候选线索提升/拒绝已有实现，作为E2E-13完整回归，不再重复登记实现；不要再次造同名feature。Phase35历史完成记录保留，本次更强验收另记新项。current_phase=40表示已登记的最高阶段，不表示已开始Phase40。

## 外部工具启动检查

```bash
pwd
git rev-parse --show-toplevel
git status --short
git branch --show-current
git log --oneline -5
python3 scripts/check-harness.py --check-git
```

- [ ] 确认与用户指定同一仓库。已有其他未提交工作时建独立worktree，保留原内容；不要批量暂存。
- [ ] 从feature_list找到唯一in-progress并核对原分支；若无则从依赖completed且main包含其提交的not-started选一项。
- [ ] 读该feature的execution_plan、acceptance、verify_cmd、files与quality_gate；本文中的新增测试/接口是待创建产物，不能把“文件不存在”当已验证失败。
- [ ] 新建 `codex/<feature-id>-<slug>`，仅该feature设in-progress，active_feature同步；运行`./init.sh`。环境限制需要正常本机端口与Mockito附加，不把环境失败记产品回归。

当前首选 `p36-02`；或者先做 `p36-01` 浏览器测试设施，二者无业务依赖。

## 代码导航

| 当前入口 | 需理解的行为 |
|---|---|
| web/ContentController.resolveManualReview | 现无版本，读最新章后直接DONE/resolve全部屏障 |
| web/CheckpointController.approve/modify | 第二套批准/修改推进路径，章节类必须关闭旁路 |
| service/ChapterRevisionService.doEdit | 统一正文入口、幂等、快照、事务后rebind目前吞异常 |
| persistence/sqlite/SqliteNovelRepository | 自建连接事务；不能外层事务里调用另开连接方法 |
| service/AgentRevisionService.previews | 现ConcurrentHashMap，重启即丢；来源指纹与分目标apply |
| web/DirectiveController | 现意见CRUD和任意文本证据；业务生成尚未消费 |
| memory/ContextAssembler / WritingContext | 作者要求注入、预算和稳定序列化入口 |
| service/PlanEditionService | 现根据目标直接裁剪，无持久预览凭证/原子多表提交 |
| components/InteractiveReview | 目录只改selectedIdx，草稿/报告/两套审查JSX |
| components/AgentRevisionDialog | instructions/preview现仅内存、JSON对比 |
| components/BookReviewView | DONE错误映射批准；当前导出非固定成书版 |

以上Java路径前缀`src/main/java/com/writing/novel/`，前端前缀`frontend/src/`。

## 测试环境，不依赖作者已有20章小说

### 后端专项

在bash会话先`source scripts/load-env.sh`，再执行各项Maven verify_cmd；不打印环境变量。每个JUnit使用独立临时SQLite目录、StubLlmGateway或scenario，禁止连作者库。测试fixture允许通过Repository/API建立合成状态，但生产API行为必须真实调用；并发测试用两个连接/两个请求及CountDownLatch，不用睡眠制造“看似并发”。READ/ACT/ASSERT三段：记录已读身份→另一个动作改变身份→原动作提交→读最终状态及审计。

### 浏览器环境

p36-01先创建独立QA包并将依赖精确版本写进package/lock；后续工具只用`npm ci`。浏览器驱动不进入生产bundle。

```bash
# p36-01首次建立，执行时将解析版本固化进lockfile
npm --prefix qa/collaboration install --save-dev --save-exact @playwright/test
npm --prefix qa/collaboration exec -- playwright install chromium

# 后续执行者复用锁文件
npm --prefix qa/collaboration ci
npm --prefix frontend run build
```

使用一个新的临时目录；保留此终端直到测试结束：

```bash
QA_RUN_DIR=$(mktemp -d)
export QA_RUN_DIR
bash -c 'source scripts/load-env.sh && mvn -q spring-boot:run -Dspring-boot.run.arguments="--server.address=127.0.0.1 --server.port=18083 --llm.scenario.enabled=true --llm.scenario.harsh=true --persistence.embedding=stub --persistence.sqlite.db-path='"$QA_RUN_DIR"'/novel.db --persistence.vector-store.index-path='"$QA_RUN_DIR"'/vectors.idx"'
```

另一个终端（确认18083本轮服务就绪后，不要占用/杀掉用户原服务）：

```bash
export QA_BASE_URL=http://127.0.0.1:18083
export QA_ARTIFACT_DIR=/tmp/writingagent-collaboration-qa
npm --prefix qa/collaboration test -- smoke.spec.ts
```

QA fixture通过真实API创建：空tags互动项目→协作start→初始设定批准→协作start，scenario首章人审；7章导航/24→20计划另建SOLO合成项目，用POST chapters每章500字以上占位文本及大纲API准备。版本/屏障缺陷的后端故障fixture可以直接在临时库通过repository建立，必须与作者动作分开说明。浏览器验收不能改数据库伪造点击结果。fixture函数在各spec的beforeAll中实现；只复用合成文本，不复制私人小说。

每个spec明确设置桌面1280×900与窄屏390×844，错误日志、截图/trace/下载hash写QA_ARTIFACT_DIR；新测试断言取实际DOM，不靠固定坐标。主页面pageerror与未解释console.error必须失败。测试不调用真实外部LLM。

## QG — 质量与回放门禁

带quality_gate的feature除专项与全量外：

```bash
./scripts/e2e.sh --mode replay --preset mystery
python3 scripts/compare-baseline.py
./scripts/diagnose.sh data/e2e/events.jsonl
```

先在该feature的隔离worktree中保留旧golden/报告/fixtures，确保未使用用户原data/e2e目录。脚本会重建data/e2e，因此不要在作者正在使用该目录时运行。UI-only不得借机会bless。

若prompt/上下文改变导致旧语料分叉，按AGENTS.md在合并后重建匹配语料；真实模型调用需执行会话已有授权，否则记录待授权边界，不能用scenario替代质量结果。模型/embedding配置由load-env.sh加载，不输出凭据。

```bash
export E2E_FIXTURE_DIR=data/e2e-fixtures/mystery-YYYYMMDD-feature-id
./scripts/e2e.sh --mode record --preset mystery
./scripts/e2e.sh --mode replay --preset mystery
# 保存本次结构化结果后再跑第二次；比较规范化质量字段与调用指纹，不比较时间戳
./scripts/e2e.sh --mode replay --preset mystery
python3 scripts/compare-baseline.py
```

`YYYYMMDD-feature-id`在执行时用当天日期和实际feature替换，这是运行参数，不是固定共享目录。记录新旧报告、章数口径、调用数、失败分类及变化原因，回放两次一致后给人类查看。AGENTS.md要求“展示新旧质量对比并经人工确认后 … --bless”；取得对该质量对比的人工确认才运行：

```bash
python3 scripts/compare-baseline.py --bless
```

无当前批准/无完整语料时保持in-progress，不把只跑1章屏障的回放称作20章文学验证。影响多个题材的上下文改动至少补mystery与wuxia固定样本，并在证据中明确功能冒烟与质量实验的不同口径。

## 全链路验收用例矩阵

| ID | 输入与动作 | 必须观察到的结果 |
|---|---|---|
| E2E-01 | 新建仅标题构思、tags为空，开始协作 | 无NPE，进入初始设定审阅 |
| E2E-02 | 第7章/outline切换、刷新、前进后退、后台新增章 | URL/页签/选中章一致，不抢定位 |
| E2E-03 | 标题/正文草稿、刷新、B升版、存储失败 | 两字段恢复；冲突可找回，配额失败明确提示 |
| E2E-04 | A看v2，B保存v3，A从正文/卡/抽屉/旧端点批准 | 全部拒绝旧身份，v3屏障仍阻塞；A重新审阅后可批 |
| E2E-05 | 三意见生成候选、刷新/重启、恢复、双apply | 意见/候选不丢，正文只推进一版，JSON不作主界面 |
| E2E-06 | 三意见中两项实现一项UNMET；HARD跨章 | 逐项原文证据与原因、只可确认当前稿，持续要求不消失 |
| E2E-07 | 旧报告、误报/例外/延期、重新检查中编辑 | 旧报告警示与有效裁决版本一致；过期返回不能冒用 |
| E2E-08 | 24→22预览改20、另窗口改变计划、再预览处置 | 旧token无效；承诺逐项决定；成功后20章且旧保存不复活章纲 |
| E2E-09 | 事实asOf7/10、角色9章才知情、来源v2 | 历史时点正确、不泄露未来，来源确实是v2 |
| E2E-10 | 人物改动预览、取消、来源变化后确认 | 取消无写入；冲突保留输入；不自动改历史正文 |
| E2E-11 | 当前缺批准→补齐→创建批准edition→编辑章→再导出 | 缺条件阻塞；批准版每章hash及文件字节不变 |
| E2E-12 | 慢不可取消模型、接手/断线/重启，390px键盘完成操作 | 晚结果不覆盖，当前owner不丢，操作可达，运行状态诚实 |

| E2E-13 | 既有候选线索面板提升/拒绝、刷新、390px键盘操作 | 提升进入正式台账且不重复；拒绝持久保留；移动端入口可达 |

另有STUDY-01：两题材各20章、两条件、每章≥3份真实独立评分及作者耗时。程序验证样本完整性，实际读者给评分；缺数据标INCOMPLETE，不能由Agent代填。

## 每项证据与恢复记录

```json
{
  "featureId":"p36-04",
  "baseCommit":"实际起始提交",
  "implementationCommit":"实际实现提交",
  "branch":"codex/p36-04-versioned-review-api",
  "red":{"command":"mvn test -Dtest=VersionedReviewDecisionTest","exitCode":1,"failure":"旧稿请求返回200而非409"},
  "green":{"command":"同一专项命令","exitCode":0},
  "postMerge":{"commit":"实际main包含实现的提交","cleanCheckout":true,"checks":[]},
  "limits":["未声称真实文学实验已运行"]
}
```

此JSON是字段例子，不能原样作为完成证据；正式verified记录按docs/harness/README.md，checks必须包含实际命令/结果/exitCode且非空。原始日志含私密数据时只提交脱敏摘要与hash。日期必须当天；未完成保持in-progress并写明确下一步。失败新分类走diagnose→spec/backlog→failing test，不顺手修其他feature。

## 本地闭环与停止条件

- [ ] 专项/全量/受影响检查通过；检查暂存差异只属于当前feature。
- [ ] 检查`git log --oneline main..HEAD`及`git diff --stat main...HEAD`。main前进则先同步并重新验证。
- [ ] 按仓库协议本地合并；干净main重跑`mvn compile && mvn test`、verify_cmd及受影响检查。
- [ ] `git merge-base --is-ancestor <implementationCommit> main`成功；新增verified证据/完成记录，再标completed。
- [ ] 任何关键流程缺证据都不宣称完整修复。最终功能与STUDY全部满足p40-05后才通过原方案完整验收。

外部工具接到“按方案开始实现”的明确指令后，先执行上述启动检查并选定一个feature。无需用户再次解释本轮复现经过；遇真实预算/人工评分/质量bless等外部前置条件时明确报告已完成的部分和具体缺失项。
