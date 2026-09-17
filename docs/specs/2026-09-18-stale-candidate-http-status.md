# Spec: 过期候选的 HTTP 冲突状态

状态：第三轮草稿回归中发现的独立问题，仅登记，未在 p41-09 混入实现。

## 背景与动机

隔离服务上正式生成章节候选，随后用 PUT 编辑该章节，再 POST `/revisions/{id}/apply`。正文保护生效，但返回 HTTP 500，响应 `{"error":"候选来源已变化，请刷新后重新生成"}`。浏览器因此显示通用应用失败，未进入 409 冲突提示。原始证据：`/tmp/p41-09-qa-red/test-results/revision-instruction-draft-e6e19-tion-preserves-instructions/trace.zip`；来源异常为 `NovelRepository.ProposalSourceConflictException`。

## 目标

将过期候选的明确业务冲突映射为 409，保持拒绝写入和正文不变；真实基础设施故障仍为 500。

## 非目标

不改变候选提交事务、重放规则、版本守卫或当前输入草稿修复；不放宽过期候选的应用权限。

## 验收标准

- [ ] 先以真实 API 生成候选、正式编辑来源，再真实应用旧候选，测试修复前 500、修复后 409 且正文/版本不变。
- [ ] 有效候选正常应用；未分类运行异常仍返回 500。
- [ ] `mvn test -Dtest=StaleRevisionCandidateApiTest` 和全量编译测试通过，独立分支合并后复验。

## 影响面

- **涉及模块**: web 异常映射及 API 测试。
- **涉及 prompt 模板**: 无。
- **数据库 schema 变更**: 无。
- **质量基线影响**: 无。

## Feature 拆分

| id | name | 依赖 | acceptance | verify_cmd |
|----|------|------|------------|------------|
| p41-10 | 过期候选返回409业务冲突 | p37-04 | 真实来源编辑后应用旧候选返回409且稿件不变；有效候选可应用；未分类异常仍500。 | `mvn test -Dtest=StaleRevisionCandidateApiTest` |

目标文件：`src/main/java/com/writing/novel/web/GlobalExceptionHandler.java` 与 `src/test/java/com/writing/novel/web/StaleRevisionCandidateApiTest.java`。

## 风险与备选方案

只映射明确的候选过期异常，不把所有 RuntimeException 当作409。不得用接受500的原接口断言代替本项修复；p41-09 的 UI 草稿测试仅验证失败后输入保留，不证明冲突状态已经正确。
