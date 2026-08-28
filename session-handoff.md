# Session Handoff

## Current Objective

- **Goal**: Phase 16-19 百万字稳定性 + 风格确定性 + 人机交互（18 features 全部完成）+ LLM 路由切换与 E2E 全量验证。
- **Current status**: **全部完成**。396 测试全绿；DeepSeek v4-flash E2E record/replay/compare-baseline 三关全过。
- **Spec**: `docs/specs/2026-08-28-million-word-roadmap.md`
- **Branch**: `phase-11-quality-gate-architecture`（未 push，无 upstream）

## Completed（2026-08-28 ~ 08-29）

| Phase | 内容 | 提交 |
|---|---|---|
| 模型切换 | glm-5.3-flash（9 块）→ 调试 8 轮修复 8 个基础设施 bug → **终态 DeepSeek v4-flash**（9a9e6fd→ae694f7 系列） | `9a9e6fd`…`ae694f7` 后续 |
| P16 百万字止血 | HIGH 老化+FINAL 近60章口径 / 伏笔回收卷（死路修复）/ 4 处全量注入收窄+fail-loud / 重写路径向量重建 / 摘要分层裁剪+premise 预算 | `9a9e6fd` `9727f08` `4b4df2a` `d0f93af` `8716a2e` |
| P17 风格确定性 | WUXIA 全链路 / 爽点四段式钩子题材条件化 / volume-outline genreRules / ConceiveGate 豁免+styleVoice+preferredQuoteSources / 自定义预设持久化 | `6cd62a5` `8716a2e` `4983ead` `d0f93af` |
| P18 人机交互 | PAUSED 终态 / checkpoint 质量 payload（含前端）/ 写前指令通道（write-chapter/write-all instruction）/ SSE 实时进度（EventLog 总线+SseEventBus+前端订阅） | `4983ead` `d0f93af` `99ce1ee` |
| P19 长篇质量 | 角色生死链（Character.Status+死亡检测+注入标注+调度豁免）/ story_facts 索引+subject 规范化 / 全书结构化快照（book_snapshot） | `99ce1ee` + p19-02 |

LLM 基础设施加固（保留生效，DeepSeek 同受益）：callTimeout 30min、429 退避 30s、内容审查 1301 温和化重试、**complete() 流式聚合路径**（streaming=true 时 SSE 聚合+include_usage，fixture 录制透明）、构思三模板 2500 字输出纪律。

## Verification（2026-08-29 实跑）

- 全量 `mvn test`：**396 pass / 0 fail / 1 skip**（bash -c 'source scripts/load-env.sh && mvn test'——Spring 上下文测试需 DEEPSEEK_API_KEY）
- `cd frontend && npm run build` 通过
- **E2E record**（`E2E_FIXTURE_DIR=data/e2e-fixtures/dsv4-20260829 ./scripts/e2e.sh --mode record --chapters 3`）：3 章 / 10,951 字 / 57 LLM 调用 / **0 错误 / 0 章节失败**
- **E2E replay**：与 record 完全一致（同字数/同调用/0 错误）——确定性验证 PASS
- `compare-baseline.py` PASS（已 `--bless`，golden 对齐 dsv4-20260829）
- 读者评分 8/8/9；武侠题材 REST 验证（preset=wuxia→genre=WUXIA）+ SSE 端点验证通过

## GLM 教训（如再切回必读）

1. 必须用 coding 端点 `https://open.bigmodel.cn/api/coding/paas/v4`（标准 paas 端点 1113 余额不足）
2. `thinking-enabled: false`（yml 已删该行=默认不注入；GLM 需手动加回）否则思考模式单次 3-10 分钟
3. **夜间（约 23 点后）网关对 >5min 非流式长响应 StreamReset CANCEL，新连接也失效**——必须走流式（completeViaStream 已就绪，把任务块 streaming 置 true 即可）
4. 极健谈：撞 max_tokens 上限浪费 3-4 分钟且截断 JSON——2500 字输出纪律模板行已在三个构思模板中

## Known Issues / Next Steps

1. **标题解析健壮性（回流候选）**：第 3/5 章标题混入大纲伏笔标注（"回收)；埋设-…"）——卷大纲 LLM 行格式漂移 + parseAndAppendVolume 标题截取需按「。/）」截断。不影响正文与质检。
2. P16-P19 的前端深度交互（checkpoint payload 面板美化、SSE 断线重连 UI）可继续打磨。
3. 分支未 push；如需：`git push -u origin phase-11-quality-gate-architecture`。
4. 百万字长跑验证：P0 缺陷已全部修复，建议后续安排 50-100 章 record 实跑验证 HIGH 老化/回收卷/摘要分层在真实长跑中的表现。
