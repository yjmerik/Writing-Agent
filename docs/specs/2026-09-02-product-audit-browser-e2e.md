# Spec: 产品一致性审计浏览器 E2E 红灯套件

> 来源：`docs/audits/2026-09-02-product-design-consistency-audit.md`。

## 背景与动机

2026-09-02 页面产品一致性审查确认了 16 项问题。现有 `scripts/browser-e2e.sh` 只覆盖页面可打开、基础响应式、终态门禁、懒加载、SSE 和人工复核恢复，未覆盖操作是否真正生效、模式切换后的状态续跑、审核后的正文稳定性，以及人工内容管理能力。

修复产品实现前，先建立独立的浏览器 E2E 红灯套件，让每项修复都有可重复的失败证据。该套件不并入现有 8/8 发布门禁，避免把已知产品缺陷伪装成基础设施回归。

## 目标

1. 将审计第 6 节的 9 个建议场景转成可筛选、可机器汇总的 ego-browser E2E 用例。
2. 默认模式下任何产品断言失败均返回非零；`--expect-known-failures` 模式仅在失败集合与显式基线完全一致且无基础设施错误时返回零。
3. 每个场景输出 PASS / XFAIL / ERROR、断言细节与 JSON 汇总，为后续逐项修复提供红灯入口。

## 非目标

- 本 feature 不修复审计中的任何产品问题。
- 不修改 Prompt、业务状态机、数据库 schema 或正式 LLM 配置。
- 不把已知失败直接加入 `scripts/release-gate.sh`。
- 不以 DOM 单元测试代替真实 Spring 应用、SQLite、Scenario LLM 和浏览器联动。

## 验收标准

- [ ] `./scripts/product-audit-e2e.sh --list` 稳定列出 9 个场景 ID、优先级和关联问题。
- [ ] `./scripts/product-audit-e2e.sh --expect-known-failures` 启动隔离应用并完成 9 个场景；只有失败集合与已知缺陷基线一致、且无 ERROR 时退出 0。
- [ ] `./scripts/product-audit-e2e.sh` 在未修复问题仍存在时退出非零，防止默认运行把 XFAIL 当作通过。
- [ ] `jq empty feature_list.json && mvn compile -q && mvn test` 通过；前端构建通过。

## 场景清单

| id | 类型 | 用户目标 | 核心成功标准 | 审计关联 |
|---|---|---|---|---|
| `auto-zero-intervention` | Specific | 自动项目只启动一次便完成 3 章 | 到达 FINAL，章节数为 3 | P1-01 |
| `initial-setup-modify` | Specific | 在初始设定审核中要求重做 | 内容指纹变化，弹窗关闭，检查点被处理 | P0-01 |
| `volume-outline-modify` | Specific | 在卷大纲审核中要求修改事件 | 大纲指纹变化，已写章节不被覆盖 | P0-01 |
| `standalone-chapter-rewrite` | Specific | 无待审核项时重写已完成章节 | 版本递增、正文变化、弹窗关闭 | P0-02 |
| `interactive-to-auto` | Specific | 在审核点切换自动模式 | 当前等待解除并继续推进 | P0-03 |
| `approve-keeps-chapter-visible` | Specific | 批准章节后继续阅读 | 正文持续可见，无永久加载 | P1-03 |
| `checkpoint-card-targeting` | Comparative | 分别操作两张审核卡 | 请求与状态命中所点卡片 ID | P1-07 |
| `manual-content-management` | Open-ended | 人工管理设定、人物、大纲和章节 | 四类内容均有完整新增/编辑入口 | P1-04/P1-05/P1-06 |
| `mobile-critical-path` | Specific | 375px 下完成关键操作 | 无遮挡，关键控件可聚焦且有可访问名 | P2-02 |

## 影响面

- **涉及模块**: `scripts/` 浏览器 E2E harness、Scenario LLM 隔离运行配置
- **涉及 prompt 模板**: 无
- **数据库 schema 变更**: 无；测试只在 `data/e2e/product-audit-browser/` 使用隔离 SQLite
- **质量基线影响**: 无；不评价文学质量，不需要 bless

## Feature 拆分

| id | name | 依赖 | acceptance | verify_cmd |
|----|------|------|------------|------------|
| p25-01 | 产品审计浏览器 E2E 红灯套件 | [] | 9 个场景可列举、可筛选、可汇总；已知失败模式严格校验；Maven 与前端基线不回归 | `./scripts/product-audit-e2e.sh --expect-known-failures` |

## 风险与备选方案

- 浏览器或应用启动失败必须归类为 ERROR，不能被 `--expect-known-failures` 吞掉。
- 多检查点场景需要构造系统正常流程难以同时产生的隔离状态；仅在测试 SQLite 中插入检查点，不接触业务数据。
- 当某个缺陷修复后，场景会出现 XPASS 并使“已知失败”模式失败，提醒维护者从已知失败集合移除该 ID，再将其接入常规门禁。
