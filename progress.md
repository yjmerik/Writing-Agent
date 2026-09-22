# F1修复方案设计与特性拆分 — 2026-09-22

Last Updated: 2026-09-22

## Current
针对UI验证F1死锁完成修复方案设计并按需求拆分协议登记：spec
docs/specs/2026-09-22-high-adjudication-unblock.md；NCS-071收敛为裁决表单（前端）、
新增NCS-072（暂停任务终止语义/后端）、NCS-073（终止入口与409指引/前端）。
纯文档变更，active_feature=null。

## Files
docs/specs/2026-09-22-high-adjudication-unblock.md：根因取证（裁决API完备但无提交UI；
cancel对PAUSED仅设标志不终态转移不释放owner）+两出路设计+验收标准。
feature_list.json：NCS-071重新收敛+072/073登记（含verify_cmd与依赖）。

## Verification
纯文档；干净main全量1187/0/0/1通过；提交b512d7f ff-only合入main。
方案关键取证：批准门禁（SqliteNovelRepository审计P1-2）认绑定当前稿的
FALSE_POSITIVE/EXCEPTION裁决；dispositions API强制理由+实时身份校验；
cancel(taskId)仅设cancelFlags，对PAUSED无终态转移不释放coordinator owner。

## Blockers
无。方案待用户授权实现。

## Next Steps
授权后按顺序实现：NCS-071（主链路裁决→批准→继续）与NCS-072→073（占坑兜底）可并行，
各走特性分支闭环；端到端两条出路各留证一次。
