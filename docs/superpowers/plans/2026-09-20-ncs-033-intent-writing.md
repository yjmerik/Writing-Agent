# 写作上下文接入创作约定 Implementation Plan

> **For agentic workers:** 使用executing-plans；本项完成前不开始下一特性。

**Goal:** 通过现有保存接口填写约定后，真正的ChapterWriter请求带有对应版本的完整契约。

**Architecture:** MemoryConfig显式注入AuthorIntentContextService；ContextAssembler每次组装读取最新契约，放入不可静默裁剪的作者要求区。合并检查约定与现有作者要求的预算，超限不发送模型请求。空契约逐字兼容原请求。

**Tech Stack:** Java17/Spring Boot3/SQLite、既有PromptTemplateService。

## Global Constraints

生产改动2文件，测试1文件。SPI隔离不变。不改旧模板变量和模型配置，不用ThreadLocal隐式改变所有LLM请求。prompt变化须真实录制、双replay、新旧对比及人工确认；不能只测空约定宣布新增能力已验收。

### Task 1: 实际请求集成

Files: memory/ContextAssembler.java、memory/MemoryConfig.java、service/AuthorIntentWritingTest.java。
Interface: `setAuthorIntentContextService(AuthorIntentContextService service)`；render结果追加既有authorDirectiveSection。

- [x] RED：Spring真实装配，MockMvc PUT两版约定；真实ChapterWriter发请求到受控网关时断言新视角/样本/修订号，另一作品无串入；空约定字节相等；预算不足不产生正文。
- [x] GREEN：MemoryConfig传入服务；assemble调用render(projectId,maxContextTokens)，与现有要求合并；TokenBudget估算保护区超过预算立即抛错；保留原裁剪顺序。
- [x] Run `mvn test -Dtest=AuthorIntentWritingTest`和对应ChapterWriter/ContextAssembler回归，`./init.sh`。
- [ ] 既有replay与baseline对比，单独保存非空约定的真实模型录制和双replay证据，展示质量差异；需要人工确认时保持in-progress。
- [ ] 授权范围内提交合main，干净main复验；人工质量确认后bless并登记完成。若须先合入后record，合入仍保持in-progress，不能开始下一项。

## Branch verification

RED AuthorIntentWritingTest 3项2失败（约定未进请求、超限不拒绝）；GREEN及相关回归15项通过。init 1101/0F/0E/1S，编译及build通过。旧语料replay 3章9730字、54次LLM回放、0错误、baseline PASS。日志/tmp/ncs033-red.log、/tmp/ncs033-green.log、/tmp/ncs033-final.log、/tmp/ncs033-old-replay.log。接下来按AGENTS.md合入后录制非空约定语料、双replay；人工质量确认前保持in-progress，不bless。

## Main verification / pending authorization

实现4cb8f7ccd9759de37f8598fae8f663dc69a91f22已合main，干净main init 1101/0F/0E/1S，旧replay及baseline PASS。真实record被自动审批拒绝，已准备并提交recording-scope，等待用户授权。不得bless或登记完成。验证摘要见docs/harness/evidence/2026-09-20-ncs-033-pending.json。
