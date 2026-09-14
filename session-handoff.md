# 协作修复实施交接（连续实施中） — 2026-09-14

Last Updated: 2026-09-14

## Current（工作台账，随实施滚动更新）

用户已授权连续实施剩余全部 feature（"一次把剩余的feature都实现，不用询问"）。按依赖拓扑逐项闭环，每项：分支→RED（最小编译契约后行为失败）→实现→专项+全量→ff 合并→干净 main 复跑→register.py 登记。

已完成（本次连续实施）：p36-02,03,04,05,06,07,01,12,13（Phase36 全部13项）；p37-01,02,14,09,06,07,15,11,10。main 最新 600fa6e。计数 283 completed / 36 not-started。check-harness PASS。

## Verification 基线

main 全量 807/0/0/1skip；QG：e2e replay mystery PASS + compare-baseline PASS（意见注入对无意见项目逐字不变，未 bless，无需重建语料）。

## 实施模式（务必复用）

- 分支：git checkout -b codex/<id>-<slug>；feature_list 标 in-progress + active_feature。
- RED：新接口先加 SPI/域最小编译契约（缺省抛 UnsupportedOperationException），测试跑行为性失败后先提交 RED，再实现。
- 登记：python3 /tmp/register.py <feature_id> <branch> <full_commit> /tmp/ev-<id>.json（evidence JSON 需含 evidence_line 字段）；然后 git add 三个文件提交 docs(<id>)。
- QA spec：放 qa/collaboration/<name>.spec.ts；QA_BASE_URL 只允许 localhost；场景服务：mvn spring-boot:run -Dspring-boot.run.arguments="--server.address=127.0.0.1 --server.port=18083 --llm.scenario.enabled=true --llm.scenario.harsh=true --persistence.embedding=stub --persistence.sqlite.db-path=$QA_RUN_DIR/novel.db --persistence.vector-store.index-path=$QA_RUN_DIR/vectors.idx"；环境变量必须 bash -c 'source scripts/load-env.sh && ...'（zsh 不兼容）。
- 已知坑：AntD 两字按钮 accessible name 有空格（"取 消"）；stub 网关 harsh 注入按调用序只覆盖前2次检查；POST /chapters 创建 ≥500 字草稿版本号从1跳2；带图标按钮 aria-label；契约性 409/剪贴板降级的 console.error 需过滤；saveChapterRevision 要求 revised 复用现有行 id；测试直连 PromptTemplateService 需手动 loadTemplates()；结构化编号行 [D-x revN] 提取 ID 含连字符需与结果 ID 一致。

## 剩余 36 项清单（按拓扑序）

后端（先行）：p37-16(10,15,07✓) → p37-03(02,14✓) → p37-12(03,07,10,11) → p38-01(✓deps) → p38-02 → p38-03 → p38-09 → p39-01 → p39-10 → p39-02 → p38-10 → p38-13 → p38-05 → p38-06 → p38-07 → p38-11 → p39-03 → p39-07 → p39-08 → p39-04 → p39-09 → p40-01 → p40-02 → p40-04（协议+脚本可完成）。
QA（服务起来后逐个）：p37-04, p37-05, p37-13(需p37-16), p38-04, p38-08, p38-12, p39-05, p39-06, p40-03。
无法由代码代填：p40-06（真实两题材20章盲评+人工负担实验，需真实模型预算与真人评分）→ 保持 in-progress 如实标注；p40-05（依赖 p40-06）→ 脚本可写，验收依赖 p40-06。

## Files / Next Steps

- 下一项：p37-16（章节生成完成后落库意见落实证据；mvn test -Dtest=GeneratedDirectiveEvidenceTest；接 NovelOrchestrator 章节完成后调用 DirectiveEvidenceAgent.evaluate 并经 saveDirectiveEvidence 落库，核对失败=UNMET 不假造；引用 p36-07 修订边界）。
- 计划细节：docs/superpowers/plans/2026-09-12-collaboration-repair-p3{7,8,9}.md 与 p40.md。
- 每项 verify_cmd 见 feature_list.json。

## Blockers

p40-06 需真实模型预算+真人评分（Agent 不可代填）；p40-05 验收依赖 p40-06。其余无阻塞。

## Next Steps

- 已完成：p37-16（d2a2a04，811/0/0/1skip）。
- 下一项：p37-03（修订服务使用持久候选和恢复查询，PersistentRevisionApiTest，依赖已满足），随后 p37-12 → p38 系列（01→02→03→09→10→13→05→06→07→11）→ p39 系列（01→10→02→03→07→08→04→09）→ QA 系列（p37-04/05/13、p38-04/08/12、p39-05/06、p40-03）→ p40-01/02/04 → p40-05/06（真实实验依赖，如实标注）。
