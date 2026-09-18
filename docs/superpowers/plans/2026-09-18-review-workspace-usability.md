# 审阅工作区修复 Implementation Plan

> 按用户反馈继续修复，单代理按顺序执行。独立分支 codex/p42-01-review-workspace，现有隔离工作区，不触及运行中服务直至验证并重新打包。

**Goal:** 审阅操作有明确响应，正文宽敞，长意见可读。
**Architecture:** 工作区保持路由权威；受控reviewPanelOpen在点击事件直接打开唯一完整抽屉；readingMode统一收起侧栏/目录。
**Tech Stack:** React18、Ant Design、Playwright、Spring Boot。

## Global Constraints

只改3个实现/测试文件；不动业务审核规则、prompt或用户小说。先失败测试后修复。规范提交、合并、干净main复验后登记完成。

### Task 1: 浏览器复现与修复

Files: frontend/src/pages/ProjectWorkspace.tsx、frontend/src/components/InteractiveReview.tsx、qa/collaboration/review-workspace.spec.ts。

- [x] 新增测试：`await page.getByRole('button',{name:/^审阅内容/}).click(); await expect(page.getByRole('dialog',{name:/审查面板/})).toBeVisible()`；验证二次打开、跨页签待审、正文宽度、专注阅读及390px长报告。
- [x] 在原实现上运行专项记录RED；报告展示用固定长文本响应，路由与点击走真实前端。
- [x] Parent增加reviewPanelOpen与readingMode；待审导航一次navigate到`/project/${id}/chapter/${chapterIndex}`清除旧tab/version；打开信号不重置组件草稿。
- [x] Child保留一份完整审查面板作为Drawer内容；正文两列，标题/操作分行，评分逐行，问题标签与描述上下排列；段落操作另起一行。
- [x] 运行专项、全部浏览器、构建、编译测试；检查桌面/窄屏截图。
- [ ] 提交p42-01、ff合main、干净检出完整复验；登记完成证据。
- [ ] 打包并替换8080旧服务，只读查看凤鸣九州，交给用户人工复核。
