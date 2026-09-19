# NCS-029 任务与版本中心验收 — 2026-09-19

## 范围与环境

仅实现 feature NCS-029：持久任务历史及控制入口、章稿历史与整书记录聚合。
Java 21；隔离 SQLite /tmp/writingagent-ncs-browser/novel.db；localhost:18097；ego-browser space 23 / p1。
未修改写作编排、prompt、模型计费或历史恢复语义。费用没有任务级持久记录，显示“未记录”。

## 验证

- WritingTaskListTest：新增接口之前 2 failures / 0 errors（缺端点返回500）；实现后 2/0F/0E。覆盖持久任务各状态、目标、进度、原因、跨作品隔离、空列表。
- ./init.sh：1084 tests / 0 failures / 0 errors / 1 skipped；编译与前端构建通过。日志 /tmp/ncs029-full.log。
- 浏览器：从 UI 创建隔离作品 114381af-52ce-43a1-8a0e-6b96248619f0，在任务中心点击“从当前进度继续”→“停止当前任务”，显示暂停和作者停止原因；刷新页面记录保留。再次继续并停止，保留两条任务记录。
- 章稿：作品 f48efcbf-ea03-4eb5-9efa-b1faa9c7bc5b 通过正文编辑保存 v3，版本中心列出 v2；“只读查看”进入 version=2 历史比较。当前稿仍 v3，旧稿没有编辑框。整书记录 731cb264 在同页显示已定稿及导出入口。
- 等待人工复核时，继续按钮禁用并显示原因。

## 证据

- versions.png：章稿历史列表。
- tasks-stopped.png：停止并刷新后的持久任务。
- tasks-resumed.png：继续后的历史保留。

## 限制 / 范围外发现

- 继续复用既有协作 API，从当前作品进度新建任务，并不复用旧 taskId；页面明示此语义。
- 当前无任务级费用保存机制，不扩展计费后端。
- 既有 Scenario 检查协议、DONE 稿审批入口问题沿用 NCS-028 报告，只记录；NCS-030 联合场景另行验收，不能由本专项推定通过。
