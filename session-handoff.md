# 交接：验证轮修复 NCS-173..176 闭环 — 2026-10-03

Last Updated: 2026-10-03

## Current

1003 创作者验证轮（《山河执棋人》项目 37353224，历史权谋·200章）发现的
1 个 P1 + 3 个 UX 问题全部修复并合入 main（active_feature=None）：

- NCS-173（P1）：上游 LLM 停滞时后处理扇出无超时 f.get() 冻结写作循环
  （实测两次、最长15分钟，期间批准/重写全 409）。修复=限时等待+超时事件化
  （POST_PROCESS_STAGE_TIMEOUT）+账本保持未完成+扇出池改缓存
- NCS-174：LLM 客户端超时属性逃逸口（novel.llm.{connect,read,call}-timeout-seconds，
  默认不变）+ qwen-flash-extract 预算 600→300s
- NCS-175：Agent 修改预览轮询进度可视化（异步预览 4-6 分钟静默期不再像卡死）
- NCS-176：候选计数一键直达（workspace-view 新增 candidateChapters + chip 深链，
  实测追加修复 setSearchParams 覆写深链 bug）+ 创作要求 localStorage 持久化
- 新 backlog NCS-177：index.html 缓存控制（升级后浏览器拿旧 bundle，实测）

## Files

- spec：docs/specs/2026-10-03-pipeline-resilience-and-review-ux.md
- main：be96efe(173) → 74f4cb5(174) → 6d0435f(175) → 17316a4+5ac7fc2(176) → 2293ce5
- 归档：docs/harness/archive/progress-2026-10-03-pre-ncs173.md / handoff-2026-10-02-backlog-clear.md

## Verification

- 全量 mvn test：1481 run / 0 fail / 0 error / 1 skip（main，2026-10-03）
- npm run build 通过；浏览器端到端（18095 新包 PID 88899）：
  候选 chip→/chapter/5 审查面板定位第5章候选线索区；创作要求刷新恢复；
  复现测试 ChapterPostProcessorFanoutTimeoutTest（修复前无限挂死→修复后限时放弃）

## 环境注意

- mvn test 必须 bash -c 'source scripts/load-env.sh && mvn test'（zsh 直接 source 会 bad substitution）
- 18095 启动命令：env 加载 /tmp/novel18095.env（47 个变量，含三个 DASHSCOPE key）
  java -jar target/writing-agent-0.1.0-SNAPSHOT.jar --server.port=18095
  --persistence.sqlite.db-path=./data/ui-archtest-20260929/novel.db
  --persistence.vector-store.index-path=./data/ui-archtest-20260929/vectors.idx
- 已清理三个遗留实例：18095 旧包(51851)、18099 九天孤儿(13108)、8080 孤儿(37302)
- 浏览器升级服务后需强刷（Ctrl+Shift+R）：index.html 无 no-cache 头（NCS-177）

## Blockers

- 无（既有数据问题备忘：`--check-git` 下 ncs-077-verified 的提交对象已不在仓库
  历史（27d1511 无法解析，疑早期 reset/rebase 丢失），本轮未篡改历史证据；
  后续如需修复应单独 feature 处理）

## Next Steps

1. 《山河执棋人》继续协作推进 50 万字：当前 12 章 / 55,273 字，ch12 待人工复核
   （打开审阅→裁决候选线索→批准）；创作要求已预写「增加喘息章与场景多样性」
2. NCS-177 index.html 缓存控制（小改动）
3. NCS-170 场景锚点评测 / NCS-171 任务日志调查仍在 backlog
4. 系统性风格观察（未登记）：对白乒乓 vs 说明文密度两头摆，连续4章对抗博弈
   ——后续可并入 NCS-170 评测或单开 prompt 调优 feature
