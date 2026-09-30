# Spec: Phase-53 批次 Review 整改

> 文件名约定：`docs/specs/YYYY-MM-DD-<slug>.md`。新需求必须先落 spec，再拆 feature。

## 背景与动机

2026-09-30 对 phase-53 批次（`face356...HEAD`，NCS-139..151 共 11 个 feature，+2160/-203）
做了 Standards / Spec 双轴代码与架构 review，发现以下问题（详见当日会话报告）：

**行为性缺口（Spec 轴）**
- NCS-144 语义相反：登记承诺「预览-确认修订链、保持作者批准制」，实现是 design 后直接
  `saveCharacter` 原地 upsert（`NovelOrchestrator.reviseNamedExistingCharacters`），作者无确认环节。
  需求文档引用的 `previewCharacterUpdate` 链在代码中并不存在——需要新建该链路。
- NCS-145 承诺的 `FAILED_RETRYABLE` 状态未实现：冷却重投超限后终态仍写死 `"FAILED"`，
  且无任何重试入口。
- NCS-142「失败即时不入库」未兑现：模型库保存路径不探测、不阻断，坏配置仍可入库。
- NCS-143「被拒提示内联逐条裁决入口」的前端半边未做。
- 登记可信度失效：6 个 feature 的 `verify_cmd`/`acceptance` 引用不存在的测试类名；
  NCS-149 description 含未实现的降级条款；NCS-143 acceptance 的审计标识名与代码不符。

**代码卫生（Standards 轴，均为判断性意见）**
- `SqliteNovelRepository` NCS-143 查询族三个方法重复同一段大 WHERE，`maxDeferredUntil`
  手工按序绑 10 个位置参数（顺序与 `bindDeferredHigh` 不同），极脆弱。
- `LongTaskService.isProviderCooldown` 用异常**消息文本** `contains(...)` 分类冷却，
  上游措辞一变重投即静默失效（Primitive Obsession）。
- 静默吞异常：`NovelOrchestrator` 卷小结 `catch (Exception ignored) {}`、
  `ReviewClassificationService` 裁决查询失败降级为空集且无日志。
- `LlmProbe.run` 原地突变入参 `candidate`，契约隐式；「候选合并」逻辑在
  `ModelConfigController` 与 `ModelLibraryController` 各写一遍；`ModelLibraryController:158`
  语句挤在方法签名行（c1a1426 格式破坏）。
- `PlotMotifTracker` 以 summary 前缀 `"第N章桥段"` 充当记录身份键，写读格式隐性耦合。

## 目标

1. 补齐三个已登记但未兑现的行为承诺：人物卡修订批准制（NCS-144 缺口）、
   长任务 FAILED_RETRYABLE 可见可重试（NCS-145 缺口）、模型库保存连通性门禁（NCS-142 缺口），
   外加被拒提示内联裁决入口（NCS-143 前端半边）。
2. 恢复登记可信度：修正全部失效的 verify_cmd/acceptance/description，并加校验脚本防再发。
3. 清除本批引入的代码卫生债：重复 SQL、消息文本分类异常、静默吞异常、隐式契约与格式破坏。

## 非目标

- 不做 prompt 大改（O-2/O-3 已登记为 NCS-152/153，另行推进）。
- 不整体重构 `NovelOrchestrator`（仅把人物卡领域逻辑随 NCS-154 下沉）。
- 不追溯补跑 NCS-144 合入时的 e2e 门禁（golden 已随 NCS-150 语料重建包含现行为；
  批准制修复 NCS-154 自身按 DoD 重跑 e2e，等效补上门禁）。
- FAILED_RETRYABLE 不做跨重启重试（任务 Supplier 不持久化；重启后保留终态展示，
  重新发起走既有 UI 入口）。

## 验收标准

- [ ] 点名卡修订不再自动落库：产出待确认预览，确认后生效、放弃即清空（`mvn test -Dtest=CharacterRevisionPreviewTest`）
- [ ] 冷却重投超限进入 `FAILED_RETRYABLE` 且可经端点手动重投（`mvn test -Dtest=LongTaskRetryTest`）
- [ ] 模型库保存前探测，失败拒存且不入库（`mvn test -Dtest=ModelLibrarySaveGateTest`）
- [ ] 所有 completed feature 的 verify_cmd 可执行（`python3 scripts/check-verify-cmds.py` 退出码 0）
- [ ] `mvn compile && mvn test` 全绿；涉及注入上下文的 feature 按各自 DoD 跑 e2e 对比

## 影响面

- **涉及模块**: orchestrator / service（LongTask、ReviewClassification）/ llm（gateway、LlmProbe）/
  persistence（sqlite）/ web（Checkpoint、LongTask、ModelLibrary）/ frontend（InteractiveReview、types、client）
- **涉及 prompt 模板**: 无（不动 `.st`；LlmProbe 探针语维持硬编码并加豁免注释）
- **数据库 schema 变更**: 无（人物卡待确认预览用内存态存储，不落新表）
- **质量基线影响**: NCS-154 改变人物卡落库时序（确认后才注入后续章写上下文）、NCS-161 改
  桥段历史排除逻辑——两者均需 `./scripts/e2e.sh` 对比，若分叉按协议 record 重建语料 +
  连续两次 replay 一致 + `--bless`（理由记入证据）。其余 feature 无基线影响。

## Feature 拆分

> 拆分规则见 AGENTS.md「需求拆分协议」。多数 feature 控制在 1-3 个文件；
> NCS-154/157 为垂直切片略超，理由：中间态不可独立交付。

| id | name | type | 依赖 | acceptance | verify_cmd |
|----|------|------|------|------------|------------|
| NCS-164 | 登记修正与 verify_cmd 校验 | harness | [] | 6 个失效 verify_cmd/acceptance 更正为实际测试类名；NCS-149 description 移除未实现降级条款；NCS-143 acceptance 审计标识对齐 APPROVE_WITH_DEFER；修复 check-harness 既有 FAIL（NCS-144..151 补 completion_record、ncs-070-e2e-verified 记录补全字段）；新增校验脚本对所有 **completed** feature 核对 verify_cmd 引用的测试类存在 | `python3 scripts/check-harness.py && python3 scripts/check-verify-cmds.py` |
| NCS-154 | 人物卡修订预览-确认链（后端） | bug | [] | `reviseNamedExistingCharacters` 不再直接 saveCharacter：design 候选存为待确认预览（含逐字段原/候选 diff），新增 confirm（应用 upsert）/discard（清空）端点；未点名卡零变动；copyIfPresent/describeCard 下沉出 orchestrator；合入前 e2e 对比，分叉则按协议重建语料 | `mvn test -Dtest=CharacterRevisionPreviewTest && mvn test -Dtest=ReviseNamedCharacterTest` |
| NCS-155 | 人物卡修订预览 UI | feature | [NCS-154] | 检查点修订结果区渲染待确认人物卡 diff 卡片，确认/放弃调用端点后状态刷新；`cd frontend && npm run build` 通过 | `cd frontend && npm run build` |
| NCS-156 | 类型化 provider 异常 | bug | [] | 网关无可用 provider 时抛 `LlmProviderUnavailableException`（携带 cooldown 瞬态标志）；`LongTaskService` 改 instanceof 判定重投并删除消息文本匹配；异常消息措辞变化不影响重投分类（测试用改写消息的异常验证） | `mvn test -Dtest=LlmProviderFallbackTest && mvn test -Dtest=LongTaskRetryTest` |
| NCS-157 | FAILED_RETRYABLE 终态与手动重投 | feature | [NCS-156] | 冷却重投超限终态为 `FAILED_RETRYABLE`（非 FAILED）；统一轮询端点原样返回；新增 `POST /api/projects/{id}/long-tasks/{jobId}/retry` 对该状态任务重新入队（重新占用工作权）；前端展示状态并提供重试按钮 | `mvn test -Dtest=LongTaskRetryTest` |
| NCS-158 | NCS-143 查询族收敛 | maintenance | [] | count/maxDeferredUntil/描述三类查询共用一条行集 SQL，Java 端聚合；删除手工位置绑定，参数绑定统一走 `bindDeferredHigh`；ApproveWithDefer/ApprovalEvidence 行为不变 | `mvn test -Dtest=ApproveWithDeferTest && mvn test -Dtest=ApprovalEvidenceIdentityTest` |
| NCS-159 | 静默吞异常治理 | bug | [] | 卷小结失败记 debug、裁决查询失败记 warn（降级行为保留）；ListAppender 断言两处日志出现 | `mvn test -Dtest=SilentCatchLoggingTest` |
| NCS-160 | LlmProbe 契约收紧与合并去重 | maintenance | [] | `LlmProbe.run` 内部防御性拷贝、不再突变入参（调用方拷贝可删）；两控制器候选合并逻辑统一为共享 helper；`ModelLibraryController:158` 格式破坏修复；探针 prompt 加豁免注释 | `mvn test -Dtest=ModelConnectivityCheckTest && mvn test -Dtest=ModelConfigControllerValidationTest` |
| NCS-161 | 桥段记录章号显式解析 | maintenance | [] | 同章排除改为严格正则 `^第(\d+)章桥段` 提取整数比较；summary 前缀措辞微调不破坏排除；e2e 对比无退化 | `mvn test -Dtest=PlotMotifScopeTest` |
| NCS-162 | 模型库保存连通性门禁 | bug | [] | `PUT /api/models/{name}` 保存前探测候选，失败返回 400 结构化错误且配置不入库；`?force=true` 逃生门（离线场景）；成功路径与既有校验不变 | `mvn test -Dtest=ModelLibrarySaveGateTest` |
| NCS-163 | 被拒提示内联裁决入口 | feature | [] | 批准被拒提示支持逐条 FALSE_POSITIVE/EXCEPTION/DEFER 快捷裁决提交（走既有裁决端点，无后端新端点）；`cd frontend && npm run build` 通过 | `cd frontend && npm run build` |

**建议顺序**: NCS-164（最便宜，先恢复登记可信度）→ 154→155 → 156→157 → 162 → 163 → 158/159/160/161。
NCS-152/153（O-2/O-3 prompt 项）与本次整改独立，可穿插。

## 风险与备选方案

- **NCS-154 是行为变更**（自动落库 → 等待确认）：旅程 F-2 的原始痛点是「意见被静默忽略」，
  改后必须保证预览在修订结果中显式可见，不得再次静默。回滚 = revert 该 feature 单分支。
- **保存门禁（162）挡离线配置**：`?force=true` 逃生门兜底；否决了「仅前端阻断」方案
  （API 直调可绕过且不可机器验证）。
- **FAILED_RETRYABLE 跨重启不可重试**（Supplier 不持久化）：备选方案「持久化任务参数重建」
  被否决——所有长任务提交方都有 UI 入口可重新发起，重投入口仅服务进程内存态。
- **e2e 语料分叉风险（154/161）**：按 DoD 走 record 重建 + 双 replay 一致 + 人工确认后 bless；
  若人工不可及，feature 保持 in-progress 不合入。
