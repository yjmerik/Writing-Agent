#!/usr/bin/env bash
# p27-08: A real chapter-index change must not unmount unsaved author drafts.
set -euo pipefail
cd "$(dirname "$0")/.."
PORT="${DRAFT_E2E_PORT:-18094}"
BASE="http://127.0.0.1:$PORT"
DIR="$(pwd)/data/e2e/draft-preservation"
if lsof -tiTCP:"$PORT" -sTCP:LISTEN >/dev/null 2>&1; then
  echo "Port $PORT occupied; refusing to stop another service"; exit 2
fi
mkdir -p "$DIR"
RUN_DIR=$(mktemp -d "$DIR/run-XXXXXX")
APP_PID=""
cleanup() { if [ -n "$APP_PID" ]; then kill "$APP_PID" 2>/dev/null || true; wait "$APP_PID" 2>/dev/null || true; fi; }
trap cleanup EXIT INT TERM
mvn -q spring-boot:run -Dspring-boot.run.arguments="--server.port=$PORT --llm.scenario.enabled=true --llm.replay.mode=off --persistence.embedding=stub --persistence.sqlite.db-path=$RUN_DIR/novel.db --persistence.vector-store.index-path=$RUN_DIR/vectors.idx" > "$RUN_DIR/server.log" 2>&1 &
APP_PID=$!
ready=0
for _ in $(seq 1 90); do
  if curl --noproxy '*' -sf "$BASE/api/projects" >/dev/null; then ready=1; break; fi
  sleep 1
done
[ "$ready" = 1 ] || { echo "Service startup failed: $RUN_DIR/server.log"; exit 2; }
PROJECT=$(curl --noproxy '*' -sf -X POST "$BASE/api/projects" -H 'Content-Type: application/json' \
  -d '{"title":"后台推进草稿保护验收","concept":"古代悬疑查案","tags":"悬疑,古代","mode":"INTERACTIVE","preset":"mystery","targetChapters":3}' \
  | python3 -c 'import json,sys;print(json.load(sys.stdin)["id"])')
if ! ego-browser nodejs > "$RUN_DIR/browser.out" 2>&1 <<EGOEOF
const fs = await import('node:fs')
const task = await useOrCreateTaskSpace('writingagent acceptance tests')
fs.writeFileSync('$RUN_DIR/task-space.txt',String(task.id))
await openOrReuseTab('$BASE',{wait:true,timeout:30})
await gotoAndWait('$BASE/#/project/$PROJECT',{timeout:30,settle:1})
const api='/api/projects/$PROJECT'
function assert(value,message){if(!value)throw new Error(message)}
async function until(fn,label){for(let i=0;i<90;i++){if(await fn())return;await wait(1)}throw new Error('Timeout: '+label)}
async function request(method,path,body=null){
 return await js('(()=>{const x=new XMLHttpRequest();x.open('+JSON.stringify(method)+','+JSON.stringify(path)+',false);x.setRequestHeader("Content-Type","application/json");x.send('+JSON.stringify(body===null?null:JSON.stringify(body))+');if(x.status!==200)throw new Error("HTTP "+x.status+" "+x.responseText);return JSON.parse(x.responseText)})()')
}
const result={passed:false,scenario:'draft-survives-background-chapter-advance'}
try {
 await until(async()=>await js('!!document.querySelector("[data-collaboration-start]")&&!document.querySelector("[data-collaboration-start]").disabled'),'start available')
 fs.writeFileSync('$RUN_DIR/before.txt',await snapshotText())
 await click('[data-collaboration-start]')
 await until(async()=>{const s=await request('GET',api+'/collaboration');return !s.busy&&s.pendingReviews>0},'framework review')
 await until(async()=>await js('[...document.querySelectorAll("button")].some(b=>b.innerText.trim()==="新增章节")'),'author editor')
 await js('(()=>{[...document.querySelectorAll("button")].find(b=>b.innerText.trim()==="新增章节").click()})()')
 await fillInput('input[aria-label="新增章节标题"]','作者草稿，不可丢失')
 await fillInput('textarea[aria-label="新增章节正文"]','正在精修的人物细节')
 fs.writeFileSync('$RUN_DIR/draft-open.txt',await snapshotText())
 // Simulate another client approving/continuing while this author keeps an unsaved modal open.
 await request('PUT',api+'/collaboration/review-policy',{policy:'MANUAL'})
 const pending=await request('GET',api+'/checkpoints')
 assert(pending.length===1,'Expected one framework review')
 await request('POST',api+'/checkpoints/'+pending[0].id+'/approve')
 await request('POST',api+'/collaboration/start',{})
 await until(async()=>(await request('GET',api+'/collaboration')).currentChapter>=1,'background chapter advance')
 await wait(3) // Allow the collaboration poll and parent refresh to observe the new index.
 assert(await js('document.querySelector("input[aria-label=新增章节标题]")?.value==="作者草稿，不可丢失"'),'Author draft lost after chapter index changed')
 assert(await js('document.querySelector("textarea[aria-label=新增章节正文]")?.value==="正在精修的人物细节"'),'Author body draft lost')
 await until(async()=>{const s=await request('GET',api+'/collaboration');return !s.busy&&s.state==='FINAL'},'isolated background task complete')
 result.currentChapter=(await request('GET',api+'/collaboration')).currentChapter
 result.passed=true
} catch(error){result.error=String(error)}
fs.writeFileSync('$RUN_DIR/after.txt',await snapshotText())
fs.writeFileSync('$RUN_DIR/results.json',JSON.stringify(result,null,2))
cliLog(JSON.stringify(result))
EGOEOF
then cat "$RUN_DIR/browser.out"; exit 2; fi
cat "$RUN_DIR/browser.out"
cp "$RUN_DIR/results.json" "$DIR/latest-results.json"
python3 - "$RUN_DIR/results.json" <<'PY'
import json,sys
result=json.load(open(sys.argv[1]));print('DRAFT PRESERVATION', 'PASS' if result['passed'] else 'FAIL')
sys.exit(0 if result['passed'] else 1)
PY
