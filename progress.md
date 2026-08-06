# Session Progress Log

## Current State

**Last Updated:** 2026-08-05
**Phase 9:** harness 自动化闭环设施完成 ✅
**Features:** 50 total, 50 completed
**Tests:** 96 pass, 0 failures, 1 skipped (`RealLlmSmokeTest`)
**Harness validation:** 100/100

## Phase 9 完成情况

### 可重放 E2E

- `RecordReplayLlmGateway`：`record` 录制真实 LLM 响应；`replay` 按请求指纹回放；fixture 缺失 fail-fast
- 指纹归一化配置 JSON 的 `updatedAt` 字段，避免新建项目的时间戳导致 replay miss
- `ObservedLlmGateway`：记录每次 LLM 调用的任务类型、耗时、token 用量和错误分类
- `scripts/e2e.sh`：独立 data/e2e 工作区启动服务、创建项目、构思、逐章写作、输出 `e2e-report.json`

### 可诊断运行

- `telemetry/EventLog` 写 `data/logs/events.jsonl`，事件：`LLM_CALL` / `LLM_ERROR` / `CHAPTER_WRITTEN` / `CHAPTER_FAILED`
- `EventContext` 关联项目 ID 与章节号
- `logback-spring.xml` 写 `data/logs/app.log`（14 天滚动）
- `scripts/diagnose.sh` 聚合失败分类、LLM 统计和最近写作章节

### 可度量优化

- `scripts/compare-baseline.py` 对比 `e2e-report.json` 和 golden 基线，指标退化时失败
- `baselines/README.md` 规定首次 record 与人工 `--bless` 建立基线的流程

### 可持续开发

- `docs/specs/TEMPLATE.md` 规定新需求 spec 格式
- `AGENTS.md` 增加需求拆分、失败回流、bugfix gate、会话结束和 backlog 耗尽协议
- `feature_list.json` 50 条 feature 均含 `acceptance` 与 `verify_cmd`
- `.github/workflows/ci.yml` 添加 Maven CI 门禁（仓库配置 remote 后生效）
- `init.sh` 改为 fail-fast，验证编译、95 测试、20 prompt 模板及前端构建

## Verification Evidence

| Check | Command | Result |
|---|---|---|
| 新增单测 | `mvn test -Dtest=EventLogTest,RecordReplayLlmGatewayTest,LlmGatewayWiringTest` | 10 pass |
| 全量验证 | `./init.sh` | 95 pass, 0 failures; frontend build pass |
| Harness 审计 | `validate-harness.mjs --target .` | 100/100 |
| E2E bootstrap | `./scripts/e2e.sh --mode replay --chapters 1` | 服务就绪、项目创建；fixture 缺失时 fail-fast（预期） |
| 诊断脚本 | `./scripts/diagnose.sh /tmp/.../events.jsonl` | 合成事件的分类/统计/建议正确 |
| 基线脚本 | `compare-baseline.py --bless` → compare | 合成报告往返 PASS |
| 短真实调用 | `POST .../preferences/chat`（record） | 5.37s；587 prompt + 430 completion tokens；偏好正确更新为 2500 字 |
| 短回放调用 | 同请求（replay） | 3ms；结果一致，未触达外部 LLM |

## Next Steps

1. 在可接受更长运行时间时执行 `./scripts/e2e.sh --mode record --chapters 3`，完整录制并审阅多章产出。
2. 确认 `e2e-report.json` 的质量后运行 `python3 scripts/compare-baseline.py --bless`。
3. 运行 `./scripts/e2e.sh` 验证零成本多章 replay，再运行 `python3 scripts/compare-baseline.py`。
4. 后续新需求必须从 `docs/specs/TEMPLATE.md` 起步；E2E 的失败分类按 AGENTS.md 回流为 bug feature。
