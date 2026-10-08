# 交接：50万字长跑护航 + 模型评估轮（GLM→qwen 回切） — 2026-10-07

Last Updated: 2026-10-09

## Current

《山河执棋人》长跑护航至 ch40 / 151,673 字（30.3%）。1003 修复轮 NCS-173..176、
1005-1006 运行时修复 NCS-178、模型评估 NCS-181/182 全部闭环合入 main：

- NCS-173 扇出超时防护 / 174 客户端超时可配置 / 175 预览进度 / 176 候选直达+创作要求持久化
- NCS-178 check 预算 1500s（消除检查超时屏障，验证有效）
- NCS-181 全通道切 glm-5.3-flash 评估（ch36-39）→ NCS-182 按用户决定回切 qwen3.8-flash
  （GLM：文风更丰但慢 2-3 倍、成本 2 倍、空响应率高；两轮切换均实测验证调用）
- 新 backlog：NCS-177（index.html 缓存）/ NCS-179（检查输出爆炸 45k→97k，根因=
  悬置伏笔 69+ 条全量复查）/ NCS-180（真典修正不级联章节约束缓存，ch30 六次裁决实测）

## Files

- spec：docs/specs/2026-10-03-pipeline-resilience-and-review-ux.md
- main：be96efe(173)/74f4cb5(174)/6d0435f(175)/17316a4+5ac7fc2(176)/0d57090(178 登记)/
  99fe6e0+d910cca(181)/80914c4(182 revert)
- 证据：docs/harness/evidence/2026-10-0{3,7}-ncs-*.json

## Verification

- 全量 mvn test 1484/0F/1S（qwen 回切后，2026-10-07）
- 18095 = PID 88352（qwen 新包）；ch40 由 qwen-flash-write 完成（225s/¥0.064）
- 屏障处置方法论沉淀在记忆 novel-50w-verification-run（API 补查/逐条裁决/补桥模式）

## 环境注意

- mvn test 必须 bash -c 'source scripts/load-env.sh && mvn test'（zsh 直接 source 报错）
- 18095 启动：env 加载 /tmp/novel18095.env + java -jar target/...jar --server.port=18095
  --persistence.sqlite.db-path=./data/ui-archtest-20260929/novel.db
  --persistence.vector-store.index-path=./data/ui-archtest-20260929/vectors.idx
- GLM 通道配置在 git 历史（99fe6e0），需要时可直接 revert NCS-182 恢复

## Blockers

- 无（既有数据问题：ncs-077-verified 提交对象已不在仓库历史，--check-git FAIL，未篡改）

## 2026-10-09 收尾与评测

- 《山河执棋人》收尾于 55 章 / 197,769 字（qwen Token Plan 月配额耗尽自然停止，重置 UTC 10-09 16:00 ≈ 北京 10-10 00:00；如需续写等配额恢复后点继续协作）
- 5 子 Agent 并行多维评测完成，报告 docs/reports/2026-10-09-novel-eval-shanhezhiqiren/（综合 6.2：内容6/题材8/文笔6.5/一致性6.5/读者4.2）
- 评测映射的系统改进方向已列入综合报告第三节（钩子多样性/词频反馈/回报率检测/长期设定回查）

## Next Steps

1. （可选）配额恢复后继续《山河执棋人》50 万字目标
2. backlog 三项：NCS-179（检查输出治理，最影响长跑效率）/ NCS-180（约束缓存级联）/ NCS-177（缓存头）
3. 章节风格观察（未登记）：qwen 对白乒乓 vs GLM 场景丰盈——后续可在 prompt 层融合
