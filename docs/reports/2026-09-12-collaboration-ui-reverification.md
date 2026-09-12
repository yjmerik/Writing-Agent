# 协作 UI 修复复验 — 2026-09-12

## 结论

**未完整修复。** 对 main `b1c8289aee7e3b7ceb48472d819b5faa799cf195` 实际运行与浏览器复验；构建与单测通过不代表原验收完整交付。

## 环境与证据边界

- 本机隔离服务，SQLite backup复制验收数据库；只操作副本，原小说未改。
- 显式scenario/harsh/stub embedding，不调用真实生成模型；桌面和390×844内置浏览器交互，截图确认窄屏问题明细。
- 全量`./init.sh`成功：736 tests / 0 failures / 0 errors / 1 skipped，编译/前端构建通过。首次沙箱Mockito附加和端口绑定受限，正常环境重跑通过，不视为产品回归。
- `check-harness.py --check-git`与六个verify-ui脚本通过；四个脚本主要检查源码字符串，另外两个只测独立草稿/路由函数。
- 不含文学实验、完整网络故障、慢模型晚写专项；不把未测标为通过。

## 复验矩阵

| 项目 | 结果 | 事实 |
|---|---|---|
| A1空标签构思 | 核心复现通过 | 原tags=NULL失败项目重新开始，正常进入设定审阅并生成首章 |
| A2草稿/位置 | 部分通过 | 正文标记能恢复；标题丢失；目录第7章刷新仍第1章 |
| A3旧报告 | 核心复现通过 | 当前v6明确提示报告针对v2；裁决闭环仍缺 |
| A4批准版本 | 未通过 | A显示v2、B正式保存v3、A“已处理”仍使v3 DONE及屏障RESOLVED |
| A5候选/意见 | 未通过 | 三条意见候选刷新消失，再打开输入为空；仍原始JSON；意见未接生成落实 |
| A6窄屏 | 部分通过 | 390px抽屉显示具体问题，但逐条裁决/意见落实不完整 |
| 计划预览 | 有新入口但有缺陷 | 22预览后改20，旧预览和确认按钮仍有效 |
| 成书 | 部分交付 | 当前稿清单/导出存在，无固定成书批准与版本化导出 |

## 关键复现与定位

### 旧窗口放行新稿（高）

1. 合成互动项目首章v2，NEEDS_MANUAL_REVIEW。
2. A显示v2；B通过正式PUT接口携expectedVersion=2保存v3。
3. workspace待办正确重绑v3、canContinue=false；A正文仍v2。
4. A点“已处理”显示成功；只读核对v3/status=DONE、屏障v3/RESOLVED。

`frontend/src/api/client.ts` resolveManualReview不传版本；`web/ContentController.resolveManualReview`读最新章直接放行。`ResolveReviewConcurrencyTest`中“屏障稿号与当前章不符时放行被拒并保留屏障”实际没有发送放行请求，只验证rebind，故不覆盖此漏洞。

### 草稿、路由与候选

- InteractiveReview目录onClick只setSelectedIdx；ProjectWorkspace只有顶部待办动作同步路由。
- 标题onChange只改内存；正文修改才saveDraft；打开编辑器忽略缓存标题。draftStore版本不同返回null，缺显式冲突保留。
- AgentRevisionDialog instructions/preview只useState，打开清空；服务AgentRevisionService也用内存Map，需同时解决刷新与重启。

### 其他交付差距

- AuthorDirective仅CRUD/手工证据端点；agent/memory/orchestrator/service未读取该账本注入生成，也无自动逐项证据链。
- OutlineView目标变化不清旧预览，apply读取当前newTarget；确认后未刷新旧draft可导致随后保存复活章纲。
- BookReviewView把DONE显示“已批准流程完成”；DONE可来自自动或手工内容，不能代表作者批准。book-export读取动态chapters。
- 人物影响预览、来源时间线/知情边界、误报/例外/延期、运行详情与真实文学实验不能因Phase35完成而视为已交付。

## 下一步

完整可执行修复设计见[总规格](../specs/2026-09-12-collaboration-repair-program.md)，外部工具从[执行入口](../superpowers/plans/2026-09-12-collaboration-repair-entry.md)启动。

该复验记录只包含脱敏事实，重现不依赖原作者项目ID/小说正文/个人工具目录。
