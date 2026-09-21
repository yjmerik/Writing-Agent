# Spec: 大纲伏笔的时间信息与当前章上下文

## 背景与动机

NCS-033授权重录中，原计划第2章埋设、第5章回收的“陆三行墓志铭”被持久化为planted_at_chapter=1、expected_resolve_chapter=NULL；回收行又被建成另一条未回收伏笔。首章连续性检查因此报告HIGH。代码extractForeshadowingsFromOutline拼接全大纲后统一以1构建伏笔，collectSettingsForCheck不按当前章排除未来条目。证据docs/harness/evidence/ncs033/foreshadow-chronology-discovery.json；录制尚在进行，此发现不等于本轮终态。

## 目标

保留大纲伏笔来源章号与计划回收节点；未来计划不得作为当前章已经发生、必须立即回收的事实注入。

## 非目标

不通过降低严重度掩盖真实矛盾；不把大纲写有“回收”直接等同于正文已回收。不在NCS-033分支顺带修改该独立问题。

## 验收标准

- NCS-061：第2章埋设、第5章回收的条目保存正确来源与回收节点；回收行不再新建重复未回收条目；不具备来源章号的信息不伪装成第1章已埋设。先failing test再修复，运行`mvn test -Dtest=ForeshadowOutlineChronologyTest`。
- NCS-062：真实写作及连续性检查请求在第1章不含第2章的未来伏笔作为已发生事实；到相应章节按计划/事实语义使用，不丢失已埋设伏笔。先failing test再修复，运行`mvn test -Dtest=FutureForeshadowContextTest`。
- 均需编译、全量测试；涉及上下文变化须匹配录制、双回放、对比及人工确认，不用旧语料缺失当作质量通过。

## 影响面

NCS-061：NovelOrchestrator大纲伏笔抽取及专项测试。
NCS-062：ContextAssembler、ChapterProduction当前章上下文与专项测试。
不更改schema；正确使用已有plantedAtChapter、expectedResolveChapter等字段。正文确认机制若需要另行调整，另登记特性。

## Feature 拆分

| id | name | 依赖 | verify_cmd |
|----|------|------|------------|
| NCS-061 | 保留大纲伏笔时间信息 | [] | `mvn test -Dtest=ForeshadowOutlineChronologyTest` |
| NCS-062 | 排除被冒充当前事实的未来伏笔 | NCS-061 | `mvn test -Dtest=FutureForeshadowContextTest` |

## 风险与备选方案

必须区分计划、正文已埋设及正文已回收；过滤未来内容不能顺带丢失当前已发生的约束。样本中的检查误报不能靠反复录制碰运气解决。

## 2026-09-21 定点修复补充

原来源章与连续性检查的未来信息过滤已随 `3ce8cd6` 合入。本轮继续录制报告暴露出：括号内分号被当成条目分隔符，时间箭头中的埋设章被误当回收章。NCS-061补齐这两处解析修复与真实格式回归，不迁移或篡改旧失败证据库。

本轮完成口径遵循 `2026-09-21-ncs-focused-fixes.md` 的用户限定范围：代码缺陷验证后合并，模型输出不稳定登记；不重新真实录制、解除旧屏障或bless。

## NCS-062 完成范围

续验发现写作端 `ContextAssembler.buildForeshadowings` 尚未过滤未来章；补齐与已有连续性检查相同的来源章过滤。卷末审计沿用已有字段：排除预计回收章晚于卷末的条目；无期限条目保留提醒，不当作到期数量；明确到期未回收超过5条仍为HIGH。未更改全书FINAL审计，也未自动确认正文回收。

新增失败复现覆盖写作上下文及四组卷末组合：明确到期、未来到期、无期限、混合。生产改动只有既有上下文筛选和审计过滤/计数，无新服务、状态或数据库表。
