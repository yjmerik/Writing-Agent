# ZCode → Codex 接手记录 — 2026-09-19

Last Updated: 2026-09-19

## Current

已完成工作区盘点并接收未完事项。本次交接维护分支：codex/docs-zcode-takeover-20260919。
接手时 main=9348eac，无未合并本地分支，仅一个工作区，无未提交业务代码。
NCS-001..027 已登记完成并合入 main；最新实现 4864435，状态回执 9348eac。
旧 progress/handoff 的 21/30 与 NCS-022 恢复入口已失效；真实进度为 27/30。
active_feature=null；本次未开启 NCS-028，不将计划接手记为 in-progress 或 completed。

## Files

- feature_list.json：唯一状态入口；docs/harness/completions.json：完成提交与证据索引。
- docs/specs/2026-09-19-novel-complete-solution.md：当前方案、NCS-028 验收及成书路径范围约束。
- src/main/java/com/writing/novel/web/BookEditionController.java：下一开发入口。
- 既有未跟踪 .zcode Phase 32 计划和 2026-09-05-ui-experience 报告目录原样保留，不应整体暂存或当作当前特性。
- 旧 progress/handoff 归档：docs/harness/archive/2026-09-19-pre-codex-takeover-*.md。

## Verification

- harness --check-git PASS；完成证据的提交归属已核验。
- 本次 Java 21 运行 ./init.sh PASS：编译通过；1074 tests / 0 failures / 0 errors / 1 skipped；前端构建通过（既有大包提示）。本地日志 /tmp/writingagent-takeover-init-jdk21-20260919.log。
- 历史 NCS-027 证据记录 1074 tests / 0 failures / 0 errors / 1 skipped、前端构建和浏览器流程通过；详见 docs/harness/evidence/2026-09-19-ncs-027.json。

## Blockers

- p40-05/06：缺人工 severeContinuityErrors，继续保持 deferred。
- 验证环境：默认 Maven 使用 Java 26；受限执行 Mockito 自附加失败，不能据此认定业务回归。
- 使用本机 Java 21：env JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ./init.sh；测试需要正常 JVM 附加与本地端口权限。

## Next Steps

1. 唯一下一特性 NCS-028（not-started），依赖 NCS-027 已满足；新建 codex/ncs-028-edition-validation。
2. 先写 failing test，再实现 EditionValidationService / BookEditionController 等必要改动；只改变成书路径，保持写作循环门禁行为。
3. verify_cmd：mvn test -Dtest=EditionValidationTest；完整 mvn compile && mvn test；遵守逐项合并、合并后验证及完成记录协议。
4. NCS-028 完成后再做 NCS-029，然后 NCS-030（真实浏览器 T01–T11 + console/network + 报告证据）。
