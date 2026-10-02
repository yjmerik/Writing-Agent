# 18095 新包重载验收报告 — 2026-10-02

实例：本地 18095（原 PID 73731 → 新 PID 24305），数据目录 ./data/ui-archtest-20260929/
新包：main c404838（含 NCS-152/153/154/155/156/157/158/159/160/161/162/163/164/165/166/167/168）

## 1. 重载

- 原样保留启动参数（--server.port=18095 + sqlite/vector 两路径，真实 embedding 通道）
- 按「禁止运行中重建 jar」规则：停（kill 73731）→ mvn package → 重启（nohup，日志
  data/logs/18095-restart.log）；启动 1.3 秒，长任务清扫 7 条过期项
- 重启前后数据完整性一致：10 章 / 15 人物 / ch10 v2 NEEDS_MANUAL_REVIEW 4093 字 /
  AFTER_CHAPTER 检查点 PENDING

## 2. 新特性端点验收（真实实例）

| 特性 | 验证 | 结果 |
|---|---|---|
| NCS-154/155 预览端点 | GET character-revisions | {cards:[]}（无待确认，正确） |
| NCS-157 retry 端点 | POST long-tasks/fake/retry | 404 + LONG_TASK_NOT_FOUND 结构化 |
| NCS-162 保存门禁 | PUT library 坏端点候选 | 400 + MODEL_PROBE_FAILED + probe{ok:false,latency 15ms}，未入库 |

## 3. 第 10 章处置（作者复核链路）

- review-context：identity v2 + contentHash + reviewSetHash + 1 人审屏障 + 0 未解决 HIGH
- 正文抽查「骨更唤名」4093 字：氛围/声口/场景锚点/结尾意象达标 → 版本化批准
  （review-decisions，HTTP 200，approvalId 7959dd36）；未查清 1 项自动 AUTHOR_WAIVED
- 批准后 NCS-146 自动恢复生效：AUTO_RUN 任务续跑 → 第 11 章「问剑逼城」4588 字
  写出 → INTERACTIVE 暂停在新检查点（作者下轮审阅）

## 4. 观察（不阻塞验收）

1. **embedding key 401**：本实例 embedding 通道 API key 失效（dashscope 拒绝），向量检索
   优雅降级词法通道（设计行为），写作/后处理未断；但后处理因重试变慢（NCS-140 上限兜底）。
   **需用户提供有效 embedding key 并更新该实例配置**。
2. **场景锚点部分生效**：ch11 开头锚点良好，但中段仍见 18 连对白（行占比 0.66，粗测）。
   铁律 12 在单章真实长篇上的遵从度有限，建议下轮旅程用 quality 评估的 dialogueDensity
   做正式对照，必要时加强（如锚点行数下限）。
3. 日志观察：批准后任务日志未即时出现在 app.log/stdout（数据链路本身正确），下次会话可查。

## 5. 结论

重载成功、数据无损、新特性端点全过、作者复核链路（含自动豁免/自动恢复）真实走通。
18095 现运行 main c404838 全部特性；待作者审阅第 11 章检查点与更新 embedding key。
