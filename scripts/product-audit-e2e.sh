#!/usr/bin/env bash
# p25-01: 页面产品一致性审计 E2E 红灯套件。
# 默认：产品断言失败即非零。
# --expect-known-failures：仅当失败集合与 KNOWN_FAILURES 完全一致且无 ERROR 时通过。
set -u
cd "$(dirname "$0")/.."

PORT=8096
EXPECT_KNOWN=0
ONLY_CASE=""
LIST_ONLY=0

CASES=(
  auto-zero-intervention
  initial-setup-summary
  initial-setup-modify
  volume-outline-modify
  standalone-chapter-rewrite
  interactive-to-auto
  approve-keeps-chapter-visible
  checkpoint-card-targeting
  manual-content-management
  mobile-critical-path
)

# 测试先行基线：修复某项后，必须从此数组移除对应 ID；XPASS 会使校验失败。
KNOWN_FAILURES=(
  auto-zero-intervention
  interactive-to-auto
  manual-content-management
  mobile-critical-path
)

describe_case() {
  case "$1" in
    auto-zero-intervention) echo "P1-01|P1|自动项目一次启动后生成 3 章并到达 FINAL" ;;
    initial-setup-summary) echo "P1-08|P1|初始设定审核展示真实摘要并可跳转内容页签" ;;
    initial-setup-modify) echo "P0-01|P0|初始设定带意见重做后内容发生变化" ;;
    volume-outline-modify) echo "P0-01|P0|卷大纲带意见重做且不覆盖已写章节" ;;
    standalone-chapter-rewrite) echo "P0-02|P0|无待审核项时独立重写章节" ;;
    interactive-to-auto) echo "P0-03|P0|审核点切自动后解除等待并续跑" ;;
    approve-keeps-chapter-visible) echo "P1-03|P1|批准章节后正文持续可见" ;;
    checkpoint-card-targeting) echo "P1-07|P1|多审核卡操作命中所点 checkpoint" ;;
    manual-content-management) echo "P1-04/P1-05/P1-06|P1|四类内容具备完整人工管理入口" ;;
    mobile-critical-path) echo "P2-02|P2|375px 关键路径无遮挡且可键盘操作" ;;
  esac
}

usage() {
  echo "usage: $0 [--port N] [--case ID] [--list] [--expect-known-failures]"
}

while [ $# -gt 0 ]; do
  case "$1" in
    --port) PORT="$2"; shift 2 ;;
    --case) ONLY_CASE="$2"; shift 2 ;;
    --list) LIST_ONLY=1; shift ;;
    --expect-known-failures) EXPECT_KNOWN=1; shift ;;
    -h|--help) usage; exit 0 ;;
    *) echo "unknown arg: $1"; usage; exit 2 ;;
  esac
done

contains_case() {
  local needle="$1"
  shift
  local item
  for item in "$@"; do
    [ "$item" = "$needle" ] && return 0
  done
  return 1
}

if [ -n "$ONLY_CASE" ] && ! contains_case "$ONLY_CASE" "${CASES[@]}"; then
  echo "unknown case: $ONLY_CASE"
  exit 2
fi

if [ "$LIST_ONLY" = "1" ]; then
  printf '%-34s %-9s %-3s %s\n' "ID" "ISSUE" "PRI" "SUCCESS CRITERIA"
  for id in "${CASES[@]}"; do
    IFS='|' read -r issue priority description <<< "$(describe_case "$id")"
    printf '%-34s %-9s %-3s %s\n' "$id" "$issue" "$priority" "$description"
  done
  exit 0
fi

SELECTED=()
if [ -n "$ONLY_CASE" ]; then
  SELECTED=("$ONLY_CASE")
else
  SELECTED=("${CASES[@]}")
fi

ROOT="$(pwd)"
DIR="$ROOT/data/e2e/product-audit-browser"
BASE="http://127.0.0.1:$PORT"
APP_PID=""

cleanup() {
  if [ -n "$APP_PID" ] && kill -0 "$APP_PID" 2>/dev/null; then
    kill "$APP_PID" 2>/dev/null || true
    wait "$APP_PID" 2>/dev/null || true
  fi
}
trap cleanup EXIT INT TERM

if lsof -tiTCP:"$PORT" -sTCP:LISTEN >/dev/null 2>&1; then
  echo "ERROR port $PORT is already in use; refusing to stop an unrelated process"
  exit 2
fi

rm -rf "$DIR"
mkdir -p "$DIR"

echo "=== Product Audit Browser E2E ==="
echo "cases: ${SELECTED[*]}"

mvn -q spring-boot:run \
  -Dspring-boot.run.arguments="--server.port=$PORT --llm.scenario.enabled=true --llm.replay.mode=off --persistence.embedding=stub --persistence.sqlite.db-path=$DIR/novel.db --persistence.vector-store.index-path=$DIR/vectors.idx" \
  > "$DIR/server.log" 2>&1 &
APP_PID=$!

ready=0
for _ in $(seq 1 90); do
  if curl --noproxy '*' -sf "$BASE/api/projects" >/dev/null 2>&1; then
    ready=1
    break
  fi
  sleep 1
done
if [ "$ready" != "1" ]; then
  echo "ERROR app did not become ready; see $DIR/server.log"
  exit 2
fi

create_project() {
  local title="$1"
  local mode="$2"
  local target="$3"
  curl --noproxy '*' -sf -X POST "$BASE/api/projects" \
    -H 'Content-Type: application/json' \
    -d "{\"title\":\"$title\",\"concept\":\"产品审计确定性场景\",\"tags\":\"悬疑,古代\",\"mode\":\"$mode\",\"preset\":\"mystery\",\"targetChapters\":$target}" \
    | python3 -c "import json,sys; print(json.load(sys.stdin)['id'])"
}

wait_task() {
  local project_id="$1"
  local task_id="$2"
  local status=""
  local i
  for i in $(seq 1 180); do
    status=$(curl --noproxy '*' -sf "$BASE/api/projects/$project_id/writing-tasks/$task_id" \
      | python3 -c "import json,sys; print(json.load(sys.stdin).get('status',''))") || return 1
    case "$status" in
      COMPLETED|PAUSED) return 0 ;;
      FAILED|CANCELLED) echo "seed task $task_id ended as $status" >&2; return 1 ;;
    esac
    sleep 1
  done
  echo "seed task $task_id timed out" >&2
  return 1
}

conceive_once() {
  local project_id="$1"
  local response task_id
  response=$(curl --noproxy '*' -sf -X POST "$BASE/api/projects/$project_id/run") || return 1
  task_id=$(printf '%s' "$response" | python3 -c "import json,sys; print(json.load(sys.stdin).get('taskId',''))")
  [ -n "$task_id" ] || return 1
  wait_task "$project_id" "$task_id"
}

advance_once() {
  curl --noproxy '*' -sf -X POST "$BASE/api/projects/$1/run" >/dev/null
}

write_one() {
  curl --noproxy '*' -sf -X POST "$BASE/api/projects/$1/write-chapter" \
    -H 'Content-Type: application/json' -d '{}' >/dev/null
}

need_case() {
  contains_case "$1" "${SELECTED[@]}"
}

# ---------- 隔离数据种子 ----------
P_AUTO=""
P_SETUP_SUMMARY=""
P_SETUP=""
P_VOLUME=""
P_REWRITE=""
P_SWITCH=""
P_APPROVE=""
P_MULTI=""
CP_SETUP=""
CP_VOLUME=""
CP_MULTI_FIRST=""
CP_MULTI_LATEST=""
CP_SETUP_MALFORMED=""

if need_case auto-zero-intervention; then
  P_AUTO=$(create_project "audit-auto" SOLO 3) || exit 2
  conceive_once "$P_AUTO" || exit 2
fi

if need_case initial-setup-summary; then
  P_SETUP_SUMMARY=$(create_project "audit-setup-summary" INTERACTIVE 3) || exit 2
  conceive_once "$P_SETUP_SUMMARY" || exit 2
  CP_SETUP_MALFORMED=$(uuidgen | tr '[:upper:]' '[:lower:]')
  sqlite3 "$DIR/novel.db" \
    "INSERT INTO checkpoints(id,project_id,type,description,status,created_at,payload) VALUES('$CP_SETUP_MALFORMED','$P_SETUP_SUMMARY','AFTER_INITIAL_SETUP','旧异常摘要','PENDING',datetime('now'),'{\"worldSummary\":{\"unexpected\":\"invalid-world\"},\"characterSummary\":[\"invalid-character\"],\"premiseSummary\":42,\"links\":[{\"label\":\"安全跳转\",\"target\":\"world\"},{\"label\":\"异常跳转\",\"target\":\"missing\"}]}');" || exit 2
fi

if need_case initial-setup-modify; then
  P_SETUP=$(create_project "audit-setup" INTERACTIVE 3) || exit 2
  conceive_once "$P_SETUP" || exit 2
  CP_SETUP=$(curl --noproxy '*' -sf "$BASE/api/projects/$P_SETUP/checkpoints" \
    | python3 -c "import json,sys; a=json.load(sys.stdin); print(a[-1]['id'])") || exit 2
fi

if need_case volume-outline-modify; then
  P_VOLUME=$(create_project "audit-volume" SOLO 3) || exit 2
  conceive_once "$P_VOLUME" || exit 2
  CP_VOLUME=$(uuidgen | tr '[:upper:]' '[:lower:]')
  sqlite3 "$DIR/novel.db" \
    "INSERT INTO checkpoints(id,project_id,type,description,status,created_at,payload) VALUES('$CP_VOLUME','$P_VOLUME','AFTER_VOLUME_OUTLINE','卷1大纲已生成，待审核','PENDING',datetime('now'),NULL); UPDATE projects SET state='PAUSED_AT_CHECKPOINT' WHERE id='$P_VOLUME';" || exit 2
fi

if need_case standalone-chapter-rewrite || need_case manual-content-management || need_case mobile-critical-path; then
  P_REWRITE=$(create_project "audit-content" SOLO 3) || exit 2
  conceive_once "$P_REWRITE" || exit 2
  advance_once "$P_REWRITE" || exit 2
  write_one "$P_REWRITE" || exit 2
  # Scenario gateway deliberately returns a fixed rewrite body.  Give the existing
  # chapter a distinct persisted source body so this browser case can assert the
  # real rewrite contract: a successful request replaces visible content as well
  # as incrementing the version.
  sqlite3 "$DIR/novel.db" \
    "UPDATE chapters SET content = content || char(10) || char(10) || '【重写前来源标记】' WHERE project_id='$P_REWRITE' AND chapter_index=1;" || exit 2
fi

if need_case manual-content-management; then
  # Two written-boundary volumes expose an important UI invariant: adding an unwritten
  # chapter to an earlier written volume would place a new high chapter index before a
  # later locked chapter and make the complete outline invalid.
  second_volume_id=$(uuidgen | tr '[:upper:]' '[:lower:]')
  fourth_synopsis_id=$(uuidgen | tr '[:upper:]' '[:lower:]')
  sqlite3 "$DIR/novel.db" \
    "INSERT INTO volumes(id,project_id,volume_index,title,summary) VALUES('$second_volume_id','$P_REWRITE',2,'第二卷','第二卷边界测试'); UPDATE chapter_synopses SET volume_id='$second_volume_id' WHERE project_id='$P_REWRITE' AND chapter_index IN (2,3); INSERT INTO chapter_synopses(id,project_id,volume_id,chapter_index,title,synopsis,writing_notes) VALUES('$fourth_synopsis_id','$P_REWRITE','$second_volume_id',4,'事件4','第4章的场景梗概，用于验证单独移动后立即保存。',NULL); UPDATE projects SET current_chapter_index=2 WHERE id='$P_REWRITE';" || exit 2
fi

if need_case interactive-to-auto; then
  P_SWITCH=$(create_project "audit-switch" INTERACTIVE 3) || exit 2
  conceive_once "$P_SWITCH" || exit 2
fi

if need_case approve-keeps-chapter-visible; then
  P_APPROVE=$(create_project "audit-approve" INTERACTIVE 3) || exit 2
  conceive_once "$P_APPROVE" || exit 2
  cp_id=$(curl --noproxy '*' -sf "$BASE/api/projects/$P_APPROVE/checkpoints" \
    | python3 -c "import json,sys; a=json.load(sys.stdin); print(a[-1]['id'])") || exit 2
  curl --noproxy '*' -sf -X POST "$BASE/api/projects/$P_APPROVE/checkpoints/$cp_id/approve" >/dev/null || exit 2
  advance_once "$P_APPROVE" || exit 2
  write_one "$P_APPROVE" || exit 2
  cp_id=$(curl --noproxy '*' -sf "$BASE/api/projects/$P_APPROVE/checkpoints" \
    | python3 -c "import json,sys; a=json.load(sys.stdin); print(a[-1]['id'])") || exit 2
  curl --noproxy '*' -sf -X POST "$BASE/api/projects/$P_APPROVE/checkpoints/$cp_id/approve" >/dev/null || exit 2
  write_one "$P_APPROVE" || exit 2
fi

if need_case checkpoint-card-targeting; then
  P_MULTI=$(create_project "audit-multi" SOLO 3) || exit 2
  conceive_once "$P_MULTI" || exit 2
  CP_MULTI_FIRST=$(uuidgen | tr '[:upper:]' '[:lower:]')
  CP_MULTI_LATEST=$(uuidgen | tr '[:upper:]' '[:lower:]')
  sqlite3 "$DIR/novel.db" \
    "INSERT INTO checkpoints(id,project_id,type,description,status,created_at,payload) VALUES('$CP_MULTI_FIRST','$P_MULTI','AFTER_INITIAL_SETUP','较早审核项','PENDING','2026-09-02T01:00:00Z','{\"preview\":\"较早卡片审核摘要\",\"score\":8}'); INSERT INTO checkpoints(id,project_id,type,description,status,created_at,payload) VALUES('$CP_MULTI_LATEST','$P_MULTI','AFTER_VOLUME_OUTLINE','最新审核项','PENDING','2026-09-02T01:00:01Z',NULL); UPDATE projects SET state='PAUSED_AT_CHECKPOINT' WHERE id='$P_MULTI';" || exit 2
fi

SELECTED_JSON=$(printf '%s\n' "${SELECTED[@]}" | python3 -c "import json,sys; print(json.dumps([x.strip() for x in sys.stdin if x.strip()]))")

ego-browser nodejs > "$DIR/browser.out" 2>&1 <<EGOEOF
const fs = await import('node:fs')
const crypto = await import('node:crypto')
const selected = new Set($SELECTED_JSON)
const results = []
const base = '$BASE'
const task = await useOrCreateTaskSpace('writing agent product audit e2e')
fs.writeFileSync('$DIR/task-space.txt', String(task.id))
await openOrReuseTab(base + '/', { wait: true, timeout: 30 })

const fingerprint = (value) => crypto.createHash('sha256').update(JSON.stringify(value)).digest('hex')
const worldFingerprint = (entries) => fingerprint((entries || []).map(entry => ({
  id: entry.id,
  type: entry.type,
  name: entry.name,
  content: entry.content,
  version: entry.version,
})).sort((a, b) => String(a.id).localeCompare(String(b.id))))

async function pageJson(path, options) {
  // 当前 ego-browser 的 js() 不等待页面 Promise；只用于 GET 的同步 XHR 保证返回可序列化对象。
  const source = '(() => {const x=new XMLHttpRequest();x.open("GET",' + JSON.stringify(path) + ',false);x.send();' +
    'if(x.status<200||x.status>=300)throw new Error("HTTP "+x.status+" "+x.responseText);' +
    'return x.responseText?JSON.parse(x.responseText):null})()'
  return await js(source)
}

async function go(path) {
  await gotoAndWait(base + path, { timeout: 30, settle: 1 })
  await wait(2)
}

async function clickButtonContaining(text, scopeText) {
  const source = '(() => {' +
    'const scopes=' + (scopeText ? '[...document.querySelectorAll(".ant-alert")].filter(x=>x.innerText.includes(' + JSON.stringify(scopeText) + '))' : '[document]') + ';' +
    'for(const scope of scopes){const b=[...scope.querySelectorAll("button")].find(x=>x.innerText.includes(' + JSON.stringify(text) + '));if(b){b.click();return true}}return false})()'
  return await js(source)
}

async function openTab(text) {
  const clicked = await js('(() => {const t=[...document.querySelectorAll(".ant-tabs-tab")].find(x=>x.innerText.includes(' + JSON.stringify(text) + '));if(t){t.click();return true}return false})()')
  if (clicked) await wait(2)
  return clicked
}

async function modalOpen() {
  return await js('!![...document.querySelectorAll(".ant-modal-wrap")].find(x=>getComputedStyle(x).display!=="none")')
}

async function submitModal(instruction, buttonText) {
  if (!await modalOpen()) return false
  await fillInput('.ant-modal-wrap textarea', instruction)
  return await js('(() => {const modal=[...document.querySelectorAll(".ant-modal-wrap")].find(x=>getComputedStyle(x).display!=="none");const b=[...modal.querySelectorAll("button")].find(x=>x.innerText.includes(' + JSON.stringify(buttonText) + '));if(b){b.click();return true}return false})()')
}

async function runCase(id, fn) {
  if (!selected.has(id)) return
  try {
    const detail = await fn()
    const passed = !!detail.pass
    delete detail.pass
    results.push({ id, status: passed ? 'PASS' : 'XFAIL', detail })
    cliLog((passed ? 'PASS  ' : 'XFAIL ') + id + ' ' + JSON.stringify(detail))
  } catch (error) {
    const message = error && error.stack ? error.stack : String(error)
    results.push({ id, status: 'ERROR', detail: { message } })
    cliLog('ERROR ' + id + ' ' + message)
  }
}

await runCase('auto-zero-intervention', async () => {
  await go('/#/project/$P_AUTO')
  const status = await pageJson('/api/projects/$P_AUTO/status')
  const chapters = await pageJson('/api/projects/$P_AUTO/chapters?page=0&size=10')
  const count = (chapters.chapters || []).length
  return { pass: status.state === 'FINAL' && status.currentChapter === 3 && count === 3,
    state: status.state, currentChapter: status.currentChapter, chapterCount: count }
})

await runCase('initial-setup-summary', async () => {
  await go('/#/project/$P_SETUP_SUMMARY')
  await openTab('章节管理')
  const summary = await js('(() => {const a=[...document.querySelectorAll(".ant-alert")].find(x=>x.innerText.includes("初始设定"));const text=a?.innerText||"";return {world:text.includes("场景大陆001"),character:text.includes("主角001"),premise:text.includes("产品审计确定性场景")&&text.includes("查清师门旧案")}})()')
  const malformed = await js('(() => {const a=[...document.querySelectorAll(".ant-alert")].find(x=>x.innerText.includes("旧异常摘要"));const text=a?.innerText||"";const buttons=a?[...a.querySelectorAll("button")]:[];return {visible:!!a,invalidSummariesHidden:!text.includes("世界观摘要")&&!text.includes("人物摘要")&&!text.includes("原旨与大纲摘要")&&!text.includes("invalid-"),safeLink:!!buttons.find(b=>b.innerText.trim()==="安全跳转"),badLinkAbsent:!buttons.find(b=>b.innerText.trim()==="异常跳转")}})()')
  const worldClicked = await clickButtonContaining('查看世界观与人物', '初始设定')
  await wait(2)
  const worldSelected = await js('!![...document.querySelectorAll("[role=tab]")].find(x=>x.innerText.includes("世界观与人物")&&x.getAttribute("aria-selected")==="true")')
  await openTab('章节管理')
  const outlineClicked = await clickButtonContaining('查看大纲', '初始设定')
  await wait(2)
  const outlineSelected = await js('!![...document.querySelectorAll("[role=tab]")].find(x=>x.innerText.includes("大纲")&&x.getAttribute("aria-selected")==="true")')
  return { pass: summary.world && summary.character && summary.premise && malformed.visible && malformed.invalidSummariesHidden && malformed.safeLink && malformed.badLinkAbsent && worldClicked && worldSelected && outlineClicked && outlineSelected,
    summary, malformed, worldClicked, worldSelected, outlineClicked, outlineSelected }
})

await runCase('initial-setup-modify', async () => {
  await go('/#/project/$P_SETUP')
  const before = await pageJson('/api/projects/$P_SETUP/worldbuilding')
  await openTab('章节管理')
  const opened = await clickButtonContaining('修改意见重写', '初始设定')
  const submitted = opened && await submitModal('改成低魔历史题材，删除所有修炼体系', '重写后批准')
  await wait(4)
  const after = await pageJson('/api/projects/$P_SETUP/worldbuilding')
  const pending = await pageJson('/api/projects/$P_SETUP/checkpoints')
  const changed = worldFingerprint(before) !== worldFingerprint(after)
  const closed = !(await modalOpen())
  const handled = !pending.some(cp => cp.id === '$CP_SETUP')
  return { pass: opened && submitted && changed && closed && handled,
    opened, submitted, changed, modalClosed: closed, checkpointHandled: handled }
})

await runCase('volume-outline-modify', async () => {
  await go('/#/project/$P_VOLUME')
  const before = await pageJson('/api/projects/$P_VOLUME/outline')
  const beforeChapter = (await pageJson('/api/projects/$P_VOLUME/status')).currentChapter
  await openTab('章节管理')
  const opened = await clickButtonContaining('修改意见重写', '卷1大纲')
  const submitted = opened && await submitModal('把第二章改为调查账册失踪案', '重写后批准')
  await wait(4)
  const after = await pageJson('/api/projects/$P_VOLUME/outline')
  const afterChapter = (await pageJson('/api/projects/$P_VOLUME/status')).currentChapter
  const pending = await pageJson('/api/projects/$P_VOLUME/checkpoints')
  const changed = fingerprint(before) !== fingerprint(after)
  const preserved = beforeChapter === afterChapter
  const handled = !pending.some(cp => cp.id === '$CP_VOLUME')
  return { pass: opened && submitted && changed && preserved && handled,
    opened, submitted, changed, writtenChapterPreserved: preserved, checkpointHandled: handled }
})

await runCase('standalone-chapter-rewrite', async () => {
  await go('/#/project/$P_REWRITE')
  const before = await pageJson('/api/projects/$P_REWRITE/chapters/1')
  const pendingBefore = await pageJson('/api/projects/$P_REWRITE/checkpoints')
  await openTab('章节管理')
  const opened = await clickButtonContaining('重写此章')
  const submitted = opened && await submitModal('改为女医师视角并增加证据推理', '重写此章')
  await wait(4)
  const after = await pageJson('/api/projects/$P_REWRITE/chapters/1')
  const closed = !(await modalOpen())
  const versionIncreased = after.version > before.version
  const contentChanged = after.content !== before.content
  const rewrittenBodyVisible = await js('document.body.innerText.includes(' + JSON.stringify(after.content.slice(0, 28)) + ')')
  const staleSourceMarkerAbsent = await js('!document.body.innerText.includes("【重写前来源标记】")')
  const noPendingCheckpoint = pendingBefore.length === 0
  return { pass: noPendingCheckpoint && opened && submitted && closed && versionIncreased && contentChanged &&
      rewrittenBodyVisible && staleSourceMarkerAbsent,
    opened, submitted, modalClosed: closed, beforeVersion: before.version,
    afterVersion: after.version, contentChanged, pendingBefore: pendingBefore.length,
    rewrittenBodyVisible, staleSourceMarkerAbsent }
})

await runCase('interactive-to-auto', async () => {
  await go('/#/project/$P_SWITCH')
  const clicked = await clickButtonContaining('切换')
  await wait(4)
  const project = await pageJson('/api/projects/$P_SWITCH')
  const pending = await pageJson('/api/projects/$P_SWITCH/checkpoints')
  const resumed = project.state !== 'PAUSED_AT_CHECKPOINT' && pending.length === 0
  return { pass: clicked && project.mode === 'SOLO' && resumed,
    clicked, mode: project.mode, state: project.state, pending: pending.length }
})

await runCase('approve-keeps-chapter-visible', async () => {
  await go('/#/project/$P_APPROVE')
  const chapter = await pageJson('/api/projects/$P_APPROVE/chapters/2')
  const marker = chapter.content.slice(0, 28)
  await openTab('章节管理')
  await wait(3)
  const switched = await js('(() => {const row=[...document.querySelectorAll("[role=button]")].find(x=>x.innerText.includes("第2章"));if(row){row.click();return true}return false})()')
  await wait(2)
  const visibleBefore = await js('document.body.innerText.includes(' + JSON.stringify(marker) + ')')
  const refreshed = await js('(() => {const b=[...document.querySelectorAll("button")].find(x=>x.innerText.trim()==="刷新");if(!b)return false;b.click();b.click();return true})()')
  await wait(2)
  const visibleAfterRefresh = await js('document.body.innerText.includes(' + JSON.stringify(marker) + ')')
  const clicked = await clickButtonContaining('批准', '第2章')
  await wait(4)
  const state = await pageJson('/api/projects/$P_APPROVE/status')
  const visibleAfter = await js('document.body.innerText.includes(' + JSON.stringify(marker) + ')')
  const bodyLoading = await js('!!document.querySelector("[data-paragraph-index]")===false && !!document.querySelector(".ant-spin-dot-spin")')
  return { pass: switched && visibleBefore && refreshed && visibleAfterRefresh && clicked && visibleAfter && !bodyLoading,
    switched, visibleBefore, refreshed, visibleAfterRefresh, clicked, visibleAfter, bodyLoading, state: state.state }
})

await runCase('checkpoint-card-targeting', async () => {
  await go('/#/project/$P_MULTI')
  await openTab('章节管理')
  // Regression target: changing submission to the latest checkpoint or dropping the returned
  // revision result must fail this real browser flow.
  const card = await js('(() => {const a=[...document.querySelectorAll(".ant-alert")].find(x=>x.innerText.includes("较早审核项"));const text=a?.innerText||"";return {target:text.includes("审核目标：AFTER_INITIAL_SETUP"),payload:text.includes("较早卡片审核摘要")}})()')
  const opened = await clickButtonContaining('拒绝并重写', '较早审核项')
  const submitted = opened && await submitModal('只处理较早审核项', '重写（拒绝当前版本）')
  await wait(3)
  const pending = await pageJson('/api/projects/$P_MULTI/checkpoints')
  const ids = pending.map(cp => cp.id)
  const firstHandled = !ids.includes('$CP_MULTI_FIRST')
  const latestUntouched = ids.includes('$CP_MULTI_LATEST')
  const result = await js('(() => {const a=[...document.querySelectorAll(".ant-alert.ant-alert-success")].find(x=>x.innerText.includes("较早审核项"));const text=a?.innerText||"";return {description:text.includes("较早审核项"),target:text.includes("INITIAL_SETUP"),before:text.includes("修改前摘要")&&text.includes("CUSTOM|信物001|师门玉佩"),after:text.includes("修改后摘要")&&text.includes("CULTURE|乡约礼俗002|宗族与保甲"),text}})()')
  return { pass: card.target && card.payload && opened && submitted && firstHandled && latestUntouched &&
      result.description && result.target && result.before && result.after,
    card, opened, submitted, firstHandled, latestUntouched, pendingIds: ids, result }
})

await runCase('manual-content-management', async () => {
  await go('/#/project/$P_REWRITE')
  const world = await pageJson('/api/projects/$P_REWRITE/worldbuilding')
  const characters = await pageJson('/api/projects/$P_REWRITE/characters')
  await openTab('世界观与人物')
  const worldTarget = world[0]
  const characterTarget = characters[0]
  const worldExpected = { type: 'ECONOMY', name: '漕运税则（人工）', content: '每十船抽一船，刷新后仍须完整保留。' }
  const characterExpected = {
    name: '沈砚（人工）', role: '主角', personality: '谨慎而执拗', ability: '辨认暗账',
    motivation: '查清漕粮亏空', appearance: '左眉有浅疤', relationships: '与顾清舟互为盟友',
    growthArc: '从自保走向担当',
  }
  const worldScope = '[data-world-entry-id="' + (worldTarget?.id || '') + '"]'
  const characterScope = '[data-character-id="' + (characterTarget?.id || '') + '"]'

  await click(worldScope + ' .ant-select-selector')
  await wait(1)
  const worldTypeOpened = await js('!!document.querySelector(".ant-select-dropdown:not(.ant-select-dropdown-hidden)")')
  if (worldTypeOpened) await click('.ant-select-dropdown:not(.ant-select-dropdown-hidden) .ant-select-item-option[title="经济"]')
  const worldTypeSelected = await js('document.querySelector(' + JSON.stringify(worldScope) + ')?.querySelector(".ant-select-selection-item")?.innerText==="经济"')
  await fillInput('input[aria-label=' + JSON.stringify((worldTarget?.name || '') + ' 世界观名称') + ']', worldExpected.name)
  await fillInput(worldScope + ' textarea', worldExpected.content)
  const worldSaveClicked = await js('(() => {const scope=document.querySelector(' + JSON.stringify(worldScope) + ');const button=[...(scope?.querySelectorAll("button")||[])].find(x=>x.innerText.includes("保存设定"));if(!button)return false;button.click();return true})()')
  await wait(2)

  const oldCharacterName = characterTarget?.name || ''
  await fillInput('input[aria-label=' + JSON.stringify(oldCharacterName + ' 姓名') + ']', characterExpected.name)
  await fillInput('input[aria-label=' + JSON.stringify(oldCharacterName + ' 角色定位') + ']', characterExpected.role)
  await fillInput('textarea[aria-label=' + JSON.stringify(oldCharacterName + ' 性格') + ']', characterExpected.personality)
  await fillInput('textarea[aria-label=' + JSON.stringify(oldCharacterName + ' 能力') + ']', characterExpected.ability)
  await fillInput('textarea[aria-label=' + JSON.stringify(oldCharacterName + ' 动机') + ']', characterExpected.motivation)
  await fillInput('textarea[aria-label=' + JSON.stringify(oldCharacterName + ' 外貌') + ']', characterExpected.appearance)
  await fillInput('textarea[aria-label=' + JSON.stringify(oldCharacterName + ' 关系') + ']', characterExpected.relationships)
  await fillInput('textarea[aria-label=' + JSON.stringify(oldCharacterName + ' 成长弧') + ']', characterExpected.growthArc)
  const characterSaveClicked = await js('(() => {const scope=document.querySelector(' + JSON.stringify(characterScope) + ');const button=[...(scope?.querySelectorAll("button")||[])].find(x=>x.innerText.includes("保存人物"));if(!button)return false;button.click();return true})()')
  await wait(2)

  const worldAfterSave = (await pageJson('/api/projects/$P_REWRITE/worldbuilding')).find(x=>x.id===worldTarget?.id)
  const characterAfterSave = (await pageJson('/api/projects/$P_REWRITE/characters')).find(x=>x.id===characterTarget?.id)
  const worldPersisted = !!worldAfterSave && Object.entries(worldExpected).every(([key, value])=>worldAfterSave[key]===value)
  const characterPersisted = !!characterAfterSave && Object.entries(characterExpected).every(([key, value])=>characterAfterSave[key]===value)

  await go('/#/project/$P_REWRITE')
  await openTab('世界观与人物')
  const worldFields = await js('(() => {const scope=document.querySelector(' + JSON.stringify(worldScope) + ');return {type:scope?.querySelector(".ant-select-selection-item")?.innerText==="经济",name:scope?.querySelector("input.ant-input")?.value===' + JSON.stringify(worldExpected.name) + ',content:scope?.querySelector("textarea")?.value===' + JSON.stringify(worldExpected.content) + '}})()')
  const characterFields = await js('(() => {const scope=document.querySelector(' + JSON.stringify(characterScope) + ');const values=[...scope.querySelectorAll("input.ant-input,textarea")].map(x=>x.value);const expected=' + JSON.stringify(Object.values(characterExpected)) + ';return {allEight:expected.every(x=>values.includes(x)),saveButton:[...scope.querySelectorAll("button")].some(x=>x.innerText.includes("保存人物"))}})()')
  await openTab('大纲')
  const statusBeforeOutlineEdit = await pageJson('/api/projects/$P_REWRITE/status')
  const outlineBefore = await pageJson('/api/projects/$P_REWRITE/outline')
  const seedOrder = outlineBefore.volumes.flatMap(volume => volume.synopses.map(synopsis => ({
    id: synopsis.id, volumeIndex: volume.volumeIndex, chapterIndex: synopsis.chapterIndex,
    title: synopsis.title, synopsis: synopsis.synopsis,
  })))
  const targetVolume = outlineBefore.volumes.find(volume => volume.synopses.filter(s => s.chapterIndex > statusBeforeOutlineEdit.currentChapter).length >= 2)
  const targetUnwritten = targetVolume?.synopses.filter(s => s.chapterIndex > statusBeforeOutlineEdit.currentChapter) || []
  const deletedSeed = targetUnwritten[0]
  const retainedSeed = targetUnwritten[1]
  const newTitle = '人工新增待写章'
  const newSynopsis = '新增后上移，保存并刷新仍保持此梗概。'
  const premiseBefore = outlineBefore.premise
  const premiseDraftMarker = premiseBefore + '【未保存总纲草稿】'
  const premisePendingMarker = premiseBefore + '【保存请求期间继续编辑】'
  await fillInput('textarea[aria-label="总纲内容"]', premiseDraftMarker)

  const outlineActions = await js('(() => {const t=document.body.innerText;return {addVolume:t.includes("新增卷"),addChapter:t.includes("新增章节"),removeChapter:t.includes("删除章节"),removeVolume:t.includes("删除卷"),save:t.includes("保存未写大纲")}})()')
  const lockedActions = await js('(() => {const row=[...document.querySelectorAll("[data-outline-chapter-index]")].find(x=>x.getAttribute("data-outline-chapter-index")==="1");const controls=row?[...row.querySelectorAll("button")].filter(b=>/上移|下移|删除/.test(b.getAttribute("aria-label")||b.innerText)):[];return {row:!!row,protected:controls.length===0||controls.every(b=>b.disabled)}})()')
  const earlyWrittenVolume = outlineBefore.volumes.find(volume => volume.volumeIndex < targetVolume?.volumeIndex && volume.synopses.some(s => s.chapterIndex <= statusBeforeOutlineEdit.currentChapter))
  const addBoundary = await js('(() => {const buttons=[...document.querySelectorAll("button")];const early=buttons.find(x=>x.getAttribute("aria-label")===' + JSON.stringify('新增章节到卷' + (earlyWrittenVolume?.volumeIndex || '')) + ');const last=buttons.find(x=>x.getAttribute("aria-label")===' + JSON.stringify('新增章节到卷' + (targetVolume?.volumeIndex || '')) + ');return {earlyExists:!!early,earlyDisabled:!!early&&early.disabled,lastExists:!!last,lastEnabled:!!last&&!last.disabled}})()')
  const addClicked = !!targetVolume && await js('(() => {const b=[...document.querySelectorAll("button")].find(x=>x.getAttribute("aria-label")===' + JSON.stringify('新增章节到卷' + (targetVolume?.volumeIndex || '')) + ');if(!b||b.disabled)return false;b.click();return true})()')
  if (addClicked) await wait(1)
  const newRowPresent = await js('!!document.querySelector("[data-outline-new-chapter]")')
  if (newRowPresent) {
    await fillInput('[data-outline-new-chapter] input', newTitle)
    await fillInput('[data-outline-new-chapter] textarea', newSynopsis)
  }
  const moveClicked = newRowPresent && await js('(() => {const row=document.querySelector("[data-outline-new-chapter]");const b=[...(row?.querySelectorAll("button")||[])].find(x=>(x.getAttribute("aria-label")||"").startsWith("上移第"));if(!b||b.disabled)return false;b.click();return true})()')

  // Delay the real response so the page can prove that every structure-draft input is
  // locked for the whole request. The independently edited premise remains editable.
  const delayInstalled = await js('(() => {const original=window.fetch;window.__auditOutlineFetch=original;window.fetch=(input,init)=>{const url=String(input);if(url.includes("/outline")&&String(init?.method||"").toUpperCase()==="PUT"){window.fetch=original;return original(input,init).then(response=>new Promise(resolve=>setTimeout(()=>resolve(response),3500)))}return original(input,init)};return true})()')
  const moveSaveClicked = await js('(() => {const b=[...document.querySelectorAll("button")].find(x=>x.getAttribute("aria-label")==="保存未写大纲");if(!b||b.disabled)return false;b.click();return true})()')
  await wait(1)
  const pendingLocked = await js('(() => {const structureInputs=[...document.querySelectorAll("[data-outline-volume-id] input,[data-outline-chapter-id] input,[data-outline-chapter-id] textarea")];const structureButtons=[...document.querySelectorAll("button")].filter(b=>{const a=b.getAttribute("aria-label")||"";return a==="新增卷"||a==="保存未写大纲"||/^(新增章节到卷|上移卷|下移卷|删除卷|上移第|下移第|删除第)/.test(a)});const premise=document.querySelector("textarea[aria-label=总纲内容]");const premiseSave=[...document.querySelectorAll("button")].find(b=>b.getAttribute("aria-label")==="保存总纲");return {inputs:structureInputs.length>0&&structureInputs.every(x=>x.disabled),buttons:structureButtons.length>0&&structureButtons.every(x=>x.disabled),premiseEditable:!!premise&&!premise.disabled,premiseSaveLocked:!!premiseSave&&premiseSave.disabled}})()')
  if (pendingLocked.premiseEditable) await fillInput('textarea[aria-label="总纲内容"]', premisePendingMarker)
  await wait(4)
  await js('(() => {if(window.__auditOutlineFetch){window.fetch=window.__auditOutlineFetch;delete window.__auditOutlineFetch}return true})()')
  const outlineAfterMove = await pageJson('/api/projects/$P_REWRITE/outline')
  const movePersistedOrder = outlineAfterMove.volumes.flatMap(volume => volume.synopses.map(synopsis => ({
    id: synopsis.id, volumeIndex: volume.volumeIndex, chapterIndex: synopsis.chapterIndex,
    title: synopsis.title, synopsis: synopsis.synopsis,
  })))
  const moveNewPosition = movePersistedOrder.findIndex(item => item.title === newTitle && item.synopsis === newSynopsis)
  const moveRetainedPosition = movePersistedOrder.findIndex(item => item.title === retainedSeed?.title)
  const moveSavedInOrder = moveNewPosition >= 0 && moveRetainedPosition === moveNewPosition + 1 &&
    movePersistedOrder.every((item, index) => item.chapterIndex === index + 1)
  const premiseAfterMove = await js('document.querySelector("textarea[aria-label=总纲内容]")?.value')
  const premiseDraftRetained = premiseAfterMove === premisePendingMarker && outlineAfterMove.premise === premiseBefore

  await go('/#/project/$P_REWRITE')
  await openTab('大纲')
  const outlineAfterMoveReload = await pageJson('/api/projects/$P_REWRITE/outline')
  const moveReloadedOrder = outlineAfterMoveReload.volumes.flatMap(volume => volume.synopses.map(synopsis => ({
    id: synopsis.id, volumeIndex: volume.volumeIndex, chapterIndex: synopsis.chapterIndex,
    title: synopsis.title, synopsis: synopsis.synopsis,
  })))
  const moveReloadedExactly = JSON.stringify(movePersistedOrder) === JSON.stringify(moveReloadedOrder)
  const deleteClicked = !!deletedSeed && await js('(() => {const row=[...document.querySelectorAll("[data-outline-chapter-id]")].find(x=>[...x.querySelectorAll("input")].some(i=>i.value===' + JSON.stringify(deletedSeed?.title || '') + '));const b=[...(row?.querySelectorAll("button")||[])].find(x=>(x.getAttribute("aria-label")||"").startsWith("删除第"));if(!b||b.disabled)return false;b.click();return true})()')

  // Keep the UI draft intact while a real server-side validation error travels through api.request
  // and is surfaced by Ant Design message.error. The following retry sends the untouched valid draft.
  const errorInterceptorInstalled = await js('(() => {const original=window.fetch;window.__auditOutlineFetch=original;window.fetch=(input,init)=>{const url=String(input);if(url.includes("/outline")&&String(init?.method||"").toUpperCase()==="PUT"){window.fetch=original;const body=JSON.parse(init.body);const all=body.volumes.flatMap(v=>v.synopses);if(all.length>1)all[1].chapterIndex=all[0].chapterIndex;return original(input,{...init,body:JSON.stringify(body)})}return original(input,init)};return true})()')
  const rejectedSaveClicked = await js('(() => {const b=[...document.querySelectorAll("button")].find(x=>x.getAttribute("aria-label")==="保存未写大纲");if(!b||b.disabled)return false;b.click();return true})()')
  await wait(2)
  const serverErrorVisible = await js('!![...document.querySelectorAll(".ant-message-error")].find(x=>x.innerText.includes("章号必须唯一"))')
  await js('(() => {if(window.__auditOutlineFetch){window.fetch=window.__auditOutlineFetch;delete window.__auditOutlineFetch}return true})()')
  const saveClicked = await js('(() => {const b=[...document.querySelectorAll("button")].find(x=>x.getAttribute("aria-label")==="保存未写大纲");if(!b||b.disabled)return false;b.click();return true})()')
  await wait(3)
  const outlineAfterSave = await pageJson('/api/projects/$P_REWRITE/outline')
  const persistedOrder = outlineAfterSave.volumes.flatMap(volume => volume.synopses.map(synopsis => ({
    id: synopsis.id, volumeIndex: volume.volumeIndex, chapterIndex: synopsis.chapterIndex,
    title: synopsis.title, synopsis: synopsis.synopsis,
  })))
  await go('/#/project/$P_REWRITE')
  await openTab('大纲')
  const outlineAfterReload = await pageJson('/api/projects/$P_REWRITE/outline')
  const reloadedOrder = outlineAfterReload.volumes.flatMap(volume => volume.synopses.map(synopsis => ({
    id: synopsis.id, volumeIndex: volume.volumeIndex, chapterIndex: synopsis.chapterIndex,
    title: synopsis.title, synopsis: synopsis.synopsis,
  })))
  const newPosition = reloadedOrder.findIndex(item => item.title === newTitle && item.synopsis === newSynopsis)
  const retainedPosition = reloadedOrder.findIndex(item => item.title === retainedSeed?.title)
  const outlinePersisted = persistedOrder.length === seedOrder.length &&
    JSON.stringify(persistedOrder) === JSON.stringify(reloadedOrder) &&
    reloadedOrder.every((item, index) => item.chapterIndex === index + 1) &&
    !reloadedOrder.some(item => item.title === deletedSeed?.title) &&
    newPosition >= 0 && retainedPosition === newPosition + 1
  const refreshedOutlineFields = await js('(() => {const row=[...document.querySelectorAll("[data-outline-chapter-id]")].find(x=>[...x.querySelectorAll("input")].some(i=>i.value===' + JSON.stringify(newTitle) + '));const values=row?[...row.querySelectorAll("input,textarea")].map(x=>x.value):[];return {row:!!row,title:values.includes(' + JSON.stringify(newTitle) + '),synopsis:values.includes(' + JSON.stringify(newSynopsis) + ')}})()')
  await openTab('章节管理')
  const chapterActions = await js('(() => {const t=document.body.innerText;return {add:t.includes("新增章节"),directEdit:t.includes("编辑正文"),save:t.includes("保存正文")}})()')
  const pass = worldTypeOpened && worldTypeSelected && worldSaveClicked && characterSaveClicked &&
    worldPersisted && characterPersisted && Object.values(worldFields).every(Boolean) && Object.values(characterFields).every(Boolean) &&
    !!targetVolume && targetUnwritten.length >= 2 && Object.values(outlineActions).every(Boolean) &&
    lockedActions.row && lockedActions.protected && Object.values(addBoundary).every(Boolean) && addClicked && newRowPresent && moveClicked &&
    delayInstalled && moveSaveClicked && Object.values(pendingLocked).every(Boolean) && moveSavedInOrder && moveReloadedExactly && premiseDraftRetained &&
    deleteClicked && errorInterceptorInstalled &&
    rejectedSaveClicked && serverErrorVisible && saveClicked && outlinePersisted && Object.values(refreshedOutlineFields).every(Boolean) &&
    Object.values(chapterActions).every(Boolean)
  return { pass, worldSave: { worldTypeOpened, worldTypeSelected, worldSaveClicked, persisted: worldPersisted, refreshed: worldFields },
    characterSave: { characterSaveClicked, persisted: characterPersisted, refreshed: characterFields },
    outlineActions: { ...outlineActions, lockedActions, addBoundary, addClicked, newRowPresent, moveClicked,
      delayInstalled, moveSaveClicked, pendingLocked, moveSavedInOrder, moveReloadedExactly, premiseDraftRetained,
      movePersistedOrder, moveReloadedOrder, deleteClicked, rejectedSaveClicked,
      serverErrorVisible, saveClicked, persisted: outlinePersisted, refreshed: refreshedOutlineFields,
      seed: seedOrder, persistedOrder, reloadedOrder }, chapterActions }
})

await runCase('mobile-critical-path', async () => {
  await cdp('Emulation.setDeviceMetricsOverride', { width: 375, height: 667, deviceScaleFactor: 2, mobile: true })
  await go('/')
  const list = await js('(() => {const h=[...document.querySelectorAll("h1")].find(x=>x.innerText.includes("长篇小说写作系统"));const r=h?h.getBoundingClientRect():null;return {titleWidth:r?r.width:0,titleSingleLine:h?getComputedStyle(h).whiteSpace!=="normal"||h.scrollHeight<55:false,overflow:document.documentElement.scrollWidth-window.innerWidth}})()')
  await go('/#/project/$P_REWRITE')
  const workspace = await js('(() => {const trigger=document.querySelector(".ant-layout-sider-zero-width-trigger");const alert=document.querySelector(".ant-alert");const tr=trigger?trigger.getBoundingClientRect():null;const ar=alert?alert.getBoundingClientRect():null;const overlap=!!(tr&&ar&&tr.left<ar.right&&tr.right>ar.left&&tr.top<ar.bottom&&tr.bottom>ar.top);return {triggerFocusable:!!trigger&&trigger.tabIndex>=0,triggerNamed:!!trigger&&(!!trigger.getAttribute("aria-label")||!!trigger.getAttribute("title")),triggerIsButton:!!trigger&&trigger.tagName==="BUTTON",overlap}})()')
  await openTab('章节管理')
  await wait(3)
  const paragraph = await js('(() => {const p=document.querySelector("[data-paragraph-index]");const explicit=[...document.querySelectorAll("button")].some(b=>(b.getAttribute("aria-label")||"").includes("编辑段落"));return {focusable:!!p&&p.tabIndex>=0,named:!!p&&(!!p.getAttribute("aria-label")||!!p.getAttribute("title")),explicitButton:explicit}})()')
  await cdp('Emulation.clearDeviceMetricsOverride')
  const pass = list.overflow <= 0 && list.titleWidth >= 160 && list.titleSingleLine &&
    workspace.triggerFocusable && workspace.triggerNamed && workspace.triggerIsButton && !workspace.overlap &&
    ((paragraph.focusable && paragraph.named) || paragraph.explicitButton)
  return { pass, list, workspace, paragraph }
})

fs.writeFileSync('$DIR/results.json', JSON.stringify({ taskSpace: task.id, results }, null, 2))
await closeTab()
EGOEOF

browser_exit=$?
if [ "$browser_exit" != "0" ] || [ ! -f "$DIR/results.json" ]; then
  echo "ERROR ego-browser execution failed; see $DIR/browser.out"
  exit 2
fi

EXPECTED_JSON=$(for id in "${SELECTED[@]}"; do
  if contains_case "$id" "${KNOWN_FAILURES[@]}"; then echo "$id"; fi
done | python3 -c "import json,sys; print(json.dumps([x.strip() for x in sys.stdin if x.strip()]))")

python3 - "$DIR/results.json" "$DIR/summary.json" "$EXPECT_KNOWN" "$EXPECTED_JSON" <<'PY'
import datetime
import json
import sys

results_path, summary_path, expect_known, expected_raw = sys.argv[1:]
payload = json.load(open(results_path, encoding="utf-8"))
results = payload["results"]
errors = sorted(r["id"] for r in results if r["status"] == "ERROR")
failures = sorted(r["id"] for r in results if r["status"] == "XFAIL")
passes = sorted(r["id"] for r in results if r["status"] == "PASS")
expected = sorted(json.loads(expected_raw))

if expect_known == "1":
    ok = not errors and failures == expected
    mode = "expect-known-failures"
else:
    ok = not errors and not failures
    mode = "strict"

summary = {
    "generatedAt": datetime.datetime.now().astimezone().isoformat(),
    "kind": "product-audit-browser-e2e",
    "tool": "ego-browser",
    "mode": mode,
    "passed": ok,
    "counts": {"pass": len(passes), "xfail": len(failures), "error": len(errors)},
    "passes": passes,
    "failures": failures,
    "errors": errors,
    "expectedFailures": expected,
    "results": results,
}
with open(summary_path, "w", encoding="utf-8") as f:
    json.dump(summary, f, ensure_ascii=False, indent=2)

print(f"PRODUCT-AUDIT-E2E {'PASS' if ok else 'FAIL'} mode={mode} "
      f"pass={len(passes)} xfail={len(failures)} error={len(errors)}")
if failures:
    print("xfail:", " ".join(failures))
if errors:
    print("error:", " ".join(errors))
print("summary:", summary_path)
sys.exit(0 if ok else 1)
PY
