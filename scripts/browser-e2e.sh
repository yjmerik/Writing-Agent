#!/usr/bin/env bash
# p23-04: 浏览器 E2E（ego-browser 驱动，替代 Playwright——按用户指示）。
# 用法: ./scripts/browser-e2e.sh [--port 8095]
# 覆盖：终态门禁（FINAL/NEEDS_REVISION）、章节懒加载、响应式 375px、键盘可激活与
#       可访问名、SSE 连接状态、人工复核（harsh 档）。
# 后端：打包 Spring 应用 + 确定性场景网关（零 LLM 成本）；结果落 data/e2e/browser/*.json。
set -u
cd "$(dirname "$0")/.."

PORT=8095
while [ $# -gt 0 ]; do
  case "$1" in
    --port) PORT="$2"; shift 2 ;;
    *) echo "unknown arg: $1"; exit 1 ;;
  esac
done

# ego-browser 进程 cwd 与本脚本不同——所有产物路径必须绝对化
ROOT="$(pwd)"
DIR="$ROOT/data/e2e/browser"
BASE="http://127.0.0.1:$PORT"
rm -rf "$DIR"
mkdir -p "$DIR"
PASS=()
FAIL=()

record() {
  if [ "$2" = "0" ]; then PASS+=("$1"); echo "PASS  $1"; else FAIL+=("$1"); echo "FAIL  $1"; fi
}

kill_port() { lsof -ti ":$1" 2>/dev/null | xargs kill -9 2>/dev/null || true; sleep 1; }

start_app() {
  kill_port "$PORT"
  mvn -q spring-boot:run \
    -Dspring-boot.run.arguments="--server.port=$PORT --llm.scenario.enabled=true $1 --llm.replay.mode=off --persistence.embedding=stub --persistence.sqlite.db-path=$DIR/novel.db --persistence.vector-store.index-path=$DIR/vectors.idx" \
    > "$DIR/server.log" 2>&1 &
  for i in $(seq 1 90); do
    curl --noproxy '*' -sf "$BASE/api/projects" > /dev/null 2>&1 && return 0
    sleep 1
  done
  return 1
}

seed_project() {
  local PID=$(curl --noproxy '*' -sf -X POST "$BASE/api/projects" -H "Content-Type: application/json" \
    -d "{\"title\":\"$1\",\"concept\":\"browser e2e scenario book\",\"tags\":\"悬疑,古代\",\"mode\":\"SOLO\",\"preset\":\"mystery\"}" \
    | python3 -c "import json,sys; print(json.load(sys.stdin)['id'])")
  curl --noproxy '*' -sf -X PUT "$BASE/api/projects/$PID/preferences" -H "Content-Type: application/json" \
    -d "{\"targetChapters\":$2}" > /dev/null
  local i
  for (( i=0; i<$3; i++ )); do
    curl --noproxy '*' -sf -X POST "$BASE/api/projects/$PID/foreshadowings" -H "Content-Type: application/json" \
      -d "{\"content\":\"必须回收伏笔$i\",\"mustResolve\":true}" > /dev/null
  done
  local CT=$(curl --noproxy '*' -sf -X POST "$BASE/api/projects/$PID/run" \
    | python3 -c "import json,sys; print(json.load(sys.stdin).get('taskId',''))")
  local ST=""
  for i in $(seq 1 300); do
    ST=$(curl --noproxy '*' -sf "$BASE/api/projects/$PID/writing-tasks/$CT" | python3 -c "import json,sys; print(json.load(sys.stdin).get('status',''))")
    case "$ST" in COMPLETED|FAILED|CANCELLED) break ;; esac
    sleep 1
  done
  curl --noproxy '*' -sf -X POST "$BASE/api/projects/$PID/run" > /dev/null 2>&1
  sleep 2
  echo "$PID"
}

echo "=== Browser E2E (ego-browser) ==="
start_app "" || { echo "FAIL app start"; exit 1; }
echo "OK app ready"

# 终态种子：大纲 3 章 + maxChapters=5 → 大纲耗尽触发成书门禁（FINAL / NEEDS_REVISION）
P_FINAL=$(seed_project "e2e-final" 3 0)
curl --noproxy '*' -sf -X POST "$BASE/api/projects/$P_FINAL/write-all?maxChapters=5" > /dev/null
sleep 6
P_REV=$(seed_project "e2e-revision" 3 4)
curl --noproxy '*' -sf -X POST "$BASE/api/projects/$P_REV/write-all?maxChapters=15" > /dev/null
sleep 6
P_LAZY=$(seed_project "e2e-lazy" 3 0)
curl --noproxy '*' -sf -X POST "$BASE/api/projects/$P_LAZY/write-all?maxChapters=2" > /dev/null
sleep 6
P_SSE=$(seed_project "e2e-sse" 3 0)
echo "OK seeds: final=$P_FINAL rev=$P_REV lazy=$P_LAZY sse=$P_SSE"

ego-browser nodejs <<EGOEOF
const fs = await import('node:fs')
const task = await useOrCreateTaskSpace('writingagent acceptance tests')
fs.writeFileSync('$DIR/task-space.txt', String(task.id))
cliLog('task space ' + task.id)
EGOEOF
TASK_ID=$(cat "$DIR/task-space.txt")
echo "task-space=$TASK_ID"

# ---------- 1. 列表页 375px：无横向溢出 + 删除按钮可访问名 + 行键盘语义存在 ----------
ego-browser nodejs > "$DIR/list-375.out" 2>&1 <<EGOEOF
const task = await useOrCreateTaskSpace('$TASK_ID')
await openOrReuseTab('$BASE/', { wait: true, timeout: 30 })
await cdp('Emulation.setDeviceMetricsOverride', { width: 375, height: 667, deviceScaleFactor: 2, mobile: true })
await wait(2)
await js(String.raw\`(() => {
  const create = [...document.querySelectorAll('button')].find(b => b.innerText.trim() === '新建项目')
  if (create) create.click()
  return true
})()\`)
await wait(1)
const m = await js(String.raw\`(() => ({
  overflow: document.documentElement.scrollWidth - window.innerWidth,
  delLabeled: !!document.querySelector('button[aria-label^="删除项目"]'),
  rows: document.querySelectorAll('[role="button"][aria-label^="打开项目"]').length,
  truthfulProgress: [...document.querySelectorAll('[role="button"][aria-label^="打开项目"]')]
    .every(row => /\\d+\\s*\\/\\s*\\d+\\s*章\\s*·\\s*\\d+%/.test(row.innerText)),
  truthfulAutoCopy: document.body.innerText.includes('仅主动接手 — Agent 持续创作，你随时介入'),
  forbiddenAutoCopy: document.body.innerText.includes('全自动生成，无需干预'),
}))()\`)
const fs = await import('node:fs')
fs.writeFileSync('$DIR/list-375.json', JSON.stringify(m))
cliLog('LIST375 ' + JSON.stringify(m))
await closeTab()
EGOEOF
python3 -c "
import json; d=json.load(open('$DIR/list-375.json'))
exit(0 if d['overflow'] <= 0 and d['delLabeled'] and d['rows'] > 0 and d['truthfulProgress'] and d['truthfulAutoCopy'] and not d['forbiddenAutoCopy'] else 1)"; record "list-375-overflow-a11y-progress-copy" $?

# ---------- 2. 行键盘可激活（桌面） ----------
ego-browser nodejs > "$DIR/keyboard.out" 2>&1 <<EGOEOF
const task = await useOrCreateTaskSpace('$TASK_ID')
await cdp('Emulation.clearDeviceMetricsOverride')
await openOrReuseTab('$BASE/', { wait: true, timeout: 30 })
await wait(2)
const before = await js(String.raw\`(() => {
  const row = document.querySelector('[role="button"][aria-label^="打开项目"]')
  if (!row) return { path: 'NO_ROW' }
  row.focus()
  return { path: location.hash || '#/' }
})()\`)
// p23-04: CDP 派发受信任 Enter 键（ego-browser 无 pressKey 助手）
await cdp('Input.dispatchKeyEvent', { type: 'keyDown', key: 'Enter', code: 'Enter', windowsVirtualKeyCode: 13, nativeVirtualKeyCode: 13 })
await cdp('Input.dispatchKeyEvent', { type: 'keyUp', key: 'Enter', code: 'Enter', windowsVirtualKeyCode: 13, nativeVirtualKeyCode: 13 })
await wait(2)
const after = await js(String.raw\`location.hash || '#/'\`)
const fs = await import('node:fs')
fs.writeFileSync('$DIR/keyboard.json', JSON.stringify({ before, after }))
cliLog('KB ' + before + ' -> ' + after)
await closeTab()
EGOEOF
python3 -c "
import json; d=json.load(open('$DIR/keyboard.json'))
b = d['before']['path'] if isinstance(d['before'], dict) else d['before']
a = d['after']['path'] if isinstance(d['after'], dict) else d['after']
exit(0 if b == '#/' and a.startswith('#/project/') else 1)"; record "row-keyboard-activate" $?

# ---------- 3. 工作台响应式：375 侧栏折叠 + 无溢出；桌面侧栏可见 ----------
ego-browser nodejs > "$DIR/ws-375.out" 2>&1 <<EGOEOF
const task = await useOrCreateTaskSpace('$TASK_ID')
await openOrReuseTab('$BASE/#/project/$P_FINAL', { wait: true, timeout: 30 })
await cdp('Emulation.setDeviceMetricsOverride', { width: 375, height: 667, deviceScaleFactor: 2, mobile: true })
await gotoAndWait('$BASE/#/project/$P_FINAL', {timeout:30,settle:1})
await wait(2)
for (let i = 0; i < 40; i++) {
  const width = await js('document.querySelector(".ant-layout-sider")?.getBoundingClientRect().width ?? -1')
  if (width >= 0 && width < 60) break
  await wait(0.25)
}
const mobile = await js(String.raw\`(() => {
  const sider = document.querySelector('.ant-layout-sider')
  return {
    siderW: sider ? sider.getBoundingClientRect().width : -1,
    viewport: innerWidth, media: matchMedia('(max-width: 991px)').matches, collapsed: sider?.classList.contains('ant-layout-sider-collapsed'),
    overflow: document.documentElement.scrollWidth - window.innerWidth,
  }
})()\`)
await cdp('Emulation.clearDeviceMetricsOverride')
await wait(1)
for (let i = 0; i < 40; i++) {
  const width = await js('document.querySelector(".ant-layout-sider")?.getBoundingClientRect().width ?? -1')
  if (width >= 300) break
  await wait(0.25)
}
const desktop = await js(String.raw\`(() => {
  const sider = document.querySelector('.ant-layout-sider')
  return { siderW: sider ? sider.getBoundingClientRect().width : -1,
           overflow: document.documentElement.scrollWidth - window.innerWidth }
})()\`)
const fs = await import('node:fs')
fs.writeFileSync('$DIR/ws-viewport.json', JSON.stringify({ mobile, desktop }))
cliLog('WS ' + JSON.stringify({ mobile, desktop }))
await closeTab()
EGOEOF
python3 -c "
import json; d=json.load(open('$DIR/ws-viewport.json'))
m, dk = d['mobile'], d['desktop']
exit(0 if (m['siderW'] < 60 and m['overflow'] <= 0 and dk['siderW'] >= 300 and dk['overflow'] <= 0) else 1)"; record "workspace-responsive-sider" $?

# ---------- 4. 终态门禁：FINAL 不呈现无效主操作 ----------
ego-browser nodejs > "$DIR/final.out" 2>&1 <<EGOEOF
const task = await useOrCreateTaskSpace('$TASK_ID')
await openOrReuseTab('$BASE/#/project/$P_FINAL', { wait: true, timeout: 30 })
let r = null
for (let i = 0; i < 10; i++) {
  await wait(1)
  r = await js(String.raw\`(() => {
    const btns = [...document.querySelectorAll('button')]
    const byText = (t) => btns.find(b => t.test(b.innerText.trim()))
    const run = byText(/^推进状态$/)
    const one = byText(/^写一章$/)
    const batch = byText(/连续写作（最多 50 章）/)
    const body = document.body.innerText
    return {
      finalAlert: body.includes('全书已完成') && !!document.querySelector('[data-collaboration-start]:disabled'),
      runAbsent: !run,
      oneAbsent: !one,
      batchAbsent: !batch,
      rawEnum: body.includes('NEEDS_REVISION') || body.includes('PAUSED_AT_CHECKPOINT'),
      bodyHead: body.slice(0, 150),
      hash: location.hash,
    }
  })()\`)
  if (r.finalAlert) break
}
r.bodyHead = undefined; r.hash = undefined
const fs = await import('node:fs')
fs.writeFileSync('$DIR/final.json', JSON.stringify(r))
cliLog('FINAL ' + JSON.stringify(r))
await closeTab()
EGOEOF
python3 -c "
import json; d=json.load(open('$DIR/final.json'))
exit(0 if d['finalAlert'] and d['runAbsent'] and d['oneAbsent'] and d['batchAbsent'] and not d['rawEnum'] else 1)"; record "terminal-final-disabled" $?

# ---------- 5. 终态门禁：NEEDS_REVISION 中文提示 + 重新评审 ----------
ego-browser nodejs > "$DIR/revision.out" 2>&1 <<EGOEOF
const task = await useOrCreateTaskSpace('$TASK_ID')
await openOrReuseTab('$BASE/#/project/$P_REV', { wait: true, timeout: 30 })
let r = null
for (let i = 0; i < 10; i++) {
  await wait(1)
  r = await js(String.raw\`(() => {
    const btns = [...document.querySelectorAll('button')]
    const one = btns.find(b => /^写一章$/.test(b.innerText.trim()))
    const body = document.body.innerText
    return {
      revisionAlert: body.includes('成书门禁未通过（待修订）'),
      reason: body.includes('伏笔') || body.includes('硬性门禁'),
      reAudit: !!btns.find(b => b.innerText.trim() === '重新评审'),
      oneAbsent: !one,
    }
  })()\`)
  if (r.revisionAlert) break
}
const fs = await import('node:fs')
fs.writeFileSync('$DIR/revision.json', JSON.stringify(r))
cliLog('REV ' + JSON.stringify(r))
await closeTab()
EGOEOF
python3 -c "
import json; d=json.load(open('$DIR/revision.json'))
exit(0 if d['revisionAlert'] and d['reason'] and d['reAudit'] and d['oneAbsent'] else 1)"; record "terminal-needs-revision" $?

# ---------- 6. 章节懒加载：元数据一次、正文按选中懒取 ----------
ego-browser nodejs > "$DIR/lazy.out" 2>&1 <<EGOEOF
const task = await useOrCreateTaskSpace('$TASK_ID')
await openOrReuseTab('$BASE/#/project/$P_LAZY', { wait: true, timeout: 30 })
await wait(2)
await js('(()=>{const t=[...document.querySelectorAll("[role=tab]")].find(x=>x.innerText.includes("创作工具"));if(!t)throw new Error("Missing tools tab");t.click()})()')
await wait(1)
await js(String.raw\`(() => { performance.clearResourceTimings(); return true })()\`)
await js(String.raw\`(() => {
  const t = [...document.querySelectorAll('.ant-tabs-tab')].find(x => x.innerText.includes('章节管理'))
  if (t) { t.click(); return true } return false
})()\`)
await wait(3)
const afterOpen = await js(String.raw\`(() => {
  const res = performance.getEntriesByType('resource').map(r => r.name)
  return {
    meta: res.filter(u => /\/chapters\?page/.test(u)).length,
    b1: res.filter(u => /\/chapters\/1$/.test(u)).length,
    b2: res.filter(u => /\/chapters\/2$/.test(u)).length,
  }
})()\`)
await js(String.raw\`(() => {
  const item = [...document.querySelectorAll('[role="button"]')].find(x => (x.getAttribute('aria-label')||'').includes('第2章'))
  if (item) { item.click(); return true } return false
})()\`)
await wait(3)
const afterClick = await js(String.raw\`(() => {
  const res = performance.getEntriesByType('resource').map(r => r.name)
  return {
    meta: res.filter(u => /\/chapters\?page/.test(u)).length,
    b1: res.filter(u => /\/chapters\/1$/.test(u)).length,
    b2: res.filter(u => /\/chapters\/2$/.test(u)).length,
    headingShown: document.body.innerText.includes('第2章'),
  }
})()\`)
const fs = await import('node:fs')
fs.writeFileSync('$DIR/lazy.json', JSON.stringify({ afterOpen, afterClick }))
cliLog('LAZY ' + JSON.stringify({ afterOpen, afterClick }))
await closeTab()
EGOEOF
python3 -c "
import json; d=json.load(open('$DIR/lazy.json'))
o, c = d['afterOpen'], d['afterClick']
exit(0 if o['meta'] == 1 and o['b1'] == 1 and o['b2'] == 0 and c['meta'] == 1 and c['b2'] == 1 and c['headingShown'] else 1)"; record "chapter-lazy-loading" $?

# ---------- 7. SSE 连接状态 ----------
ego-browser nodejs > "$DIR/sse.out" 2>&1 <<EGOEOF
const task = await useOrCreateTaskSpace('$TASK_ID')
await openOrReuseTab('$BASE/#/project/$P_SSE', { wait: true, timeout: 30 })
await wait(2)
await js('(()=>{const t=[...document.querySelectorAll("[role=tab]")].find(x=>x.innerText.includes("创作工具"));if(!t)throw new Error("Missing tools tab");t.click()})()')
await wait(1)
await js(String.raw\`(() => {
  const batch = [...document.querySelectorAll('button')].find(b => b.innerText.includes('连续写作（最多 50 章）'))
  if (batch) batch.click()
  return true
})()\`)
await wait(1)
await js(String.raw\`(() => {
  const ok = [...document.querySelectorAll('button')].find(b => b.innerText.trim() === '开始连续写作')
  if (ok) ok.click()
  return true
})()\`)
let seen = ''
let bodyAtEnd = ''
for (let i = 0; i < 60; i++) {
  const s = await js(String.raw\`(() => {
    const body = document.body.innerText
    if (body.includes('实时通道已连接')) return 'connected'
    if (body.includes('实时通道重连中')) return 'reconnecting'
    if (body.includes('实时通道不可用')) return 'fallback'
    if (body.includes('连续写作完成')) return 'done'
    return ''
  })()\`)
  if (s && !seen) seen = s
  if (s === 'done') break
  await wait(1)
}
const doneVisible = await js(String.raw\`document.body.innerText.includes('连续写作完成')\`)
bodyAtEnd = await js(String.raw\`document.body.innerText.slice(0, 260)\`)
const fs = await import('node:fs')
fs.writeFileSync('$DIR/sse.json', JSON.stringify({ seen, doneVisible, bodyAtEnd }))
cliLog('SSE ' + JSON.stringify({ seen, doneVisible }))
await closeTab()
EGOEOF
python3 -c "
import json; d=json.load(open('$DIR/sse.json'))
exit(0 if d['doneVisible'] and d['seen'] in ('connected','done') else 1)"; record "sse-connected-status" $?

kill_port "$PORT"

# ---------- 8. 人工复核（harsh 档） ----------
start_app "--llm.scenario.harsh=true" || { echo "FAIL harsh app start"; exit 1; }
P_MR=$(seed_project "e2e-manual" 3 0)
curl --noproxy '*' -sf -X POST "$BASE/api/projects/$P_MR/write-all?maxChapters=3" > /dev/null
sleep 6
curl --noproxy '*' -sf "$BASE/api/projects/$P_MR/chapters/1" > "$DIR/mr-ch1.json"
ego-browser nodejs > "$DIR/manual.out" 2>&1 <<EGOEOF
const task = await useOrCreateTaskSpace('$TASK_ID')
await openOrReuseTab('$BASE/#/project/$P_MR', { wait: true, timeout: 30 })
await wait(2)
await js(String.raw\`(() => {
  const t = [...document.querySelectorAll('.ant-tabs-tab')].find(x => x.innerText.includes('章节管理'))
  if (t) t.click()
  return true
})()\`)
let r = null
for (let i = 0; i < 10; i++) {
  await wait(1)
  r = await js(String.raw\`(() => {
    const body = document.body.innerText
    const btns = [...document.querySelectorAll('button')]
    return {
      pendingTag: body.includes('待人审'),
      resolveBtn: !!btns.find(b => b.innerText.trim() === '已处理'),
    }
  })()\`)
  if (r.pendingTag && r.resolveBtn) break
}
let resolved = false
if (r.resolveBtn) {
  await js(String.raw\`(() => {
    const b = [...document.querySelectorAll('button')].find(x => x.innerText.trim() === '已处理')
    if (b) b.click()
    return true
  })()\`)
  await wait(3)
  resolved = await js(String.raw\`document.body.innerText.includes('已标为处理完毕')\`)
}
const fs = await import('node:fs')
fs.writeFileSync('$DIR/manual.json', JSON.stringify({ pendingTag: r.pendingTag, resolveBtn: r.resolveBtn, resolved }))
cliLog('MR ' + JSON.stringify({ pendingTag: r.pendingTag, resolveBtn: r.resolveBtn, resolved }))
await closeTab()
EGOEOF
python3 -c "
import json
ui = json.load(open('$DIR/manual.json'))
ch1 = json.load(open('$DIR/mr-ch1.json'))
exit(0 if ch1.get('status') == 'NEEDS_MANUAL_REVIEW' and ui['pendingTag'] and ui['resolveBtn'] and ui['resolved'] else 1)"; record "manual-review-resolve" $?
kill_port "$PORT"

python3 - "$DIR" <<'PYAGG'
import json, sys, datetime, os, glob
d = sys.argv[1]
summary = {"generatedAt": datetime.datetime.now().isoformat(), "kind": "browser-e2e",
           "tool": "ego-browser",
           "artifacts": sorted(os.path.basename(f) for f in glob.glob(os.path.join(d, "*.json")))}
with open(os.path.join(d, "summary.json"), "w", encoding="utf-8") as f:
    json.dump(summary, f, ensure_ascii=False, indent=2)
PYAGG

if [ ${#FAIL[@]} -eq 0 ]; then
  echo "BROWSER-E2E PASS (${#PASS[@]} checks) — $DIR/summary.json"
  exit 0
else
  echo "BROWSER-E2E FAIL — failed: ${FAIL[*]}; artifacts in $DIR/"
  exit 1
fi
