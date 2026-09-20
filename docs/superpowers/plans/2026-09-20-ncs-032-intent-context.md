# 版本化创作约定渲染 Implementation Plan

> **For agentic workers:** 使用executing-plans，独立分支与完整main闭环。

**Goal:** 将最新持久约定渲染为完整、带版本且受预算约束的上下文。

**Architecture:** AuthorIntentContextService经NovelRepository读取，使用PromptTemplateService.render("author-intent", vars)。不在网关按ThreadLocal偷偷注入；本项建立渲染合同，下项显式接入写作。

**Tech Stack:** Java17、SQLite、外置.st模板。

## Global Constraints

1–3个实现/测试文件；保留旧模板变量；空约定合法；不提升自由文本的写入授权；新约定不得受旧缓存污染。

### Task 1

Files: service/AuthorIntentContextService.java、resources/prompts/author-intent.st、service/AuthorIntentContextTest.java。
Interface: `String render(String projectId, int maxTokens)`；无内容返回空串，负预算拒绝，非空超预算抛出IllegalStateException。

- [x] RED：真实SQLite存约定，用实际模板渲染，断言八字段、修订号、模板字面量保持；新版替换旧版、作品隔离，零预算阻断非空内容。旧实现缺少渲染服务，应断言失败。
- [x] GREEN：读取一次最新约定，render外置模板，完整文本估算超限即阻断；不截字段、不改变权限。
- [x] `mvn test -Dtest=AuthorIntentContextTest`通过；改为类型化调用保留同样断言。
- [ ] `./init.sh`，明确提交三业务文件及状态，ff合main，干净main复验。
- [ ] 登记verified。未接生产请求前不宣称创作约定已进入写作；NCS-033及后续执行真实请求/record验收。

## Branch verification

基线init 1094/0F/0E/1S。RED 4项4失败（缺渲染服务）；GREEN 4项通过。分支init 1098/0F/0E/1S，compile与build通过；日志/tmp/ncs032-red.log、/tmp/ncs032-green.log、/tmp/ncs032-final.log。新增模板尚未注入运行请求，不改变现有fixture。
