#!/usr/bin/env bash
# Real-browser collaboration acceptance against an isolated, zero-cost Scenario server.
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT="$(pwd)"
DIR="$ROOT/data/e2e/collaboration"
PORT="${COLLABORATION_PORT:-18097}"
BASE="http://127.0.0.1:$PORT"
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
create() {
 curl --noproxy '*' -sf -X POST "$BASE/api/projects" -H 'Content-Type: application/json' -d "{\"title\":\"协作验收-$1\",\"concept\":\"古代悬疑查案\",\"tags\":\"悬疑,古代\",\"mode\":\"$1\",\"preset\":\"mystery\",\"targetChapters\":${2:-3}}" | python3 -c 'import json,sys;print(json.load(sys.stdin)["id"])'
}
AUTO=$(create SOLO)
REVIEW=$(create INTERACTIVE)
TAKEOVER=$(create SOLO 50)
CADENCE=$(create SOLO)
VOLUME=$(create SOLO)
ego-browser nodejs > "$RUN_DIR/browser.out" 2>&1 <<EGOEOF
const fs = await import('node:fs')
const base = '$BASE'
const task = await useOrCreateTaskSpace('writingagent acceptance tests')
fs.writeFileSync('$RUN_DIR/task-space.txt', String(task.id))
await openOrReuseTab(base, {wait:true, timeout:30})
const results=[]
function assert(value, text) { if(!value) throw new Error(text) }
async function read(path) {
 return await js('(()=>{const x=new XMLHttpRequest();x.open("GET",'+JSON.stringify(path)+',false);x.send();if(x.status!==200)throw new Error(x.responseText);return JSON.parse(x.responseText)})()')
}
async function until(fn, label, rounds=90) {
 for(let i=0;i<rounds;i++){if(await fn())return;await wait(1)}throw new Error('Timeout: '+label)
}
async function fill(selector,value) {
 await until(async()=>await js('!!document.querySelector('+JSON.stringify(selector)+')'),'field '+selector,15)
 await js('(()=>{const el=document.querySelector('+JSON.stringify(selector)+');const proto=el.tagName==="TEXTAREA"?HTMLTextAreaElement.prototype:HTMLInputElement.prototype;Object.getOwnPropertyDescriptor(proto,"value").set.call(el,'+JSON.stringify(value)+');el.dispatchEvent(new Event("input",{bubbles:true}));el.dispatchEvent(new Event("change",{bubbles:true}))})()')
}
async function button(selector) {
 assert(await js('(()=>{const b=document.querySelector('+JSON.stringify(selector)+');if(!b||b.disabled)return false;b.click();return true})()'),'Button unavailable: '+selector)
}
async function textButton(text) {
 assert(await js('(()=>{const b=[...document.querySelectorAll("button")].find(x=>x.innerText.trim()==='+JSON.stringify(text)+');if(!b||b.disabled)return false;b.click();return true})()'),'Button unavailable: '+text)
}
async function tab(text) {
 assert(await js('(()=>{const t=[...document.querySelectorAll("[role=tab]")].find(x=>x.innerText.includes('+JSON.stringify(text)+'));if(!t)return false;t.click();return true})()'),'Missing tab '+text);await wait(1)
}
async function policy(projectId,value,label) {
 await click('[data-collaboration-bar] .ant-select-selector')
 await until(async()=>await js('!![...document.querySelectorAll(".ant-select-item-option")].find(x=>x.innerText.trim()==='+JSON.stringify(label)+')'),'review options',10)
 await js('(()=>{[...document.querySelectorAll(".ant-select-item-option")].find(x=>x.innerText.trim()==='+JSON.stringify(label)+').click()})()')
 await until(async()=>(await read('/api/projects/'+projectId+'/collaboration')).reviewPolicy===value,'review policy '+value)
 await until(async()=>await js('!document.querySelector("[data-collaboration-bar] .ant-select").classList.contains("ant-select-disabled")'),'policy saved')
}
async function continueRun() {
 await until(async()=>await js('!!document.querySelector("[data-collaboration-start]")&&!document.querySelector("[data-collaboration-start]").disabled'),'continue control')
 await button('[data-collaboration-start]')
}
async function approve(projectId) {
 await tab('章节管理')
 const pending=await read('/api/projects/'+projectId+'/checkpoints')
 assert(pending.length===1,'Expected one explicit review target')
 await until(async()=>await js('[...document.querySelectorAll("button")].some(x=>x.innerText.trim()==="✅ 批准")'),'approve control',15)
 await textButton('✅ 批准')
 await until(async()=>(await read('/api/projects/'+projectId+'/checkpoints')).length===0,'explicit approval')
}
async function chapterReview(projectId) {
 for(let i=0;i<4;i++) {
  await until(async()=>{const s=await read('/api/projects/'+projectId+'/collaboration');return !s.busy&&s.pendingReviews>0},'review boundary')
  const pending=await read('/api/projects/'+projectId+'/checkpoints')
  if(pending[0].type==='AFTER_CHAPTER')return pending[0]
  assert(['AFTER_INITIAL_SETUP','AFTER_VOLUME_OUTLINE'].includes(pending[0].type),'Unexpected framework review')
  await approve(projectId);await continueRun()
 }
 throw new Error('No chapter review after framework')
}
async function run(name,fn){try{await fn();results.push({name,passed:true});cliLog('PASS '+name)}catch(e){results.push({name,passed:false,error:String(e)});cliLog('FAIL '+name+' '+e)}
 fs.writeFileSync('$RUN_DIR/'+name+'.txt',await snapshotText())
}

await run('one-start-framework-to-final',async()=>{
 await gotoAndWait(base+'/#/project/$AUTO',{timeout:30,settle:1});await wait(2)
 assert(await js('!!document.querySelector("[data-collaboration-bar]")'),'Missing global collaboration bar')
 await button('[data-collaboration-start]')
 await tab('世界观与人物')
 assert(await js('!!document.querySelector("[data-collaboration-bar]")'),'Bar disappeared on content tab')
 await gotoAndWait(base+'/#/project/$AUTO',{timeout:30,settle:1})
 await until(async()=>{const s=await read('/api/projects/$AUTO/collaboration');return !s.busy&&s.state==='FINAL'},'automatic FINAL')
 const s=await read('/api/projects/$AUTO/collaboration'), chapters=await read('/api/projects/$AUTO/chapters')
 assert(s.currentChapter===3&&chapters.chapters.length===3,'Not exactly 3 chapters')
 assert(s.task.status==='COMPLETED','Task is not complete')
 assert(chapters.chapters.every(c=>c.status==='DONE'),'Unfinished chapter persisted')
 await until(async()=>await js('document.querySelector("[data-collaboration-bar]").innerText.includes("全书已完成")'),'UI final status')
})

await run('review-edit-continue-same-workspace',async()=>{
 await gotoAndWait(base+'/#/project/$REVIEW',{timeout:30,settle:1});await wait(2)
 await button('[data-collaboration-start]')
 await until(async()=>{const s=await read('/api/projects/$REVIEW/collaboration');return !s.busy&&s.pendingReviews>0},'framework review')
 await tab('章节管理')
 await until(async()=>await js('[...document.querySelectorAll("button")].some(x=>x.innerText.includes("批准"))'),'review card')
 assert(await js('document.querySelector("[data-collaboration-start]").disabled'),'Pending review must block continue')
 // Edit persisted world content using the existing author editor.
 await tab('世界观与人物')
 const world=(await read('/api/projects/$REVIEW/worldbuilding'))[0]
 const scope='[data-world-entry-id="'+world.id+'"]'
 const authorText=world.content+'【作者精修：保留人物调查时的迟疑。】'
 await fill(scope+' textarea',authorText)
 assert(await js('(()=>{const scope=document.querySelector('+JSON.stringify(scope)+');const b=[...scope.querySelectorAll("button")].find(x=>x.innerText.includes("保存设定"));if(!b)return false;b.click();return true})()'),'World save missing')
 await until(async()=>(await read('/api/projects/$REVIEW/worldbuilding')).find(x=>x.id===world.id).content===authorText,'author edit persisted')
 await tab('章节管理')
 // Keep the chapter draft open across an independent parent refresh (review policy update).
 await textButton('新增章节')
 await fill('input[aria-label="新增章节标题"]','作者草稿，不可丢失')
 await fill('textarea[aria-label="新增章节正文"]','正在精修的人物细节')
 await js('(()=>{const x=new XMLHttpRequest();x.open("PUT","/api/projects/$REVIEW/collaboration/review-policy",false);x.setRequestHeader("Content-Type","application/json");x.send(JSON.stringify({policy:"MANUAL"}));if(x.status!==200)throw new Error(x.responseText)})()')
 const cp=(await read('/api/projects/$REVIEW/checkpoints'))[0]
 await js('(()=>{const x=new XMLHttpRequest();x.open("POST",'+JSON.stringify('/api/projects/$REVIEW/checkpoints/'+cp.id+'/approve')+',false);x.send();if(x.status!==200)throw new Error(x.responseText)})()')
 await wait(3)
 assert(await js('document.querySelector("input[aria-label=新增章节标题]")?.value==="作者草稿，不可丢失"'),'Author draft lost')
 await js('document.querySelector(".ant-modal-wrap .ant-modal-close").click()')
 // Approve the explicit target, then set review cadence to proactive takeover.
 assert((await read('/api/projects/$REVIEW/checkpoints')).length===0,'Explicit approval not applied')
 await until(async()=>{const s=await read('/api/projects/$REVIEW/collaboration');return !s.busy&&s.pendingReviews===0},'approval applied')
 await until(async()=>{const s=await read('/api/projects/$REVIEW/collaboration');return s.reviewPolicy==='MANUAL'},'review policy')
 await until(async()=>await js('!document.querySelector("[data-collaboration-start]").disabled'),'continue enabled')
 await button('[data-collaboration-start]')
 await until(async()=>{const s=await read('/api/projects/$REVIEW/collaboration');return !s.busy&&s.state==='FINAL'},'review then full continuation')
 assert((await read('/api/projects/$REVIEW/chapters')).chapters.length===3,'Continuation lost chapters')
 assert((await read('/api/projects/$REVIEW/worldbuilding')).find(x=>x.id===world.id).content===authorText,'Agent overwrote author world edit')
})

await run('takeover-during-writing-and-edit',async()=>{
 await gotoAndWait(base+'/#/project/$TAKEOVER',{timeout:30,settle:1});await wait(2)
 await button('[data-collaboration-start]')
 await until(async()=>{const s=await read('/api/projects/$TAKEOVER/collaboration');return s.busy && s.currentChapter>=1},'active writing')
 await until(async()=>await js('!!document.querySelector("[data-collaboration-stop]")'),'stop control')
 await button('[data-collaboration-stop]')
 await until(async()=>!(await read('/api/projects/$TAKEOVER/collaboration')).busy,'safe takeover')
 const paused=await read('/api/projects/$TAKEOVER/collaboration')
 assert(paused.task.status==='PAUSED' && paused.task.stopRequested,'Takeover must persist as paused')
 assert(paused.pendingReviews===0 && paused.state!=='PAUSED_AT_CHECKPOINT','Takeover changed novel phase')
 await gotoAndWait(base+'/#/project/$TAKEOVER',{timeout:30,settle:1});await wait(2)
 const stable=await read('/api/projects/$TAKEOVER/collaboration')
 assert(stable.currentChapter===paused.currentChapter && !stable.busy,'Writer continued after takeover')
 await tab('世界观与人物')
 const world=(await read('/api/projects/$TAKEOVER/worldbuilding'))[0]
 const scope='[data-world-entry-id="'+world.id+'"]'
 await fill(scope+' textarea','作者接手后的设定，必须进入下一次上下文。')
 await js('(()=>{const s=document.querySelector('+JSON.stringify(scope)+');[...s.querySelectorAll("button")].find(x=>x.innerText.includes("保存设定")).click()})()')
 await until(async()=>(await read('/api/projects/$TAKEOVER/worldbuilding')).find(x=>x.id===world.id).content==='作者接手后的设定，必须进入下一次上下文。','takeover edit')
 assert((await read('/api/projects/$TAKEOVER/collaboration')).canContinue,'Cannot continue after editing')
 const before=(await read('/api/projects/$TAKEOVER/chapters/1')).content
 await continueRun()
 await until(async()=>(await read('/api/projects/$TAKEOVER/collaboration')).currentChapter>paused.currentChapter,'continue after takeover')
 await until(async()=>await js('!!document.querySelector("[data-collaboration-stop]")'),'second takeover control')
 await button('[data-collaboration-stop]')
 await until(async()=>!(await read('/api/projects/$TAKEOVER/collaboration')).busy,'second safe takeover')
 assert((await read('/api/projects/$TAKEOVER/chapters/1')).content===before,'Continuation rewrote completed chapter')
 assert((await read('/api/projects/$TAKEOVER/worldbuilding')).find(x=>x.id===world.id).content==='作者接手后的设定，必须进入下一次上下文。','Continuation lost author edit')
})

await run('chapter-cadence-and-author-instruction',async()=>{
 await gotoAndWait(base+'/#/project/$CADENCE',{timeout:30,settle:1});await wait(2)
 await policy('$CADENCE','CHAPTER','每章审阅')
 await fill('textarea[aria-label="创作要求"]','保留作者精修的人物动机，调查情节以证据推进。')
 await continueRun()
 await until(async()=>{const s=await read('/api/projects/$CADENCE/collaboration');return !s.busy&&s.pendingReviews>0},'initial review')
 await gotoAndWait(base+'/#/project/$CADENCE',{timeout:30,settle:1})
 await until(async()=>await js('document.querySelector("textarea[aria-label=创作要求]")?.value==="保留作者精修的人物动机，调查情节以证据推进。"'),'instruction restored after refresh')
 const pending=await read('/api/projects/$CADENCE/checkpoints')
 await policy('$CADENCE','VOLUME','卷末审阅')
 assert((await read('/api/projects/$CADENCE/checkpoints'))[0].id===pending[0].id,'Cadence silently approved pending review')
 assert(await js('document.querySelector("[data-collaboration-start]").disabled'),'Pending review allows start')
 await policy('$CADENCE','CHAPTER','每章审阅')
 await chapterReview('$CADENCE')
 const first=await read('/api/projects/$CADENCE/collaboration')
 assert(first.currentChapter===1&&first.task.status==='PAUSED','Every-chapter cadence did not stop at chapter 1')
 assert(first.task.instruction==='保留作者精修的人物动机，调查情节以证据推进。','Continue lost persisted instruction')
 await approve('$CADENCE')
 await fill('textarea[aria-label="创作要求"]','')
 await continueRun()
 await chapterReview('$CADENCE')
 const second=await read('/api/projects/$CADENCE/collaboration')
 assert(second.currentChapter===2&&second.task.instruction==='','Explicit blank did not clear instruction')
 await gotoAndWait(base+'/#/project/$CADENCE',{timeout:30,settle:1});await wait(2)
 assert(await js('document.querySelector("textarea[aria-label=创作要求]").value===""'),'Cleared instruction returned after refresh')
 await approve('$CADENCE')
 await policy('$CADENCE','MANUAL','仅主动接手')
 await continueRun()
 await until(async()=>{const s=await read('/api/projects/$CADENCE/collaboration');return !s.busy&&s.state==='FINAL'},'chapter cadence to final')
})

await run('volume-cadence-review-before-final',async()=>{
 await gotoAndWait(base+'/#/project/$VOLUME',{timeout:30,settle:1});await wait(2)
 await policy('$VOLUME','VOLUME','卷末审阅')
 await continueRun()
 await chapterReview('$VOLUME')
 const boundary=await read('/api/projects/$VOLUME/collaboration')
 assert(boundary.currentChapter===3&&boundary.state==='PAUSED_AT_CHECKPOINT','Volume cadence did not wait until volume end')
 assert(boundary.task.status==='PAUSED'&&!boundary.canContinue,'Review boundary was called complete')
 assert((await read('/api/projects/$VOLUME/chapters')).chapters.length===3,'Volume review missing body')
 await approve('$VOLUME');await continueRun()
 await until(async()=>{const s=await read('/api/projects/$VOLUME/collaboration');return !s.busy&&s.state==='FINAL'&&s.task.status==='COMPLETED'},'volume approval and final audit')
})

await run('mobile-global-controls',async()=>{
 await cdp('Emulation.setDeviceMetricsOverride',{width:375,height:812,deviceScaleFactor:1,mobile:true})
 await gotoAndWait(base+'/#/project/$AUTO',{timeout:30,settle:1});await wait(2)
 assert(await js('document.documentElement.scrollWidth<=innerWidth+1'),'Page overflows mobile viewport')
 assert(await js('!!document.querySelector("[data-collaboration-bar]")'),'Missing mobile bar')
 await cdp('Emulation.clearDeviceMetricsOverride')
})
fs.writeFileSync('$RUN_DIR/results.json',JSON.stringify({passed:results.every(r=>r.passed),results},null,2))
cliLog(JSON.stringify(results))
EGOEOF
cat "$RUN_DIR/browser.out"
python3 - "$RUN_DIR/results.json" <<'PY'
import json,sys
x=json.load(open(sys.argv[1]));print('COLLABORATION E2E', 'PASS' if x['passed'] else 'FAIL');sys.exit(0 if x['passed'] else 1)
PY
cp "$RUN_DIR/results.json" "$DIR/latest-results.json"
