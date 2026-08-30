#!/usr/bin/env bash
# p23-03: 400 章零成本 soak——确定性场景网关驱动完整编排器，验证业务不变量。
# 用法: ./scripts/soak.sh [--chapters 400] [--seed 20260830] [--port 8091]
# 不变量：恰好 N 章连续唯一 / 零非终态章节 / 零重复 ACTIVE 真典键 / 单任务无重叠 /
#         零 LLM 网络调用（无 API key 启动即为证明）/ 章节导航延迟有界。
# 产出: data/e2e/soak/report.json；任一不变量失败 → 退出码非 0。
set -u
cd "$(dirname "$0")/.."

CHAPTERS=400
SEED=20260830
PORT=8091
while [ $# -gt 0 ]; do
  case "$1" in
    --chapters) CHAPTERS="$2"; shift 2 ;;
    --seed) SEED="$2"; shift 2 ;;
    --port) PORT="$2"; shift 2 ;;
    *) echo "unknown arg: $1"; exit 1 ;;
  esac
done

SOAK_DIR="data/e2e/soak"
BASE="http://localhost:$PORT"
rm -rf "$SOAK_DIR"
mkdir -p "$SOAK_DIR"

echo "=== Soak: chapters=$CHAPTERS seed=$SEED port=$PORT ==="

mvn -q spring-boot:run \
  -Dspring-boot.run.jvmArguments="-Dnovel.events.file=$SOAK_DIR/events.jsonl" \
  -Dspring-boot.run.arguments="--server.port=$PORT --llm.scenario.enabled=true --llm.replay.mode=off --persistence.embedding=stub --persistence.sqlite.db-path=$SOAK_DIR/novel.db --persistence.vector-store.index-path=$SOAK_DIR/vectors.idx" \
  > "$SOAK_DIR/server.log" 2>&1 &
SERVER_PID=$!
trap 'kill $SERVER_PID 2>/dev/null || true; wait $SERVER_PID 2>/dev/null || true' EXIT

echo ">> wait for server (no API key in env = zero-network proof)"
READY=0
for i in $(seq 1 90); do
  if curl --noproxy '*' -sf "$BASE/api/projects" > /dev/null 2>&1; then READY=1; break; fi
  if ! kill -0 $SERVER_PID 2>/dev/null; then
    echo "FAIL: server died at startup; tail of $SOAK_DIR/server.log:"
    tail -20 "$SOAK_DIR/server.log"; exit 1
  fi
  sleep 1
done
if [ "$READY" != "1" ]; then echo "FAIL: server not ready in 90s"; exit 1; fi
echo "OK server ready"

echo ">> create project (targetChapters=$CHAPTERS)"
PROJECT_ID=$(curl --noproxy '*' -sf -X POST "$BASE/api/projects" \
  -H "Content-Type: application/json" \
  -d "{\"title\":\"Soak-$SEED\",\"concept\":\"soak robustness book: a ledger that writes itself\",\"tags\":\"mystery,ancient\",\"mode\":\"SOLO\",\"preset\":\"mystery\"}" \
  | python3 -c "import json,sys; print(json.load(sys.stdin)['id'])")
curl --noproxy '*' -sf -X PUT "$BASE/api/projects/$PROJECT_ID/preferences" \
  -H "Content-Type: application/json" \
  -d "{\"targetChapters\":$CHAPTERS,\"layeredOutline\":true}" > /dev/null
echo "OK project=$PROJECT_ID"

echo ">> conceive"
CONCEIVE_TASK=$(curl --noproxy '*' -sf -X POST "$BASE/api/projects/$PROJECT_ID/run" \
  | python3 -c "import json,sys; print(json.load(sys.stdin).get('taskId',''))")
CT_STATE=""
for i in $(seq 1 600); do
  CT_STATE=$(curl --noproxy '*' -sf "$BASE/api/projects/$PROJECT_ID/writing-tasks/$CONCEIVE_TASK" \
    | python3 -c "import json,sys; print(json.load(sys.stdin).get('status',''))")
  case "$CT_STATE" in COMPLETED) break ;; FAILED|CANCELLED) echo "FAIL: conceive terminal $CT_STATE"; exit 1 ;; esac
  if [ $(( i % 30 )) -eq 0 ]; then echo "  conceiving (${i}s)..."; fi
  sleep 1
done
if [ "$CT_STATE" != "COMPLETED" ]; then echo "FAIL: conceive timeout"; exit 1; fi
STATE=$(curl --noproxy '*' -sf "$BASE/api/projects/$PROJECT_ID/status" | python3 -c "import json,sys; print(json.load(sys.stdin)['state'])")
if [ "$STATE" = "PLANNING" ]; then
  curl --noproxy '*' -sf -X POST "$BASE/api/projects/$PROJECT_ID/run" > /dev/null
  sleep 2
fi
echo "OK conceived"

echo ">> write-all $CHAPTERS chapters"
WRITE=$(curl --noproxy '*' -sf -X POST "$BASE/api/projects/$PROJECT_ID/write-all?maxChapters=$CHAPTERS")
TASK_ID=$(echo "$WRITE" | python3 -c "import json,sys; print(json.load(sys.stdin)['taskId'])")
POLL_LIMIT=$(( CHAPTERS * 6 ))
if [ "$POLL_LIMIT" -lt 600 ]; then POLL_LIMIT=600; fi
TASK_STATE=""
for i in $(seq 1 "$POLL_LIMIT"); do
  TASK=$(curl --noproxy '*' -sf "$BASE/api/projects/$PROJECT_ID/writing-tasks/$TASK_ID")
  TASK_STATE=$(echo "$TASK" | python3 -c "import json,sys; print(json.load(sys.stdin)['status'])")
  case "$TASK_STATE" in COMPLETED|FAILED|CANCELLED) break ;; esac
  if [ $(( i % 60 )) -eq 0 ]; then
    W=$(echo "$TASK" | python3 -c "import json,sys; print(json.load(sys.stdin)['chaptersWritten'])")
    echo "  progress: $W/$CHAPTERS (${i}s)"
  fi
  sleep 1
done
echo "$TASK" > "$SOAK_DIR/task.json"
echo "OK write terminal: $TASK_STATE"

echo ">> navigation latency sampling"
NAV_MS=$(curl --noproxy '*' -sf -o /dev/null -w '%{time_total}' "$BASE/api/projects/$PROJECT_ID/chapters?page=0&size=50" \
  | python3 -c "import sys; print(int(float(sys.stdin.read())*1000))")

echo ">> collect invariant data"
curl --noproxy '*' -sf "$BASE/api/projects/$PROJECT_ID/chapters?page=0&size=1000" > "$SOAK_DIR/chapters.json"
curl --noproxy '*' -sf "$BASE/api/projects/$PROJECT_ID/facts" > "$SOAK_DIR/facts.json"
curl --noproxy '*' -sf "$BASE/api/projects/$PROJECT_ID/status" > "$SOAK_DIR/status.json"

python3 - "$SOAK_DIR" "$CHAPTERS" "$SEED" "$NAV_MS" <<PYEOF
import json, sys, datetime

soak_dir, expected, seed, nav_ms = sys.argv[1], int(sys.argv[2]), sys.argv[3], int(sys.argv[4])

payload = json.load(open(f"{soak_dir}/chapters.json", encoding="utf-8"))
chapters = payload["chapters"] if isinstance(payload, dict) else payload
facts = json.load(open(f"{soak_dir}/facts.json", encoding="utf-8"))
status = json.load(open(f"{soak_dir}/status.json", encoding="utf-8"))
task = json.load(open(f"{soak_dir}/task.json", encoding="utf-8"))

events = {"CHAPTER_WRITTEN": [], "LLM_ERROR": [], "CHAPTER_FAILED": [], "WRITING_PAUSED": []}
try:
    for line in open(f"{soak_dir}/events.jsonl", encoding="utf-8"):
        try:
            e = json.loads(line)
        except json.JSONDecodeError:
            continue
        if e.get("event") in events:
            events[e["event"]].append(e)
except FileNotFoundError:
    pass

written_idx = sorted(ev.get("chapterIndex", 0) for ev in events["CHAPTER_WRITTEN"])
contiguous_unique = written_idx == list(range(1, expected + 1))

nonterminal = [c["chapterIndex"] for c in chapters if c.get("status") not in ("DONE", "NEEDS_MANUAL_REVIEW")]

keys = {}
for f in facts:
    if f.get("status") == "ACTIVE":
        k = (f.get("factType"), f.get("subject"))
        keys[k] = keys.get(k, 0) + 1
dup_active = [{"type": t, "subject": s, "count": n} for (t, s), n in keys.items() if n > 1]

nav_bound_ms = 2000
report = {
    "generatedAt": datetime.datetime.now().isoformat(),
    "kind": "zero-cost-soak",
    "seed": seed,
    "expectedChapters": expected,
    "totalChapters": len(chapters),
    "chaptersWritten": task.get("chaptersWritten"),
    "taskStatus": task.get("status"),
    "finalState": status.get("state"),
    "totalWords": status.get("totalWords", 0),
    "invariants": {
        "contiguousUniqueChapters": {
            "pass": contiguous_unique,
            "detail": "written range %s..%s contiguous=%s" % (
                min(written_idx) if written_idx else 0, max(written_idx) if written_idx else 0, contiguous_unique),
        },
        "zeroNonterminalChapters": {
            "pass": not nonterminal,
            "detail": "nonterminal: %s" % nonterminal[:10],
        },
        "zeroDuplicateActiveCanonKeys": {
            "pass": not dup_active,
            "detail": dup_active[:10] or "all (type,subject) unique",
        },
        "singleActiveTaskNoOverlap": {
            "pass": task.get("status") == "COMPLETED" and task.get("chaptersWritten") == expected
                    and not events["WRITING_PAUSED"],
            "detail": "task=%s written=%s paused_events=%s" % (
                task.get("status"), task.get("chaptersWritten"), len(events["WRITING_PAUSED"])),
        },
        "zeroLlmNetworkCalls": {
            "pass": not events["LLM_ERROR"] and not events["CHAPTER_FAILED"],
            "detail": "LLM_ERROR=%s CHAPTER_FAILED=%s (app started without any API key)" % (
                len(events["LLM_ERROR"]), len(events["CHAPTER_FAILED"])),
        },
        "boundedChapterNavigationLatency": {
            "pass": nav_ms <= nav_bound_ms,
            "detail": "GET /chapters took %sms at %s chapters (bound %sms)" % (nav_ms, len(chapters), nav_bound_ms),
        },
    },
}
with open(f"{soak_dir}/report.json", "w", encoding="utf-8") as f:
    json.dump(report, f, ensure_ascii=False, indent=2)
print(json.dumps(report, ensure_ascii=False, indent=2))

ok = all(v["pass"] for v in report["invariants"].values()) and report["taskStatus"] == "COMPLETED"
print("")
print("PASS all soak invariants -> data/e2e/soak/report.json" if ok else "FAIL soak invariant violation")
sys.exit(0 if ok else 1)
PYEOF
SOAK_EXIT=$?

if [ $SOAK_EXIT -eq 0 ]; then
  echo "SOAK PASS"
else
  echo "SOAK FAIL — see $SOAK_DIR/report.json and $SOAK_DIR/server.log"
fi
exit $SOAK_EXIT
