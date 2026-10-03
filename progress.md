# 50万字验证轮问题修复（NCS-173..176）— 2026-10-03

Last Updated: 2026-10-03

## Current

- 1003 创作者验证轮（《山河执棋人》12章/55,273字）发现的问题全部闭环：
  NCS-173 后处理扇出超时防护（P1：上游停滞冻结写作循环最长15分钟）——
  awaitFanout 限时等待（novel.postprocess.fanout-timeout-seconds 默认600s），
  超时事件化+账本保持未完成，扇出池改缓存防停滞线程占坑
- NCS-174 LLM 客户端超时可配置：系统属性逃逸口（默认不变）+ extract 预算 600→300s
- NCS-175 预览轮询进度可视化（已等待时长常驻+候选持久化说明）
- NCS-176 候选计数一键直达（candidateChapters 聚合 + chip 深链）+ 创作要求
  localStorage 持久化；验证中追加修复 setSearchParams 覆写深链 bug（5ac7fc2）
- 18095 已以新包重启（PID 88899），《山河执棋人》数据完好，浏览器端到端验证通过
- 新 backlog：NCS-177（index.html 缓存控制——升级后浏览器拿旧 bundle，实测）

## Files

- spec：docs/specs/2026-10-03-pipeline-resilience-and-review-ux.md
- main：be96efe(173)/74f4cb5(174)/6d0435f(175)/17316a4+5ac7fc2(176)/2293ce5(合并)
- 测试：ChapterPostProcessorFanoutTimeoutTest / OpenAiCompatibleClientTimeoutTest / WorkspaceViewTest

## Verification

- 全量 mvn test：1481 run / 0 fail / 0 error / 1 skip（main 合并后绿）
- npm run build 通过；浏览器端到端：候选 chip→/chapter/5 定位第5章候选线索区、
  创作要求输入→刷新→恢复、预览进度条常驻显示

## Blockers

- 无

## Next Steps

- NCS-177（index.html 缓存控制）登记待做
- 《山河执棋人》继续协作推进 50 万字（当前 12 章 55,273 字；ch12 待人工复核中）
- NCS-170（场景锚点遵从度评测）/ NCS-171（任务生命周期日志）仍在 backlog
