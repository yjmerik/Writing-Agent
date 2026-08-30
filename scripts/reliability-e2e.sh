#!/usr/bin/env bash
# p23-02: 业务生命周期 E2E——打包 Spring 应用 + 确定性场景网关 + 故障注入。
# 用法: ./scripts/reliability-e2e.sh [--port 8092]
# 场景: S1 崩溃-重启续跑 / S2 陈旧 worker 冲突拒绝 / S3 人工复核暂停-处理-续跑 / S4 真典保护唯一性
# 每场景产出 data/e2e/reliability/<scenario>/report.json；任一失败退出码非 0。
set -u
cd "$(dirname "$0")/.."

PORT=8092
while [ $# -gt 0 ]; do
  case "$1" in
    --port) PORT="$2"; shift 2 ;;
    *) echo "unknown arg: $1"; exit 1 ;;
  esac
done

ROOT="data/e2e/reliability"
mkdir -p "$ROOT"
BASE="http://localhost:$PORT"
PASS_TOTAL=0
FAIL_TOTAL=0

kill_port() {
  lsof -ti ":$1" 2>/dev/null | xargs kill -9 2>/dev/null || true
  sleep 1
}

start_app() { # $1=dir $2=port $3=extra args (string)
  mkdir -p "$1"
  mvn -q spring-boot:run \
    -Dspring-boot.run.jvmArguments="-Dnovel.events.file=$1/events.jsonl" \
    -Dspring-boot.run.arguments="--server.port=$2 --llm.scenario.enabled=true $3 --llm.replay.mode=off --persistence.embedding=stub --persistence.sqlite.db-path=$1/novel.db --persistence.vector-store.index-path=$1/vectors.idx" \
    > "$1/server.log" 2>&1 &
  for i in $(seq 1 90); do
    if curl --noproxy '*' -sf "http://localhost:$2/api/projects" > /dev/null 2>&1; then return 0; fi
    sleep 1
  done
  return 1
}

task_field() { # $1=pid $2=taskId $3=field
  curl --noproxy '*' -sf "$BASE/api/projects/$1/writing-tasks/$2" \
    | python3 -c "import json,sys; print(json.load(sys.stdin).get('$3',''))"
}

wait_task_terminal() { # $1=pid $2=taskId $3=timeout_s
  local st=""
  for i in $(seq 1 "$3"); do
    st=$(task_field "$1" "$2" status)
    case "$st" in COMPLETED|FAILED|CANCELLED|PAUSED) echo "$st"; return 0 ;; esac
    sleep 1
  done
  echo "TIMEOUT"
}

write_json() { # $1=file $2=pass(0/1) $3=scenario $4=detail $5=invariants-json
  python3 -c "
import json, sys, datetime
d = {
  'scenario': sys.argv[3],
  'pass': sys.argv[2] == '1',
  'detail': sys.argv[4],
  'invariants': json.loads(sys.argv[5]),
  'generatedAt': datetime.datetime.now().isoformat(),
}
json.dump(d, open(sys.argv[1], 'w', encoding='utf-8'), ensure_ascii=False, indent=2)
print('report:', sys.argv[1], 'pass=' + sys.argv[2])
" "$1" "$2" "$3" "$4" "$5"
}

# ---------------- S1: crash-after-stage + restart continuation ----------------
S1="$ROOT/crash-restart"
kill_port "$PORT"
rm -rf "$S1"
echo "=== S1 crash-restart ==="
S1_PASS=1
S1_DETAIL=""
if start_app "$S1" "$PORT" ""; then
  PROJECT_ID=$(curl --noproxy '*' -sf -X POST "$BASE/api/projects" -H "Content-Type: application/json" \
    -d '{"title":"S1","concept":"crash restart scenario book","tags":"mystery","mode":"SOLO","preset":"mystery"}' \
    | python3 -c "import json,sys; print(json.load(sys.stdin)['id'])")
  curl --noproxy '*' -sf -X PUT "$BASE/api/projects/$PROJECT_ID/preferences" \
    -H "Content-Type: application/json" -d '{"targetChapters":5}' > /dev/null
  CT=$(curl --noproxy '*' -sf -X POST "$BASE/api/projects/$PROJECT_ID/run" \
    | python3 -c "import json,sys; print(json.load(sys.stdin).get('taskId',''))")
  ST=""; for i in $(seq 1 300); do ST=$(task_field "$PROJECT_ID" "$CT" status); case "$ST" in COMPLETED|FAILED|CANCELLED) break ;; esac; sleep 1; done
  if [ "$ST" != "COMPLETED" ]; then S1_PASS=0; S1_DETAIL="conceive terminal=$ST"; fi

  TASK1=$(curl --noproxy '*' -sf -X POST "$BASE/api/projects/$PROJECT_ID/write-all?maxChapters=5" \
    | python3 -c "import json,sys; print(json.load(sys.stdin)['taskId'])")
  # 等第一章事件落盘后 kill -9（故障注入：进程死亡）
  KILLED=0
  for i in $(seq 1 120); do
    if [ -f "$S1/events.jsonl" ] && grep -q "CHAPTER_WRITTEN" "$S1/events.jsonl"; then KILLED=1; break; fi
    sleep 1
  done
  sleep 1
  kill_port "$PORT"
  if [ "$KILLED" != "1" ]; then S1_PASS=0; S1_DETAIL="no chapter written before kill"; fi

  # 重启同库——ApplicationRunner 自动续跑 POST_PROCESSING
  if start_app "$S1" "$PORT" ""; then
    TASK2=$(curl --noproxy '*' -sf -X POST "$BASE/api/projects/$PROJECT_ID/write-all?maxChapters=5" \
      | python3 -c "import json,sys; print(json.load(sys.stdin)['taskId'])")
    ST2=$(wait_task_terminal "$PROJECT_ID" "$TASK2" 300)
    curl --noproxy '*' -sf "$BASE/api/projects/$PROJECT_ID/chapters?page=0&size=100" > "$S1/chapters.json"
    S1_CHECK=$(python3 - "$S1/chapters.json" <<'PY'
import json, sys
payload = json.load(open(sys.argv[1], encoding="utf-8"))
chs = payload["chapters"] if isinstance(payload, dict) else payload
idx = sorted(c["chapterIndex"] for c in chs)
states = set(c["status"] for c in chs)
ok = idx == [1,2,3,4,5] and states <= {"DONE","NEEDS_MANUAL_REVIEW"}
print("PASS" if ok else "FAIL: idx=%s states=%s" % (idx, states))
PY
)
    if [ "$S1_CHECK" != "PASS" ]; then S1_PASS=0; S1_DETAIL="after restart: $S1_CHECK"; fi
    [ "$ST2" = "COMPLETED" ] || { S1_PASS=0; S1_DETAIL="second task=$ST2 $S1_DETAIL"; }
  else
    S1_PASS=0; S1_DETAIL="restart failed"
  fi
else
  S1_PASS=0; S1_DETAIL="first start failed"
fi
write_json "$S1/report.json" "$S1_PASS" "crash-restart" "$S1_DETAIL" '{"killAfterFirstChapter": true, "restartContinuation": true}' 
kill_port "$PORT"

# ---------------- S2: stale-worker conflict rejection ----------------
S2="$ROOT/stale-worker-409"
rm -rf "$S2"
echo "=== S2 stale-worker 409 ==="
S2_PASS=1
S2_DETAIL=""
if start_app "$S2" "$PORT" ""; then
  PROJECT_ID=$(curl --noproxy '*' -sf -X POST "$BASE/api/projects" -H "Content-Type: application/json" \
    -d '{"title":"S2","concept":"stale worker scenario book","tags":"mystery","mode":"SOLO","preset":"mystery"}' \
    | python3 -c "import json,sys; print(json.load(sys.stdin)['id'])")
  curl --noproxy '*' -sf -X PUT "$BASE/api/projects/$PROJECT_ID/preferences" \
    -H "Content-Type: application/json" -d '{"targetChapters":5}' > /dev/null
  CT=$(curl --noproxy '*' -sf -X POST "$BASE/api/projects/$PROJECT_ID/run" \
    | python3 -c "import json,sys; print(json.load(sys.stdin).get('taskId',''))")
  ST=""; for i in $(seq 1 300); do ST=$(task_field "$PROJECT_ID" "$CT" status); case "$ST" in COMPLETED|FAILED|CANCELLED) break ;; esac; sleep 1; done

  TASK1=$(curl --noproxy '*' -sf -X POST "$BASE/api/projects/$PROJECT_ID/write-all?maxChapters=5" \
    | python3 -c "import json,sys; print(json.load(sys.stdin)['taskId'])")
  # 活动期竞争入口必须 409
  CODE_WA=$(curl --noproxy '*' -sf -o /dev/null -w '%{http_code}' -X POST "$BASE/api/projects/$PROJECT_ID/write-all?maxChapters=5")
  CODE_WC=$(curl --noproxy '*' -sf -o /dev/null -w '%{http_code}' -X POST "$BASE/api/projects/$PROJECT_ID/write-chapter" \
    -H "Content-Type: application/json" -d '{}')
  if [ "$CODE_WA" != "409" ] || [ "$CODE_WC" != "409" ]; then
    S2_PASS=0; S2_DETAIL="competing entries: write-all=$CODE_WA write-chapter=$CODE_WC (expect 409/409)"
  fi
  curl --noproxy '*' -sf -X POST "$BASE/api/projects/$PROJECT_ID/writing-tasks/$TASK1/cancel" > /dev/null
  ST=$(wait_task_terminal "$PROJECT_ID" "$TASK1" 120)
  # 终态后新任务可启动（无强制接管路径的恢复性证明）
  TASK2=$(curl --noproxy '*' -sf -o /dev/null -w '%{http_code}' -X POST "$BASE/api/projects/$PROJECT_ID/write-all?maxChapters=1")
  if [ "$TASK2" != "200" ]; then S2_PASS=0; S2_DETAIL="after terminal, new write-all=$TASK2 (expect 200) $S2_DETAIL"; fi
else
  S2_PASS=0; S2_DETAIL="start failed"
fi
write_json "$S2/report.json" "$S2_PASS" "stale-worker-409" "$S2_DETAIL" '{"activeConflict409": true, "terminalReleaseThenAccept": true}' 
kill_port "$PORT"

# ---------------- S3: manual-review pause -> resolve -> continue ----------------
S3="$ROOT/manual-review"
rm -rf "$S3"
echo "=== S3 manual-review (harsh profile) ==="
S3_PASS=1
S3_DETAIL=""
if start_app "$S3" "$PORT" "--llm.scenario.harsh=true"; then
  PROJECT_ID=$(curl --noproxy '*' -sf -X POST "$BASE/api/projects" -H "Content-Type: application/json" \
    -d '{"title":"S3","concept":"manual review scenario book","tags":"mystery","mode":"SOLO","preset":"mystery"}' \
    | python3 -c "import json,sys; print(json.load(sys.stdin)['id'])")
  curl --noproxy '*' -sf -X PUT "$BASE/api/projects/$PROJECT_ID/preferences" \
    -H "Content-Type: application/json" -d '{"targetChapters":3}' > /dev/null
  CT=$(curl --noproxy '*' -sf -X POST "$BASE/api/projects/$PROJECT_ID/run" \
    | python3 -c "import json,sys; print(json.load(sys.stdin).get('taskId',''))")
  ST=""; for i in $(seq 1 300); do ST=$(task_field "$PROJECT_ID" "$CT" status); case "$ST" in COMPLETED|FAILED|CANCELLED) break ;; esac; sleep 1; done

  TASK1=$(curl --noproxy '*' -sf -X POST "$BASE/api/projects/$PROJECT_ID/write-all?maxChapters=3" \
    | python3 -c "import json,sys; print(json.load(sys.stdin)['taskId'])")
  ST1=$(wait_task_terminal "$PROJECT_ID" "$TASK1" 300)
  if [ "$ST1" != "PAUSED" ]; then S3_PASS=0; S3_DETAIL="first task=$ST1 (expect PAUSED by NEEDS_MANUAL_REVIEW)"; fi

  CH1_STATE=$(curl --noproxy '*' -sf "$BASE/api/projects/$PROJECT_ID/chapters/1" \
    | python3 -c "import json,sys; print(json.load(sys.stdin).get('status',''))")
  if [ "$CH1_STATE" != "NEEDS_MANUAL_REVIEW" ]; then S3_PASS=0; S3_DETAIL="ch1=$CH1_STATE (expect NEEDS_MANUAL_REVIEW) $S3_DETAIL"; fi

  RESOLVE=$(curl --noproxy '*' -sf -o /dev/null -w '%{http_code}' -X POST "$BASE/api/projects/$PROJECT_ID/chapters/1/resolve-review")
  if [ "$RESOLVE" != "200" ]; then S3_PASS=0; S3_DETAIL="resolve-review=$RESOLVE $S3_DETAIL"; fi

  TASK2=$(curl --noproxy '*' -sf -X POST "$BASE/api/projects/$PROJECT_ID/write-all?maxChapters=3" \
    | python3 -c "import json,sys; print(json.load(sys.stdin)['taskId'])")
  ST2=$(wait_task_terminal "$PROJECT_ID" "$TASK2" 300)
  curl --noproxy '*' -sf "$BASE/api/projects/$PROJECT_ID/chapters?page=0&size=100" > "$S3/chapters.json"
  S3_CHECK=$(python3 - "$S3/chapters.json" <<'PY'
import json, sys
payload = json.load(open(sys.argv[1], encoding="utf-8"))
chs = payload["chapters"] if isinstance(payload, dict) else payload
idx = sorted(c["chapterIndex"] for c in chs)
ok = idx == [1,2,3] and all(c["status"] == "DONE" for c in chs)
print("PASS" if ok else "FAIL: idx=%s states=%s" % (idx, [c["status"] for c in chs]))
PY
)
  if [ "$S3_CHECK" != "PASS" ] || [ "$ST2" != "COMPLETED" ]; then
    S3_PASS=0; S3_DETAIL="continue: $S3_CHECK task2=$ST2 $S3_DETAIL"
  fi
else
  S3_PASS=0; S3_DETAIL="start failed"
fi
write_json "$S3/report.json" "$S3_PASS" "manual-review" "$S3_DETAIL" '{"pauseOnManualReview": true, "resolveThenContinue": true}' 
kill_port "$PORT"

# ---------------- S4: canon protection uniqueness ----------------
S4="$ROOT/canon-protection"
rm -rf "$S4"
echo "=== S4 canon protection ==="
S4_PASS=1
S4_DETAIL=""
if start_app "$S4" "$PORT" ""; then
  PROJECT_ID=$(curl --noproxy '*' -sf -X POST "$BASE/api/projects" -H "Content-Type: application/json" \
    -d '{"title":"S4","concept":"canon protection scenario book","tags":"mystery","mode":"SOLO","preset":"mystery"}' \
    | python3 -c "import json,sys; print(json.load(sys.stdin)['id'])")
  # 同 type+subject 人工补丁冲突写入两次——必须只留一条 ACTIVE（最新胜出）
  curl --noproxy '*' -sf -X POST "$BASE/api/projects/$PROJECT_ID/facts" \
    -H "Content-Type: application/json" \
    -d '{"factType":"CHARACTER_STATE","subject":"季衡","assertion":"命格：不灭玄体"}' > /dev/null
  curl --noproxy '*' -sf -X POST "$BASE/api/projects/$PROJECT_ID/facts" \
    -H "Content-Type: application/json" \
    -d '{"factType":"CHARACTER_STATE","subject":"季衡","assertion":"命格：凡体"}' > /dev/null
  curl --noproxy '*' -sf "$BASE/api/projects/$PROJECT_ID/facts" > "$S4/facts.json"
  S4_CHECK=$(python3 - "$S4/facts.json" <<'PY'
import json, sys
facts = json.load(open(sys.argv[1], encoding="utf-8"))
target = [f for f in facts if f.get("subject") == "季衡" and f.get("status") == "ACTIVE"]
latest_won = any("凡体" in (f.get("assertion") or "") for f in target)
ok = len(target) == 1 and latest_won
print("PASS" if ok else "FAIL: active=%d latestWon=%s" % (len(target), latest_won))
PY
)
  if [ "$S4_CHECK" != "PASS" ]; then S4_PASS=0; S4_DETAIL="$S4_CHECK"; fi

  # 归档后 ACTIVE 归零（审计保留在库内，接口只见 ACTIVE）
  FID=$(curl --noproxy '*' -sf "$BASE/api/projects/$PROJECT_ID/facts" \
    | python3 -c "import json,sys; fs=[f for f in json.load(sys.stdin) if f.get('subject')=='季衡']; print(fs[0]['id'] if fs else '')")
  if [ -n "$FID" ]; then
    RETIRE=$(curl --noproxy '*' -sf -o /dev/null -w '%{http_code}' -X POST "$BASE/api/projects/$PROJECT_ID/facts/$FID/retire")
    LEFT=$(curl --noproxy '*' -sf "$BASE/api/projects/$PROJECT_ID/facts" \
      | python3 -c "import json,sys; print(sum(1 for f in json.load(sys.stdin) if f.get('subject')=='季衡'))")
    if [ "$RETIRE" != "200" ] || [ "$LEFT" != "0" ]; then S4_PASS=0; S4_DETAIL="retire=$RETIRE left=$LEFT $S4_DETAIL"; fi
  else
    S4_PASS=0; S4_DETAIL="no fact id for retire"
  fi
else
  S4_PASS=0; S4_DETAIL="start failed"
fi
write_json "$S4/report.json" "$S4_PASS" "canon-protection" "$S4_DETAIL" '{"activeUniquenessLatestWins": true, "retireRemovesFromActive": true}' 
kill_port "$PORT"

# ---------------- aggregate ----------------
AGG=$(python3 - "$ROOT" <<'PY'
import json, glob, sys, datetime, os
results = {}
for p in sorted(glob.glob(sys.argv[1] + "/*/report.json")):
    r = json.load(open(p, encoding="utf-8"))
    results[r["scenario"]] = r
allpass = all(r["pass"] for r in results.values()) and len(results) == 4
summary = {"generatedAt": datetime.datetime.now().isoformat(), "allPass": allpass,
           "scenarios": {k: {"pass": v["pass"], "detail": v["detail"]} for k, v in results.items()}}
with open(os.path.join(sys.argv[1], "summary.json"), "w", encoding="utf-8") as f:
    json.dump(summary, f, ensure_ascii=False, indent=2)
print("PASS all %d scenarios" % len(results) if allpass else "FAIL some scenarios")
sys.exit(0 if allpass else 1)
PY
)
AGG_EXIT=$?
echo "$AGG"
if [ $AGG_EXIT -eq 0 ]; then
  echo "RELIABILITY-E2E PASS — reports: $ROOT/<scenario>/report.json"
else
  echo "RELIABILITY-E2E FAIL — see $ROOT/summary.json"
fi
exit $AGG_EXIT
