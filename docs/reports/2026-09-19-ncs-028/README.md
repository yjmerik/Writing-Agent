# NCS-028 定稿校验验收（2026-09-19）

## 范围

仅实现 feature NCS-028：报告绑定整书 manifest、全章节问题核对、未知检查与批准覆盖、例外和承诺处置、报告持久化及批准门禁。增加的 SPI/SQLite/界面文件是该特性的必要接入；不修改写作循环门禁、prompt 或其他特性。

## 自动化证据

- EditionValidationTest：8 项先失败（8 failures / 0 errors），无报告批准实际返回 200；新增校验路径尚不存在。
- 实现后 8 项通过：无报告不可批准；65 章作品第 1 章 HIGH 不因窗口消失；无检查/UNKNOWN 不计为通过；报告持久化与 manifest 绑定；新稿不继承旧报告且历史记录仍可审阅；新问题使旧结论过期；版本绑定的创作例外保留；单个未决承诺即阻断。
- 原有成书 API 测试补齐报告前置条件，并保留快照不借用新稿批准的断言。
- 报告保存/读取/批准在单个 SQLite 事务快照内完成；业务服务只依赖 NovelRepository SPI。
- 检查结果绑定实际被检查的正文全文指纹，不读取模型返回时可能已改变的当前正文。旧未绑定记录明确标为未知。
- 工作稿更新使用 workingCopyChanged 单独提示；不把历史报告错误标成新稿报告。
- 全量/E2E/合并后结果以 docs/harness/evidence/2026-09-19-ncs-028.json 为准。

## 浏览器证据与边界

Ego space 23 / p1，独立服务 localhost:18097，独立数据库 /tmp/writingagent-ncs-browser/novel.db。
通过真实界面创建项目、手工章稿、整书记录，点击「校验此版本」，核对必须处理与尚未查清分列（blocked.png）。
准备好的合法连续性检查与章节批准样本用于报告通过状态和定稿按钮检查（passed.png）。
章节检查/批准前置通过真实 API 准备；本报告不将该准备过程算作全 UI Journey 已通过。
确认定稿对话框与确认动作成功，界面显示已定稿。导出端点返回 200、APPROVED 与固定 manifest；浏览器 download 事件捕获超时，未把它当作浏览器下载成功证据。
未做完整 console/network 联合验收；该验收归 NCS-030。

## 仅记录的既有问题（按用户要求不顺带修复）

1. ScenarioLlmGateway 正常连续性响应为 `[]`，但当前 CheckResultEnvelope 只把模板规定的明确无问题文本认作 VALID_EMPTY；实测为 INVALID，阻断场景模式定稿。专项诊断曾复现 VALID_EMPTY != INVALID；尝试改动已撤回，最终保留原网关和原测试。真实录制 fixture 回放未受影响。
2. 章节页仅对 NEEDS_MANUAL_REVIEW 显示「批准当前稿」，手工 DONE 章稿缺少同等批准入口；也未发现完整的手工重新检查入口。仅记录，不在本特性重构。
3. 后续 NCS-030 若上述问题阻断规定的全 UI 场景，应如实列 FAIL/BLOCKED，不能以 API 准备或测试桩成绩替代真实界面验收。
