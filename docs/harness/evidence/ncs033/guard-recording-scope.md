# NCS-033 最终修复版本的新录制范围（已授权，待修复验证后启动）

2026-09-20。本地main实现提交：8160ef37fabb792f4f48cbdff521f721265e9b74。严格检查结果、纠偏成功反馈、原问题逐项复验及仲裁阻断已合入；分支与干净主分支1138测试0失败0错误1跳过，专项47项通过，编译/前端构建通过。

上一轮“允许”对应的单轮录制已执行。它确认了原稿/约定注入，但暴露守卫首轮问题丢失，agent为避免继续消耗主动中断。保留27条调用和首章4470字；没有达成3章目标。新修复改变纠偏指令和调用序列，旧语料在CHAPTER_WRITING-6391b145cf472c17-1.json处分叉，不能验证最终版本。

用户2026-09-20明确授权“先修复问题，再重录”。完成当前质量拦截及逐项复验修复后，执行以下一轮：
- 固定百炼Token Plan端点 `https://token-plan.cn-beijing.maas.aliyuncs.com/compatible-mode/v1` 和 `qwen3.8-flash`，禁用UI模型覆盖。
- 输入为既有author-intent.json人工测试约定、流程生成的测试世界观/人物/大纲/正文及审校问题；不使用用户已有作品。embedding为本地stub。
- 一次3章目标录制，可能再次消耗套餐/API额度；没有可靠的硬金额或耗时上限。不会遇失败后不断重新采样；人审暂停不自动放行。
- 新目录 `data/e2e-fixtures/ncs033/mystery-20260920-quality-closure`，保留前两轮语料。完成后两次replay及质量对比，无额外真实调用。
- 质量未达标则如实记录；即便自动指标通过，仍须人工评阅确认后才能bless，当前不更新golden。

启动命令（先加载既有凭据与Java21环境，不打印凭据）：

```bash
E2E_FIXTURE_DIR=data/e2e-fixtures/ncs033/mystery-20260920-quality-closure python3 docs/harness/evidence/ncs033/run-intent-e2e.py --mode record --chapters 3 --port 18102
```
