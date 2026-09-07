# 统一长篇人机协作实施计划

**Goal:** 一个持续任务推进小说，作者在同一工作台接手、修改并继续。
**Architecture:** 复用 NovelOrchestrator 的生命周期和完整写作循环；WritingTaskExecutor 统一执行与工作权；独立 CollaborationController/CollaborationBar 提供用户入口。
**Tech Stack:** Java 17 / Spring Boot 3 / SQLite / React 18 / Ant Design。

## 全局约束

- 按 docs/specs/2026-09-05-unified-author-agent-collaboration.md 顺序实施，每次一个 feature。
- 保留 Agent 间不直接调用、Prompt 外置、业务只依赖 SPI、人工版本与记忆失效链。
- 不重置逐章质量计数，不用自动批准跳过待审核项；暂停不改变小说阶段。
- 验证使用本机 JDK21，运行期 LLM 使用 Scenario/replay，不调用付费模型。

## p27-01 任务存储

修改 WritingTask、SqliteSchemaInitializer、SqliteNovelRepository。新增 AUTO_RUN、instruction、stopRequested，迁移默认 null/false；保存读取字段及原始时间。契约由 p27-02 测试覆盖。

```java
public enum Type { CONCEIVE, WRITE_CHAPTER, WRITE_ALL, AUTO_RUN }
public boolean isStopRequested() { return stopRequested; }
```

- [x] 增字段和兼容迁移，运行 `mvn compile`。

## p27-02 生命周期

修改 WritingTaskExecutor；新增 CollaborativeRunTest、CollaborationTaskPersistenceTest。

```java
public synchronized WritingTask startCollaborative(String projectId, String instruction)
public synchronized void stopCollaborative(String projectId)
public WritingTask latestCollaborativeTask(String projectId)
```

- [x] 先测试从 CONCEIVING 经 PLANNING 到 WRITING/FINAL、同项目重复启动、慢步骤内停止保留 owner、停止后 PAUSED、不批准 PENDING、预算阻塞、孤儿恢复和指令往返。
- [x] 测试失败后实现：获取 owner 后重验 gate，持久化任务再提交。构思规划最多若干步且每步必须有进展；写作复用完整循环。所有异常落 FAILED 并释放自己的 owner。AUTO_RUN 中断落 PAUSED，只有 FINAL 可 COMPLETED。
- [x] 执行 `mvn test -Dtest=CollaborativeRunTest,CollaborationTaskPersistenceTest`。

## p27-03 协作接口

新增 CollaborationController、CollaborationApiTest。GET 返回真实 busy/task/pendingReviews/canContinue/blockedReason；POST start/stop 委托执行器；PUT review-policy 在工作权内设置旧 mode/span，任何已有 checkpoint 不变。

- [x] MVC 测试覆盖空闲/忙碌/待审核/终态、非法 policy、指令续用、stop 幂等。
- [x] 实现四个端点，运行 `mvn test -Dtest=CollaborationApiTest`。

## p27-04 内容写入互斥

修改 ContentController、CheckpointController；新增 CollaborationEditOwnershipTest。

- [x] 用 latch 在人工 patch 内阻塞；同时启动 Agent，预期拒绝。先验证旧代码违反此断言。
- [x] 全部仅 rejectIfWriting 的写入改为 withChapterWork 包围读到提交；已有 ChapterRevisionService/OutlineStructureService owner 不重复包围。
- [x] 审核处理从 requirePending 到更新状态在同一 owner 内，去除内部重复获取。
- [x] 执行专属测试和既有内容/审核 focused。

## p27-05 全局协作栏

修改 frontend/src/types.ts、api/client.ts，新增 CollaborationBar.tsx。轮询每 2 秒，卸载清理并忽略旧请求；GET 失败保留草稿、禁止启动，允许重试。显示真实运行/停止/等待/失败/完成，提供 instruction、start/stop、审核节奏和审核导航。

- [x] 请求完成刷新状态；后台 running 与按钮 loading 区分，服务器无 owner 前不说可编辑。
- [x] 运行 `cd frontend && npm run build`。

## p27-06 工作台语义

修改 ProjectWorkspace、ProjectListPage、RunConsole。协作栏置于 Tabs 外；run tab 降为高级单步工具，默认打开章节工作区；移除侧栏旧模式/暂停/续写重复按钮。创建表单用审核节奏文案表达 mode 兼容值。

- [x] 内容组件不因普通轮询重挂载而丢本地草稿，阶段或进度变化刷新父项目。
- [x] 构建和 user-copy-check 通过。

## p27-07 验收

新增 scripts/collaboration-e2e.sh，按现有隔离 Scenario 启动方式运行。浏览器场景：构思前一次开始到 FINAL；审核处接手/编辑/批准/继续；刷新持久任务；UI 无两套模式。更新旧产品审计两个场景使用新入口且保留终态/正文/审核断言。

- [x] 完整 Maven、前端构建、协作浏览器、产品审核、mystery replay/baseline。
- [x] 最终审查并登记 feature evidence、progress 和 session-handoff（日期 2026-09-05）。

## p27-12 服务关闭后的作者恢复

依赖 p27-02；仅修改 WritingTaskExecutor 与 CollaborativeRunTest。统一协作在正常关闭时保留 PAUSED、阶段与指令，关闭标记阻止开始下一步骤或下一章。

- [x] RED：11 tests / 2 failures，复现中断错误落 FAILED、吞中断后继续到 COMPLETED。
- [x] GREEN：11/11 PASS，两个关闭场景均停在真实阶段等待作者。
- [x] p27-07 依赖此修复，最终代码重跑完整验收。

## 审查补充与收口

p27-08 至 p27-12 见 spec，均已完成；Phase 27 共 12 项 completed。最终 init 615 tests / 0 failures / 0 errors / 1 skipped；前端、28模板、25项 ego-browser、mystery回放和质量基线均通过。补充了每章/卷末审核、创作要求刷新续用与清除、接手后再次续写的 UI 验收。最终产物见 `data/verification/phase27/final/`，状态见 session-handoff.md。

## 2026-09-08 主分支重新验收

上文为历史实施计划与当时的工作区结果。当前事实以 feature_list.json 为准；本轮逐项独立分支、合并及主分支复验记录见 docs/harness/completions.json。草稿保护已增加真正后台章节推进的独立浏览器回归，本轮总门禁为 6 项协作 + 11 项产品审计 + 8 项旧入口 + 1 项草稿保护。各项实际结果记录在 docs/harness/evidence/reconciled-p27-07.json，不能用历史勾选代替本轮结果。
