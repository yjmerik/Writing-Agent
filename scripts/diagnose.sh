#!/usr/bin/env bash
# 诊断报告：解析结构化事件日志，输出 agent 可读的失败分类 / LLM 统计 / 最近章节。
# 用法: ./scripts/diagnose.sh [events.jsonl 路径] [--project <projectId>]
set -e

DEFAULT_EVENTS_FILE="data/logs/events.jsonl"
EVENTS_FILE="$DEFAULT_EVENTS_FILE"
PROJECT_FILTER=""
while [ $# -gt 0 ]; do
  case "$1" in
    --project) PROJECT_FILTER="$2"; shift 2 ;;
    *) EVENTS_FILE="$1"; shift ;;
  esac
done

if [ ! -f "$EVENTS_FILE" ]; then
  echo "⚠️ 事件日志不存在: $EVENTS_FILE（服务运行后自动生成）"
  exit 0
fi

python3 - "$EVENTS_FILE" "$PROJECT_FILTER" <<'PYEOF'
import json, sys
from collections import defaultdict

events_file, project_filter = sys.argv[1], sys.argv[2]
events = []
with open(events_file, encoding="utf-8") as f:
    for line in f:
        line = line.strip()
        if not line:
            continue
        try:
            e = json.loads(line)
        except json.JSONDecodeError:
            continue
        if project_filter and e.get("projectId") != project_filter:
            continue
        events.append(e)

print(f"# 诊断报告 — {events_file}")
print(f"事件总数: {len(events)}" + (f"（项目过滤: {project_filter}）" if project_filter else ""))
if not events:
    sys.exit(0)
print(f"时间范围: {events[0].get('ts','?')} ~ {events[-1].get('ts','?')}\n")

failures = [e for e in events if e["event"] in ("LLM_ERROR", "CHAPTER_FAILED")]
print(f"## 失败分类（共 {len(failures)} 条）")
if failures:
    by_class = defaultdict(list)
    for e in failures:
        by_class[(e["event"], e.get("errorClass", "?"))].append(e)
    for (event, cls), items in sorted(by_class.items(), key=lambda kv: -len(kv[1])):
        latest = items[-1]
        chapters = sorted({i.get("chapter") or i.get("chapterIndex") for i in items if i.get("chapter") or i.get("chapterIndex")})
        print(f"- **{event}/{cls}** ×{len(items)}"
              + (f" 涉及章节 {chapters}" if chapters else ""))
        print(f"  最近一次: {latest.get('ts','?')} — {latest.get('message','')[:150]}")
else:
    print("无失败记录 ✅")

llm_calls = [e for e in events if e["event"] == "LLM_CALL"]
print(f"\n## LLM 调用统计（共 {len(llm_calls)} 次）")
by_task = defaultdict(list)
for e in llm_calls:
    by_task[e.get("taskType", "?")].append(e)
grand_total_cost = 0.0
for task, items in sorted(by_task.items(), key=lambda kv: -len(kv[1])):
    durs = [i.get("durationMs", 0) for i in items]
    tokens = sum(i.get("promptTokens", 0) + i.get("completionTokens", 0) for i in items)
    cost = sum(i.get("estimatedCostCny", 0.0) for i in items)
    grand_total_cost += cost
    print(f"- {task}: {len(items)} 次, 平均耗时 {sum(durs)//max(len(durs),1)}ms, "
          f"总tokens {tokens}, 估算成本 ¥{cost:.4f}")
print(f"### 总估算成本: ¥{grand_total_cost:.4f}")

chapters = [e for e in events if e["event"] == "CHAPTER_WRITTEN"]
print(f"\n## 最近写作章节（共 {len(chapters)} 章）")
for e in chapters[-10:]:
    print(f"- 第{e.get('chapterIndex','?')}章 {e.get('wordCount','?')}字 "
          f"耗时{e.get('durationMs',0)//1000}s [{e.get('projectId','?')}] {e.get('ts','')}")

# p11-q3-02: 质量诊断分类——解析质量事件（重写成功率/根因/熔断/FINAL/quality_risk）
quality_events = [e for e in events if e["event"] in
    ("QUALITY_REVIEW", "REWRITE_TRIGGERED", "CONSISTENCY_ISSUE", "QUALITY_CIRCUIT_BREAKER", "FINAL_GATE", "QUALITY_GATE")]
print(f"\n## 质量诊断（共 {len(quality_events)} 条质量事件）")
if quality_events:
    reviews = [e for e in quality_events if e["event"] == "QUALITY_REVIEW"]
    rewrites = [e for e in quality_events if e["event"] == "REWRITE_TRIGGERED"]
    failed_reviews = [e for e in reviews if not e.get("passed", True)]
    if reviews:
        rate = (1 - len(failed_reviews) / len(reviews)) * 100
        print(f"- 读者检验通过率: {rate:.0f}%（{len(reviews)-len(failed_reviews)}/{len(reviews)}），重写 {len(rewrites)} 次")
    issues = [e for e in quality_events if e["event"] == "CONSISTENCY_ISSUE"]
    if issues:
        by_type = defaultdict(int)
        for e in issues:
            by_type[e.get("type", "?")] += 1
        top = sorted(by_type.items(), key=lambda kv: -kv[1])[:3]
        print(f"- 一致性问题根因 Top: {', '.join(f'{t}×{c}' for t, c in top)}")
    breakers = [e for e in quality_events if e["event"] == "QUALITY_CIRCUIT_BREAKER"]
    if breakers:
        print(f"- ⚠️ 质量熔断 {len(breakers)} 次（连续 HIGH 问题暂停写作）")
    final_fails = [e for e in quality_events if e["event"] == "FINAL_GATE" and not e.get("passed", True)]
    if final_fails:
        reasons = ', '.join(sorted(set(e.get('reason', '?') for e in final_fails)))
        print(f"- ⚠️ FINAL 门禁失败 {len(final_fails)} 次，原因: {reasons}")
    gates = [e for e in quality_events if e["event"] == "QUALITY_GATE"]
    if gates:
        print(f"- quality_risk 标记 {len(gates)} 处（构思/规划门禁未过）")
else:
    print("无质量事件记录（质检闭环未触发或事件未启用）")

print("\n## 结论建议")
if failures:
    top = max(by_class.items(), key=lambda kv: len(kv[1]))
    print(f"- 最高频失败: {top[0][0]}/{top[0][1]} ×{len(top[1])} — 建议以此为修复起点")
    print("- 修复流程: 先写 failing test 复现 → 修复 → mvn test → 重跑 e2e")
else:
    print("- 系统运行正常，无需修复动作")
PYEOF

APP_LOG="data/logs/app.log"
if [ "$EVENTS_FILE" = "$DEFAULT_EVENTS_FILE" ] && [ -f "$APP_LOG" ]; then
  echo ""
  echo "## app.log 最近 WARN/ERROR（最多 15 条）"
  grep -E " (WARN|ERROR) " "$APP_LOG" | tail -15 || echo "无"
fi
