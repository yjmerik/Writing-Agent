# NCS 方案落地进行中 — 2026-09-19

Last Updated: 2026-09-19

## Current

novel-complete-solution 方案（docs/specs/2026-09-19-novel-complete-solution.md）拆解为 30 个 NCS feature
（phase 44–49 对应方案 M1–M6），逐个走完整特性闭环。NCS-001「持久写入门禁与 epoch 租约」已完成合入 main
（实现提交 abc2ccb = main HEAD，ff-merge，完成记录 ncs-001-verified）。active_feature=null，
下一特性按依赖序为 NCS-002（最终请求窗口核算）。

## Files

- 本轮新增：persistence/sqlite/WriteLeaseRepository、service/{WriteLeaseService,WriteLeaseStaleException}、
  测试 WriteLeaseEpochTest/PostProcessLeaseGuardTest
- 本轮修改：SqliteSchemaInitializer（project_write_leases 表 + writing_tasks.lease_epoch 列）、
  SqliteNovelRepository（任务 lease_epoch 持久化）、WritingTaskExecutor（启动捕获/取消递增）、
  ChapterWriterAgent（write 落库前 + rewriteForCorrection 返回前门禁）、ChapterPostProcessor（run 入口门禁）、
  NovelOrchestrator（late-set writeLeaseRef）、AgentConfig/MemoryConfig/PersistenceConfig/OrchestratorConfig 接线
- 规划：docs/specs/2026-09-19-novel-complete-solution.md、feature_list.json（361 项，NCS-001 completed）
- 归档：docs/harness/archive/2026-09-19-p43-01-{progress,session-handoff}.md

## Verification

NCS-001（git worktree --detach abc2ccb 干净检出 + 分支工作区各一次，本轮实际执行）：
- mvn compile PASS；mvn test 975 项 0 失败 / 0 错误 / 1 跳过（既有）
- verify_cmd（WriteLeaseEpochTest+PostProcessLeaseGuardTest）7 项 0 失败；先骨架 RED 5 项后转绿
- 无前端/prompt 改动：e2e 与 baseline 不适用（未触碰 replay 指纹）
- 证据 docs/harness/evidence/2026-09-19-ncs-001.json；check-harness 见回执提交

## Blockers

无。环境注记：
- mvn test 需 `set -a; source data/.prod-env-keys; set +a; source scripts/load-env.sh`（DASHSCOPE_TOKEN_PLAN_API_KEY）
- 干净检出需先复制 frontend/node_modules 再 npm build 生成 static（p42-01 约束 + node_modules 补充）
- 主目录两处未跟踪材料（.zcode/plans/*、docs/reports/2026-09-05-ui-experience/）按约定保留不动

## Next Steps

- 下一特性 NCS-002「最终请求窗口核算」（deps 无，从 main 新建 codex/ncs-002-final-request-budget）
- 之后按 DAG 序：NCS-003/005/007 → 004/006 → phase45 起 M2
- prompt 类 feature（NCS-008/012/018/019/020/022）触发 fixture 重建 + 人工 bless gate
