#!/usr/bin/env bash
# 端到端测试：启动服务 → 建项目 → 构思 → 写 N 章 → 产出 e2e-report.json。
# 用法: ./scripts/e2e.sh [--mode replay|record|live] [--chapters N] [--port P] [--evaluate] [--preset mystery|wuxia|...] [--acceptance full|smoke]
#   replay: 功能冒烟——用 data/fixtures/llm 回放 3 章，验证管道完整性（确定性、零成本）
#   record: 真实调用 LLM 并录制 fixture + 生成完整长篇（建基线/长篇验证用）
#   live:   真实调用，不录制
set -e
cd "$(dirname "$0")/.."

MODE="replay"
CHAPTERS=""
PORT=8090
EVALUATE=false
ACCEPTANCE="full"
PRESET="mystery"
PRESET_TAGS="悬疑,古代"
while [ $# -gt 0 ]; do
  case "$1" in
    --acceptance) ACCEPTANCE="$2"; shift 2 ;;
    --mode) MODE="$2"; shift 2 ;;
    --chapters) CHAPTERS="$2"; shift 2 ;;
    --port) PORT="$2"; shift 2 ;;
    --evaluate) EVALUATE=true; shift ;;
    --preset) PRESET="$2"; shift 2
      case "$PRESET" in
        wuxia) PRESET_TAGS="武侠,江湖" ;;
        *) PRESET_TAGS="悬疑,古代" ;;
      esac ;;
    *) echo "未知参数: $1"; exit 1 ;;
  esac
done

case "$ACCEPTANCE" in full|smoke) ;; *) echo "非法 acceptance: $ACCEPTANCE"; exit 1 ;; esac

# replay 模式固定 3 章（功能冒烟，不重跑完整长篇）；record/live 按 --chapters（默认 3）
if [ "$MODE" = "replay" ]; then
  CHAPTERS=3
  echo "ℹ️ replay 模式：固定 3 章回放，验收模式=$ACCEPTANCE"
else
  CHAPTERS="${CHAPTERS:-3}"
fi

E2E_DIR="data/e2e"
# p23-01: 未显式指定 E2E_FIXTURE_DIR 时，自动解析到 data/e2e-fixtures/ 下
# 含 fixture 文件的最新（字典序最大=版本最新）语料目录，找不到则明确报错；
# 显式指定的目录原样透传（缺失时由 replay 首个 fixture miss 快速失败）
FIXTURE_DIR="${E2E_FIXTURE_DIR:-}"
if [ -z "$FIXTURE_DIR" ]; then
  # p32-10: 自动选择必须与 preset 匹配，且按目录名（含日期后缀）字典序取最新——
  # mtime 会被复制/checkout 扰动，曾让默认 mystery 流程选中 wuxia 语料，
  # WORLDBUILDING 指纹 miss 导致构思 FAILED
  for d in $(ls -d data/e2e-fixtures/*"$PRESET"* 2>/dev/null | sort -r); do
    if ls "$d"/*.json > /dev/null 2>&1; then FIXTURE_DIR="$d"; break; fi
  done
  if [ -z "$FIXTURE_DIR" ]; then
    echo "❌ 未找到与 preset '$PRESET' 匹配的回放语料：请先录制 (./scripts/e2e.sh --mode record --preset $PRESET) 或显式设置 E2E_FIXTURE_DIR=<语料目录>"
    exit 1
  fi
  echo "ℹ️ 未指定 E2E_FIXTURE_DIR，自动选用与 preset '$PRESET' 匹配的最新语料: $FIXTURE_DIR"
fi
BASE="http://localhost:$PORT"
REPLAY_MODE="off"
case "$MODE" in
  replay) REPLAY_MODE="replay" ;;
  record) REPLAY_MODE="record" ;;
  live)   REPLAY_MODE="off" ;;
  *) echo "非法 mode: $MODE"; exit 1 ;;
esac

echo "=== E2E: mode=$MODE chapters=$CHAPTERS port=$PORT fixtures=$FIXTURE_DIR ==="
# 备份上一轮 record 的 DB（防止 replay 覆盖 record 数据）
if [ -f "$E2E_DIR/novel.db" ]; then cp "$E2E_DIR/novel.db" "$E2E_DIR/novel-record.db"; fi
rm -rf "$E2E_DIR"
mkdir -p "$E2E_DIR"

mvn -q spring-boot:run \
  -Dspring-boot.run.jvmArguments="-Dnovel.events.file=$E2E_DIR/events.jsonl" \
  -Dspring-boot.run.arguments="--server.port=$PORT --llm.replay.mode=$REPLAY_MODE --llm.replay.dir=$FIXTURE_DIR --persistence.embedding=stub --persistence.sqlite.db-path=$E2E_DIR/novel.db --persistence.vector-store.index-path=$E2E_DIR/vectors.idx" \
  > "$E2E_DIR/server.log" 2>&1 &
SERVER_PID=$!
trap 'kill $SERVER_PID 2>/dev/null || true; wait $SERVER_PID 2>/dev/null || true' EXIT

echo "▶ 等待服务就绪..."
for i in $(seq 1 90); do
  if curl --noproxy '*' -sf "$BASE/api/projects" > /dev/null 2>&1; then break; fi
  if ! kill -0 $SERVER_PID 2>/dev/null; then
    echo "❌ 服务启动失败，见 $E2E_DIR/server.log"; tail -20 "$E2E_DIR/server.log"; exit 1
  fi
  sleep 1
done
curl --noproxy '*' -sf "$BASE/api/projects" > /dev/null || { echo "❌ 服务 90s 未就绪"; exit 1; }
echo "✅ 服务已就绪"

echo "▶ 创建项目..."
PROJECT_ID=$(curl --noproxy '*' -sf -X POST "$BASE/api/projects" \
  -H "Content-Type: application/json" \
  -d "{\"title\":\"E2E基准小说-$PRESET\",\"concept\":\"一个雨夜的旧书店里，落魄账房学徒捡到一本会自己续写的账册，凡记入其中的欠债都会以离奇方式清偿\",\"tags\":\"$PRESET_TAGS\",\"mode\":\"SOLO\",\"preset\":\"$PRESET\"}" \
  | python3 -c "import json,sys; print(json.load(sys.stdin)['id'])")
echo "✅ 项目: $PROJECT_ID"

# 按章节数设置 targetChapters（避免 master outline 规划 400 章超长超时）
echo "▶ 设置 targetChapters=$CHAPTERS ..."
curl --noproxy '*' -sf -X PUT "$BASE/api/projects/$PROJECT_ID/preferences" \
  -H "Content-Type: application/json" \
  -d "{\"targetChapters\":$CHAPTERS,\"layeredOutline\":true}" > /dev/null 2>&1 || true

echo "▶ 构思阶段（世界观/人物/大纲）..."
CONCEIVE_JSON=$(curl --noproxy '*' -sf -X POST "$BASE/api/projects/$PROJECT_ID/run")
CONCEIVE_TASK=$(echo "$CONCEIVE_JSON" | python3 -c "import json,sys; d=json.load(sys.stdin); print(d.get('taskId') or '')")
if [ "$MODE" = "replay" ]; then
  CONCEIVE_LIMIT=180
else
  CONCEIVE_LIMIT=3600
fi
if [ -n "$CONCEIVE_TASK" ]; then
  CT_STATE=""
  for i in $(seq 1 "$CONCEIVE_LIMIT"); do
    CT_STATE=$(curl --noproxy '*' -sf "$BASE/api/projects/$PROJECT_ID/writing-tasks/$CONCEIVE_TASK" | python3 -c "import json,sys; print(json.load(sys.stdin).get('status',''))")
    case "$CT_STATE" in
      COMPLETED) break ;;
      FAILED|CANCELLED)
        echo "❌ 构思任务终态: $CT_STATE"; exit 1 ;;
    esac
    if [ $(( i % 30 )) -eq 0 ]; then
      echo "  构思仍在进行（${i}s）..."
    fi
    sleep 1
  done
  if [ "$CT_STATE" != "COMPLETED" ]; then
    echo "❌ 构思超时（${CONCEIVE_LIMIT}s），终态=$CT_STATE"; exit 1
  fi
fi
STATE=$(curl --noproxy '*' -sf "$BASE/api/projects/$PROJECT_ID/status" | python3 -c "import json,sys; print(json.load(sys.stdin)['state'])")
if [ "$STATE" = "PLANNING" ]; then
  curl --noproxy '*' -sf -X POST "$BASE/api/projects/$PROJECT_ID/run" > /dev/null
  STATE=$(curl --noproxy '*' -sf "$BASE/api/projects/$PROJECT_ID/status" | python3 -c "import json,sys; print(json.load(sys.stdin)['state'])")
fi
if [ "$STATE" = "CONCEIVING" ]; then
  echo "❌ 构思后仍停留在 CONCEIVING"; exit 1
fi
echo "✅ 构思完成，状态: $STATE"

echo "▶ 写作 $CHAPTERS 章..."
WRITE_RESULT=$(curl --noproxy '*' -sf -X POST "$BASE/api/projects/$PROJECT_ID/write-all?maxChapters=$CHAPTERS")
echo "$WRITE_RESULT"
TASK_ID=$(echo "$WRITE_RESULT" | python3 -c "import json,sys; print(json.load(sys.stdin)['taskId'])")

# NCS-186: 质量屏障自动放行——e2e 是确定性验证通道，写作循环因人审屏障 PAUSED 时
# 经正式批准端点放行（幂等键/身份协议与 UI 相同）；批准被 409 拒绝（如未解决 HIGH）
# 则不强行绕过，交由验收判定失败并留诊断。
approve_pending() {
  python3 - "$BASE" "$PROJECT_ID" "$CHAPTERS" <<'PYEOF'
import json, sys, urllib.request, urllib.error, uuid
base, pid, chapters = sys.argv[1], sys.argv[2], int(sys.argv[3])
opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
def get(path):
    with opener.open(f"{base}{path}", timeout=30) as r:
        return json.load(r)
approved = 0
for idx in range(1, chapters + 1):
    try:
        ctx = get(f"/api/projects/{pid}/chapters/{idx}/review-context")
    except Exception:
        continue
    if not ctx.get("barrierIds") or not ctx.get("identity"):
        continue
    body = {
        "identity": ctx["identity"],
        "reviewSetHash": ctx.get("reviewSetHash"),
        "idempotencyKey": str(uuid.uuid4()),
        "reason": "e2e 自动放行（NCS-186 确定性验证通道）",
    }
    req = urllib.request.Request(
        f"{base}/api/projects/{pid}/chapters/{idx}/review-decisions",
        data=json.dumps(body).encode("utf-8"),
        headers={"Content-Type": "application/json"}, method="POST")
    try:
        with opener.open(req, timeout=60) as r:
            if r.status == 200:
                approved += 1
                print(f"  ↪ 已放行章节 {idx} 的评审屏障")
    except urllib.error.HTTPError as e:
        print(f"  ⚠ 章节 {idx} 放行被拒（HTTP {e.code}）：{e.read().decode('utf-8', 'ignore')[:200]}",
              file=sys.stderr)
print(approved)
PYEOF
}

# 异步写作——轮询任务直到终态
# replay（3 章）固定 10 分钟上限；record/live 为每章预留 10 分钟，覆盖完整质检流水线。
if [ "$MODE" = "replay" ]; then
  POLL_LIMIT=1800
else
  POLL_LIMIT=$(( CHAPTERS * 1800 ))
  [ "$POLL_LIMIT" -lt 600 ] && POLL_LIMIT=1800
fi
TASK_JSON=""
APPROVAL_ROUNDS=0
for i in $(seq 1 "$POLL_LIMIT"); do
  TASK_JSON=$(curl --noproxy '*' -sf "$BASE/api/projects/$PROJECT_ID/writing-tasks/$TASK_ID")
  TASK_STATE=$(echo "$TASK_JSON" | python3 -c "import json,sys; print(json.load(sys.stdin)['status'])")
  case "$TASK_STATE" in
    COMPLETED|FAILED|CANCELLED) break ;;
    # p32-15: PAUSED 是合法流程停点（如一致性门禁标记人审后写作循环暂停）——
    # p32-15: PAUSED 是合法流程停点（如一致性门禁标记人审后写作循环暂停）——
    # NCS-186: 先经正式批准端点自动放行再继续轮询（同一定动线保证 record/replay 确定
    # 性）；无可放行且未写满时回退重发 write-all（覆盖批准后任务未复活的情况）；
    # 两者都无进展才按已写章节评估收尾。
    PAUSED)
      WRITTEN_NOW=$(echo "$TASK_JSON" | python3 -c "import json,sys; print(json.load(sys.stdin)['chaptersWritten'])")
      if [ "$APPROVAL_ROUNDS" -lt 10 ]; then
        APPROVAL_ROUNDS=$((APPROVAL_ROUNDS + 1))
        APPROVED=$(approve_pending 2>/dev/null | tail -1)
        if [ "${APPROVED:-0}" -gt 0 ] 2>/dev/null; then
          sleep 5
          continue
        fi
        if [ "$WRITTEN_NOW" -lt "$CHAPTERS" ]; then
          echo "  ↪ 无可放行屏障但任务 PAUSED（${WRITTEN_NOW}/${CHAPTERS}），重发 write-all 续写"
          WRITE_RESULT=$(curl --noproxy '*' -sf -X POST "$BASE/api/projects/$PROJECT_ID/write-all?maxChapters=$((CHAPTERS - WRITTEN_NOW))")
          TASK_ID=$(echo "$WRITE_RESULT" | python3 -c "import json,sys; print(json.load(sys.stdin)['taskId'])")
          sleep 5
          continue
        fi
      fi
      break ;;
  esac
  if [ $(( i % 30 )) -eq 0 ]; then
    TASK_CHAPTERS=$(echo "$TASK_JSON" | python3 -c "import json,sys; print(json.load(sys.stdin)['chaptersWritten'])")
    echo "  进度: 已写 $TASK_CHAPTERS/$CHAPTERS 章（轮询 ${i}s）"
  fi
  sleep 2
done
echo "✅ 写作任务终态: $TASK_STATE"
TASK_JSON=$(curl --noproxy '*' -sf "$BASE/api/projects/$PROJECT_ID/writing-tasks/$TASK_ID")

EVAL_JSON="null"
if [ "$EVALUATE" = "true" ]; then
  echo "▶ 质量评估..."
  EVAL_JSON=$(curl --noproxy '*' -sf -X POST "$BASE/api/projects/$PROJECT_ID/evaluate" || echo "null")
fi

echo "▶ 生成报告..."
curl --noproxy '*' -sf "$BASE/api/projects/$PROJECT_ID/chapters?size=1000" > "$E2E_DIR/chapters.json"
curl --noproxy '*' -sf "$BASE/api/projects/$PROJECT_ID/status" > "$E2E_DIR/status.json"

set +e
python3 - "$E2E_DIR" "$MODE" <<PYEOF
import json, sys, datetime, urllib.request
sys.path.insert(0, "scripts")
from e2e_acceptance import assess, quality_counts

e2e_dir, mode = sys.argv[1], sys.argv[2]
_payload = json.load(open(f"{e2e_dir}/chapters.json", encoding="utf-8"))
# p21-04: /chapters 为分页元数据 {"chapters":[...], "total":N}；兼容旧数组形状
chapters = _payload["chapters"] if isinstance(_payload, dict) else _payload
status = json.load(open(f"{e2e_dir}/status.json", encoding="utf-8"))
write_result = json.loads('''$WRITE_RESULT''')
task_json = json.loads('''$TASK_JSON''')
evaluation = json.loads('''$EVAL_JSON''')

llm_errors, chapter_failures, llm_calls = [], [], []
try:
    with open(f"{e2e_dir}/events.jsonl", encoding="utf-8") as f:
        for line in f:
            try:
                e = json.loads(line)
            except json.JSONDecodeError:
                continue
            if e["event"] == "LLM_ERROR": llm_errors.append(e)
            elif e["event"] == "CHAPTER_FAILED": chapter_failures.append(e)
            elif e["event"] == "LLM_CALL": llm_calls.append(e)
except FileNotFoundError:
    pass

quality = dict(activeReviewBarriers=None, pendingReviewChapters=None, unresolvedHighIssues=None)
try:
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    def get_json(path):
        with opener.open("$BASE/api/projects/$PROJECT_ID/" + path, timeout=30) as response:
            return json.load(response)
    chapter_qualities = [get_json("chapters/" + str(c["chapterIndex"]) + "/quality") for c in chapters]
    quality = quality_counts(get_json("workspace"), chapter_qualities)
    # NCS-104: 聚合风格量化指标（服务端确定性计算，随章节质量报告返回）
    def _avg(vals):
        vals = [v for v in vals if v is not None]
        return round(sum(vals) / len(vals), 4) if vals else 0
    _metrics = [q.get("styleMetrics") or {} for q in chapter_qualities]
    quality["styleMetrics"] = {
        "avgSentenceLength": _avg([m.get("avgSentenceLength") for m in _metrics]),
        "sentenceLengthStdDev": _avg([m.get("sentenceLengthStdDev") for m in _metrics]),
        "dialogueDensity": _avg([m.get("dialogueDensity") for m in _metrics]),
        "clicheHits": sum((m.get("clicheHits") or 0) for m in _metrics),
        "repeatedNgramRate": _avg([m.get("repeatedNgramRate") for m in _metrics]),
    }
except (OSError, KeyError, TypeError, ValueError) as exc:
    print("质量证据读取失败: " + str(exc), file=sys.stderr)

word_counts = [c.get("wordCount", 0) for c in chapters]
report = {
    **quality,
    "requestedChapters": $CHAPTERS,
    "acceptanceMode": "$ACCEPTANCE",
    "generatedAt": datetime.datetime.now().isoformat(),
    "mode": mode,
    "projectId": status["projectId"],
    "finalState": status["state"],
    "chaptersWritten": task_json.get("chaptersWritten", write_result.get("chaptersWritten", 0)),
    "taskStatus": task_json.get("status", "?"),
    "taskError": task_json.get("errorMessage", ""),
    "totalChapters": len(chapters),
    "totalWords": status.get("totalWords", 0),
    "wordCount": {
        "min": min(word_counts) if word_counts else 0,
        "max": max(word_counts) if word_counts else 0,
        "avg": sum(word_counts) // len(word_counts) if word_counts else 0,
    },
    "llmCalls": len(llm_calls),
    "llmErrors": len(llm_errors),
    "chapterFailures": len(chapter_failures),
    "evaluation": evaluation,
}
ok, reasons = assess(report, "$ACCEPTANCE")
report["acceptancePassed"] = ok
report["acceptanceReasons"] = reasons
with open("e2e-report.json", "w", encoding="utf-8") as f:
    json.dump(report, f, ensure_ascii=False, indent=2)
print(json.dumps(report, ensure_ascii=False, indent=2))

sys.exit(0 if ok else 1)
PYEOF
E2E_EXIT=$?
set -e

if [ $E2E_EXIT -eq 0 ]; then
  if [ "$ACCEPTANCE" = "smoke" ]; then
    echo "✅ E2E 管道冒烟通过（不代表完整质量验收）— 报告: e2e-report.json"
  else
    echo "✅ E2E 完整流程及自动质量门禁通过 — 报告: e2e-report.json"
  fi
  echo "   下一步: python3 scripts/compare-baseline.py 对比基线"
else
  echo "❌ E2E 失败 — 运行 ./scripts/diagnose.sh $E2E_DIR/events.jsonl 查看诊断"
fi
exit $E2E_EXIT
