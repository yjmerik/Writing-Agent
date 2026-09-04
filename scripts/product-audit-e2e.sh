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
  quality-evaluation-state
  mobile-critical-path
)

# 测试先行基线：修复某项后，必须从此数组移除对应 ID；XPASS 会使校验失败。
KNOWN_FAILURES=(
  auto-zero-intervention
  interactive-to-auto
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
    quality-evaluation-state) echo "P2-05|P2|质量评价区分未评价、运行、失败和成功零分" ;;
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
P_QUALITY=""
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
  third_volume_id=$(uuidgen | tr '[:upper:]' '[:lower:]')
  fourth_synopsis_id=$(uuidgen | tr '[:upper:]' '[:lower:]')
  fifth_synopsis_id=$(uuidgen | tr '[:upper:]' '[:lower:]')
  sqlite3 "$DIR/novel.db" \
    "INSERT INTO volumes(id,project_id,volume_index,title,summary) VALUES('$second_volume_id','$P_REWRITE',2,'第二卷','第二卷边界测试'); INSERT INTO volumes(id,project_id,volume_index,title,summary) VALUES('$third_volume_id','$P_REWRITE',3,'第三卷','纯未写卷，用于验证定向 Agent 修改'); UPDATE chapter_synopses SET volume_id='$second_volume_id' WHERE project_id='$P_REWRITE' AND chapter_index IN (2,3); INSERT INTO chapter_synopses(id,project_id,volume_id,chapter_index,title,synopsis,writing_notes) VALUES('$fourth_synopsis_id','$P_REWRITE','$second_volume_id',4,'事件4','第4章的场景梗概，用于验证单独移动后立即保存。',NULL); INSERT INTO chapter_synopses(id,project_id,volume_id,chapter_index,title,synopsis,writing_notes) VALUES('$fifth_synopsis_id','$P_REWRITE','$third_volume_id',5,'事件5','第5章的场景梗概，用于验证纯未写卷定向修订。',NULL); UPDATE projects SET current_chapter_index=2 WHERE id='$P_REWRITE';" || exit 2
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

if need_case quality-evaluation-state; then
  P_QUALITY=$(create_project "audit-quality" SOLO 3) || exit 2
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
const characterFingerprint = (entries) => fingerprint((entries || []).map(character => ({
  id: character.id,
  name: character.name,
  role: character.role,
  personality: character.personality,
  ability: character.ability,
  motivation: character.motivation,
  appearance: character.appearance,
  relationships: character.relationships,
  growthArc: character.growthArc,
  speakingStyle: character.speakingStyle,
})).sort((a, b) => String(a.id).localeCompare(String(b.id))))
const chapterFingerprint = (chapter) => fingerprint(chapter ? {
  id: chapter.id,
  chapterIndex: chapter.chapterIndex,
  title: chapter.title,
  content: chapter.content,
  status: chapter.status,
  version: chapter.version,
} : null)
const chapterMetaFingerprint = (chapters) => fingerprint((chapters || []).map(chapter => ({
  id: chapter.id,
  chapterIndex: chapter.chapterIndex,
  title: chapter.title,
  status: chapter.status,
  version: chapter.version,
  wordCount: chapter.wordCount,
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

async function clickButtonInScope(scopeSelector, text, ariaLabel) {
  const source = '(() => {const scope=document.querySelector(' + JSON.stringify(scopeSelector) + ');' +
    'const b=[...(scope?.querySelectorAll("button")||[])].find(x=>' +
    (ariaLabel
      ? '(x.getAttribute("aria-label")||"")===' + JSON.stringify(ariaLabel)
      : 'x.innerText.trim()===' + JSON.stringify(text)) +
    ');if(!b||b.disabled)return false;b.click();return true})()'
  return await js(source)
}

async function generateAgentRevisionPreview(instruction) {
  if (!await modalOpen()) return { generated: false, diffVisible: false }
  await fillInput('textarea[aria-label="修改要求"]', instruction)
  const generated = await js('(() => {const modal=[...document.querySelectorAll(".ant-modal-wrap")].find(x=>getComputedStyle(x).display!=="none");const b=[...modal.querySelectorAll("button")].find(x=>x.innerText.trim()==="生成预览");if(!b||b.disabled)return false;b.click();return true})()')
  if (generated) await wait(3)
  const diffVisible = generated && await js('!!document.querySelector("[data-revision-side=before]")&&!!document.querySelector("[data-revision-side=after]")')
  return { generated, diffVisible }
}

async function finishAgentRevision(action) {
  if (!await modalOpen()) return false
  const clicked = await js('(() => {const modal=[...document.querySelectorAll(".ant-modal-wrap")].find(x=>getComputedStyle(x).display!=="none");let b=[...modal.querySelectorAll("button")].find(x=>x.innerText.trim()===' + JSON.stringify(action) + ');if(!b&&' + JSON.stringify(action) + '==="取消")b=modal.querySelector(".ant-modal-close");if(!b||b.disabled)return false;b.click();return true})()')
  if (clicked) await wait(action === '确认应用' ? 3 : 1)
  return clicked
}

function selectedObjectOnlyChanged(before, after, targetId) {
  const selectedBefore = (before || []).find(item => item.id === targetId)
  const selectedAfter = (after || []).find(item => item.id === targetId)
  const otherBefore = (before || []).filter(item => item.id !== targetId)
    .sort((a, b) => String(a.id).localeCompare(String(b.id)))
  const otherAfter = (after || []).filter(item => item.id !== targetId)
    .sort((a, b) => String(a.id).localeCompare(String(b.id)))
  return fingerprint(selectedBefore) !== fingerprint(selectedAfter) &&
    fingerprint(otherBefore) === fingerprint(otherAfter)
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
  const chapterActions = await js('(() => {const t=document.body.innerText;return {add:t.includes("新增章节"),directEdit:t.includes("编辑正文"),history:t.includes("版本记录")}})()')
  const draftTitle = '人工新增章节草稿'
  const draftContent = '人工草稿初稿，等待作者继续完成。'
  const editedTitle = '人工编辑后的章节'
  const editedContent = '人工编辑正文标记。'.repeat(80)
  const addOpened = chapterActions.add && await clickButtonContaining('新增章节')
  if (addOpened) {
    await fillInput('input[aria-label="新增章节标题"]', draftTitle)
    await fillInput('textarea[aria-label="新增章节正文"]', draftContent)
  }
  const createSubmitted = addOpened && await clickButtonContaining('创建草稿')
  if (createSubmitted) await wait(3)
  const chaptersAfterCreate = await pageJson('/api/projects/$P_REWRITE/chapters?page=0&size=1000')
  const created = (chaptersAfterCreate.chapters || []).find(chapter => chapter.title === draftTitle)
  const createdSelector = created ? '[data-chapter-index="' + created.chapterIndex + '"]' : ''
  const createdVisible = !!created && await js('!!document.querySelector(' + JSON.stringify(createdSelector) + ')')

  const editOpened = !!created && await clickButtonContaining('编辑正文')
  const saveActionVisible = editOpened && await js('(() => {const modal=[...document.querySelectorAll(".ant-modal-wrap")].find(x=>getComputedStyle(x).display!=="none");return !!modal&&[...modal.querySelectorAll("button")].some(x=>x.innerText.includes("保存正文"))})()')
  if (editOpened) {
    await fillInput('input[aria-label="章节标题"]', editedTitle)
    await fillInput('textarea[aria-label="章节正文"]', editedContent)
  }
  const editSubmitted = editOpened && await clickButtonContaining('保存正文')
  if (editSubmitted) await wait(4)
  const edited = created ? await pageJson('/api/projects/$P_REWRITE/chapters/' + created.chapterIndex) : null
  const editPersisted = !!edited && edited.title === editedTitle && edited.content === editedContent && edited.version > created.version

  if (edited) {
    await go('/#/project/$P_REWRITE')
    await openTab('章节管理')
    const editedSelector = '[data-chapter-index="' + edited.chapterIndex + '"]'
    await js('(() => {const row=document.querySelector(' + JSON.stringify(editedSelector) + ');if(!row)return false;row.click();return true})()')
    await wait(2)
  }
  const editReloaded = !!edited && await js('document.body.innerText.includes(' + JSON.stringify(editedTitle) + ')&&document.body.innerText.includes(' + JSON.stringify('人工编辑正文标记。') + ')')
  const historyOpened = !!edited && await clickButtonContaining('版本记录')
  if (historyOpened) await wait(1)
  const historySelector = created ? '[data-chapter-version="' + created.version + '"]' : ''
  const historyState = historyOpened && await js('(() => {const row=document.querySelector(' + JSON.stringify(historySelector) + ');const text=row?.innerText||"";return {row:!!row,oldTitle:text.includes(' + JSON.stringify(draftTitle) + '),compare:!![...(row?.querySelectorAll("button")||[])].find(x=>x.innerText.includes("比较")),restore:!![...(row?.querySelectorAll("button")||[])].find(x=>x.innerText.includes("回退到此版本"))}})()')
  const compareClicked = historyState?.compare && await js('(() => {const row=document.querySelector(' + JSON.stringify(historySelector) + ');const b=[...(row?.querySelectorAll("button")||[])].find(x=>x.innerText.includes("比较"));if(!b)return false;b.click();return true})()')
  const comparison = compareClicked && await js('(() => {const oldText=document.querySelector("pre[data-compare-side=old]")?.innerText||"";const currentText=document.querySelector("pre[data-compare-side=current]")?.innerText||"";return {old:oldText.includes(' + JSON.stringify(draftContent) + '),current:currentText.includes(' + JSON.stringify('人工编辑正文标记。') + ')}})()')
  const restoreClicked = historyState?.restore && await js('(() => {const row=document.querySelector(' + JSON.stringify(historySelector) + ');const b=[...(row?.querySelectorAll("button")||[])].find(x=>x.innerText.includes("回退到此版本"));if(!b)return false;b.click();return true})()')
  if (restoreClicked) await wait(1)
  const restoreConfirmed = restoreClicked && await clickButtonContaining('确认回退')
  if (restoreConfirmed) await wait(4)
  const restored = created ? await pageJson('/api/projects/$P_REWRITE/chapters/' + created.chapterIndex) : null
  const restorePersisted = !!restored && restored.title === draftTitle && restored.content === draftContent && restored.version > edited.version
  if (restored) {
    await go('/#/project/$P_REWRITE')
    await openTab('章节管理')
    const restoredSelector = '[data-chapter-index="' + restored.chapterIndex + '"]'
    await js('(() => {const row=document.querySelector(' + JSON.stringify(restoredSelector) + ');if(!row)return false;row.click();return true})()')
    await wait(2)
  }
  const restoreReloaded = !!restored && await js('document.body.innerText.includes(' + JSON.stringify(draftContent) + ')')

  const conflictOpened = !!restored && await clickButtonContaining('编辑正文')
  const externalTitle = '外部并发更新标题'
  const externalContent = '外部并发更新正文标记。'.repeat(60)
  const externalUpdate = conflictOpened && await js('(() => {const x=new XMLHttpRequest();x.open("PUT",' + JSON.stringify('/api/projects/$P_REWRITE/chapters/' + (restored?.chapterIndex || 0)) + ',false);x.setRequestHeader("Content-Type","application/json");x.send(JSON.stringify({expectedVersion:' + (restored?.version || 0) + ',title:' + JSON.stringify(externalTitle) + ',content:' + JSON.stringify(externalContent) + '}));return {status:x.status,body:x.responseText?JSON.parse(x.responseText):null}})()')
  if (conflictOpened) {
    await fillInput('input[aria-label="章节标题"]', '会产生冲突的本地标题')
    await fillInput('textarea[aria-label="章节正文"]', '会产生冲突的本地正文。'.repeat(60))
  }
  const conflictSubmitted = conflictOpened && await clickButtonContaining('保存正文')
  if (conflictSubmitted) await wait(4)
  const conflictState = conflictSubmitted && await js('(() => {const modal=[...document.querySelectorAll(".ant-modal-wrap")].find(x=>getComputedStyle(x).display!=="none"&&x.innerText.includes("编辑正文"));return {modalOpen:!!modal,message:document.body.innerText.includes("章节已被更新，请重新确认"),title:modal?.querySelector("input[aria-label=章节标题]")?.value||"",content:modal?.querySelector("textarea[aria-label=章节正文]")?.value||""}})()')
  const afterConflict = restored ? await pageJson('/api/projects/$P_REWRITE/chapters/' + restored.chapterIndex) : null
  const conflictHandled = externalUpdate?.status === 200 && conflictState?.modalOpen && conflictState?.message &&
    conflictState.title === externalTitle && conflictState.content === externalContent &&
    afterConflict?.title === externalTitle && afterConflict?.content === externalContent && afterConflict.version > restored.version
  const conflictClosed = !conflictState?.modalOpen || await finishAgentRevision('取消')

  // P1-06: all five object levels share the same preview-before-apply contract.
  // Preview and cancel must be read-only; a confirmed candidate may change only its selected object.
  await go('/#/project/$P_REWRITE')
  await openTab('世界观与人物')
  const agentWorldBefore = await pageJson('/api/projects/$P_REWRITE/worldbuilding')
  const agentWorldTarget = agentWorldBefore[0]
  const agentWorldScope = '[data-world-entry-id="' + (agentWorldTarget?.id || '') + '"]'
  const agentWorldCancelOpened = !!agentWorldTarget && await clickButtonInScope(agentWorldScope, '让 Agent 修改')
  const agentWorldCancelPreview = agentWorldCancelOpened
    ? await generateAgentRevisionPreview('只补充一条可验证的经济规则')
    : { generated: false, diffVisible: false }
  const agentWorldAfterCancelPreview = await pageJson('/api/projects/$P_REWRITE/worldbuilding')
  const agentWorldPreviewReadOnly = worldFingerprint(agentWorldBefore) === worldFingerprint(agentWorldAfterCancelPreview)
  const agentWorldCancelled = agentWorldCancelPreview.generated && await finishAgentRevision('取消')
  const agentWorldAfterCancel = await pageJson('/api/projects/$P_REWRITE/worldbuilding')
  const agentWorldCancelReadOnly = worldFingerprint(agentWorldBefore) === worldFingerprint(agentWorldAfterCancel)
  const agentWorldOpened = !!agentWorldTarget && await clickButtonInScope(agentWorldScope, '让 Agent 修改')
  const agentWorldPreview = agentWorldOpened
    ? await generateAgentRevisionPreview('只补充一条可验证的经济规则')
    : { generated: false, diffVisible: false }
  const agentWorldApplied = agentWorldPreview.diffVisible && await finishAgentRevision('确认应用')
  const agentWorldAfter = await pageJson('/api/projects/$P_REWRITE/worldbuilding')
  const agentWorldAfterTarget = agentWorldAfter.find(item => item.id === agentWorldTarget?.id)
  const agentWorldScoped = agentWorldApplied && agentWorldAfterTarget?.content !== agentWorldTarget?.content &&
    worldFingerprint(agentWorldBefore.filter(item => item.id !== agentWorldTarget?.id)) ===
      worldFingerprint(agentWorldAfter.filter(item => item.id !== agentWorldTarget?.id))

  const agentCharactersBefore = await pageJson('/api/projects/$P_REWRITE/characters')
  const agentCharacterTarget = agentCharactersBefore[0]
  const agentCharacterScope = '[data-character-id="' + (agentCharacterTarget?.id || '') + '"]'
  const agentCharacterOpened = !!agentCharacterTarget && await clickButtonInScope(agentCharacterScope, '让 Agent 修改')
  const agentCharacterPreview = agentCharacterOpened
    ? await generateAgentRevisionPreview('只强化这个人物的调查动机')
    : { generated: false, diffVisible: false }
  const agentCharactersAfterPreview = await pageJson('/api/projects/$P_REWRITE/characters')
  const agentCharacterPreviewReadOnly = characterFingerprint(agentCharactersBefore) === characterFingerprint(agentCharactersAfterPreview)
  const agentCharacterApplied = agentCharacterPreview.diffVisible && await finishAgentRevision('确认应用')
  const agentCharactersAfter = await pageJson('/api/projects/$P_REWRITE/characters')
  const agentCharacterAfterTarget = agentCharactersAfter.find(item => item.id === agentCharacterTarget?.id)
  const agentCharacterScoped = agentCharacterApplied &&
    agentCharacterAfterTarget?.motivation !== agentCharacterTarget?.motivation &&
    characterFingerprint(agentCharactersBefore.filter(item => item.id !== agentCharacterTarget?.id)) ===
      characterFingerprint(agentCharactersAfter.filter(item => item.id !== agentCharacterTarget?.id))

  await openTab('大纲')
  const agentStatus = await pageJson('/api/projects/$P_REWRITE/status')
  const agentOutlineBefore = await pageJson('/api/projects/$P_REWRITE/outline')
  const agentVolumeTarget = agentOutlineBefore.volumes.find(volume =>
    volume.synopses.every(synopsis => synopsis.chapterIndex > agentStatus.currentChapter))
  const agentVolumeScope = '[data-outline-volume-id="' + (agentVolumeTarget?.id || '') + '"]'
  const agentVolumeOpened = !!agentVolumeTarget && await clickButtonInScope(agentVolumeScope, '让 Agent 修改')
  const agentVolumePreview = agentVolumeOpened
    ? await generateAgentRevisionPreview('只调整本卷标题和简介')
    : { generated: false, diffVisible: false }
  const agentOutlineAfterPreview = await pageJson('/api/projects/$P_REWRITE/outline')
  const agentVolumePreviewReadOnly = fingerprint(agentOutlineBefore) === fingerprint(agentOutlineAfterPreview)
  const agentVolumeApplied = agentVolumePreview.diffVisible && await finishAgentRevision('确认应用')
  const agentOutlineAfter = await pageJson('/api/projects/$P_REWRITE/outline')
  const agentVolumeScoped = agentVolumeApplied &&
    agentOutlineBefore.premise === agentOutlineAfter.premise &&
    selectedObjectOnlyChanged(agentOutlineBefore.volumes, agentOutlineAfter.volumes, agentVolumeTarget?.id)

  await openTab('章节管理')
  await wait(2)
  const agentChapterBefore = await pageJson('/api/projects/$P_REWRITE/chapters/1')
  const agentChapterListBefore = await pageJson('/api/projects/$P_REWRITE/chapters?page=0&size=1000')
  const agentChapterSelector = '[data-chapter-index="1"]'
  await js('(() => {const row=document.querySelector(' + JSON.stringify(agentChapterSelector) + ');if(!row)return false;row.click();return true})()')
  await wait(2)
  const agentChapterOpened = await clickButtonInScope('body', '', '让 Agent 修改第1章')
  const agentChapterPreview = agentChapterOpened
    ? await generateAgentRevisionPreview('只增强本章的证据推理')
    : { generated: false, diffVisible: false }
  const agentChapterAfterPreview = await pageJson('/api/projects/$P_REWRITE/chapters/1')
  const agentChapterPreviewReadOnly = chapterFingerprint(agentChapterBefore) === chapterFingerprint(agentChapterAfterPreview)
  const agentChapterApplied = agentChapterPreview.diffVisible && await finishAgentRevision('确认应用')
  const agentChapterAfter = await pageJson('/api/projects/$P_REWRITE/chapters/1')
  const agentChapterListAfter = await pageJson('/api/projects/$P_REWRITE/chapters?page=0&size=1000')
  const agentChapterScoped = agentChapterApplied && chapterFingerprint(agentChapterBefore) !== chapterFingerprint(agentChapterAfter) &&
    chapterMetaFingerprint(agentChapterListBefore.chapters.filter(item => item.id !== agentChapterBefore?.id)) ===
      chapterMetaFingerprint(agentChapterListAfter.chapters.filter(item => item.id !== agentChapterBefore?.id))

  const agentPassageBefore = agentChapterAfter
  const agentPassageButton = await js('(() => {const b=[...document.querySelectorAll("button")].find(x=>(x.getAttribute("aria-label")||"").startsWith("让 Agent 修改第 ")&&(x.getAttribute("aria-label")||"").endsWith(" 段"));return b?.getAttribute("aria-label")||""})()')
  const passageMatch = agentPassageButton.match(/第 (\d+) 段/)
  const passageLineIndex = passageMatch ? Number(passageMatch[1]) - 1 : -1
  const agentPassageOpened = !!agentPassageButton && await clickButtonInScope('body', '', agentPassageButton)
  const agentPassagePreview = agentPassageOpened
    ? await generateAgentRevisionPreview('只增强这一段的现场感')
    : { generated: false, diffVisible: false }
  const agentPassageAfterPreview = await pageJson('/api/projects/$P_REWRITE/chapters/1')
  const agentPassagePreviewReadOnly = chapterFingerprint(agentPassageBefore) === chapterFingerprint(agentPassageAfterPreview)
  const agentPassageApplied = agentPassagePreview.diffVisible && await finishAgentRevision('确认应用')
  const agentPassageAfter = await pageJson('/api/projects/$P_REWRITE/chapters/1')
  const passageBeforeLines = (agentPassageBefore?.content || '').split('\n')
  const passageAfterLines = (agentPassageAfter?.content || '').split('\n')
  const agentPassageScoped = agentPassageApplied && passageLineIndex >= 0 &&
    agentPassageAfter?.id === agentPassageBefore?.id && agentPassageAfter?.title === agentPassageBefore?.title &&
    passageBeforeLines.length === passageAfterLines.length &&
    passageBeforeLines[passageLineIndex] !== passageAfterLines[passageLineIndex] &&
    passageBeforeLines.every((line, index) => index === passageLineIndex || line === passageAfterLines[index])

  const agentRevisions = {
    conflictClosed,
    world: { opened: agentWorldOpened, preview: agentWorldPreview, previewReadOnly: agentWorldPreviewReadOnly,
      cancelOpened: agentWorldCancelOpened, cancelPreview: agentWorldCancelPreview, cancelled: agentWorldCancelled,
      cancelReadOnly: agentWorldCancelReadOnly, applied: agentWorldApplied, scoped: agentWorldScoped },
    character: { opened: agentCharacterOpened, preview: agentCharacterPreview,
      previewReadOnly: agentCharacterPreviewReadOnly, applied: agentCharacterApplied, scoped: agentCharacterScoped },
    volume: { target: agentVolumeTarget?.id, opened: agentVolumeOpened, preview: agentVolumePreview,
      previewReadOnly: agentVolumePreviewReadOnly, applied: agentVolumeApplied, scoped: agentVolumeScoped },
    chapter: { opened: agentChapterOpened, preview: agentChapterPreview,
      previewReadOnly: agentChapterPreviewReadOnly, applied: agentChapterApplied, scoped: agentChapterScoped },
    passage: { button: agentPassageButton, opened: agentPassageOpened, preview: agentPassagePreview,
      previewReadOnly: agentPassagePreviewReadOnly, applied: agentPassageApplied, scoped: agentPassageScoped },
  }
  const agentRevisionsPass = agentRevisions.conflictClosed &&
    agentRevisions.world.cancelOpened && agentRevisions.world.cancelPreview.diffVisible &&
    agentRevisions.world.cancelled && agentRevisions.world.cancelReadOnly &&
    ['world', 'character', 'volume', 'chapter', 'passage'].every(level => {
      const state = agentRevisions[level]
      return state.opened && state.preview.generated && state.preview.diffVisible &&
        state.previewReadOnly && state.applied && state.scoped
    })
  const pass = worldTypeOpened && worldTypeSelected && worldSaveClicked && characterSaveClicked &&
    worldPersisted && characterPersisted && Object.values(worldFields).every(Boolean) && Object.values(characterFields).every(Boolean) &&
    !!targetVolume && targetUnwritten.length >= 2 && Object.values(outlineActions).every(Boolean) &&
    lockedActions.row && lockedActions.protected && Object.values(addBoundary).every(Boolean) && addClicked && newRowPresent && moveClicked &&
    delayInstalled && moveSaveClicked && Object.values(pendingLocked).every(Boolean) && moveSavedInOrder && moveReloadedExactly && premiseDraftRetained &&
    deleteClicked && errorInterceptorInstalled &&
    rejectedSaveClicked && serverErrorVisible && saveClicked && outlinePersisted && Object.values(refreshedOutlineFields).every(Boolean) &&
    Object.values(chapterActions).every(Boolean) && addOpened && createSubmitted && createdVisible &&
    editOpened && saveActionVisible && editSubmitted && editPersisted && editReloaded && historyOpened &&
    Object.values(historyState || {}).every(Boolean) && compareClicked && Object.values(comparison || {}).every(Boolean) &&
    restoreClicked && restoreConfirmed && restorePersisted && restoreReloaded && conflictHandled && agentRevisionsPass
  return { pass, worldSave: { worldTypeOpened, worldTypeSelected, worldSaveClicked, persisted: worldPersisted, refreshed: worldFields },
    characterSave: { characterSaveClicked, persisted: characterPersisted, refreshed: characterFields },
    outlineActions: { ...outlineActions, lockedActions, addBoundary, addClicked, newRowPresent, moveClicked,
      delayInstalled, moveSaveClicked, pendingLocked, moveSavedInOrder, moveReloadedExactly, premiseDraftRetained,
      movePersistedOrder, moveReloadedOrder, deleteClicked, rejectedSaveClicked,
      serverErrorVisible, saveClicked, persisted: outlinePersisted, refreshed: refreshedOutlineFields,
      seed: seedOrder, persistedOrder, reloadedOrder },
    chapterActions: { ...chapterActions, save: saveActionVisible, addOpened, createSubmitted, createdVisible,
      createdVersion: created?.version, editOpened, editSubmitted, editPersisted, editReloaded,
      historyOpened, historyState, compareClicked, comparison, restoreClicked, restoreConfirmed,
      restoredVersion: restored?.version, restorePersisted, restoreReloaded, externalUpdateStatus: externalUpdate?.status,
      conflictSubmitted, conflictState, afterConflictVersion: afterConflict?.version, conflictHandled },
    agentRevisions }
})

await runCase('quality-evaluation-state', async () => {
  await gotoAndWait(base + '/?qualityAuditRun=' + Date.now() + '#/project/$P_QUALITY', { timeout: 30, settle: 1 })
  await wait(2)
  await openTab('质量评价')
  const initial = await js('(() => {const body=document.body.innerText;return {empty:body.includes("尚未评价"),scoreAbsent:!body.includes("总评分："),noIssuesAbsent:!body.includes("未发现问题")}})()')

  await js('(() => {const original=window.fetch.bind(window);window.__qualityOriginalFetch=original;window.__qualityFailedOnce=false;window.__qualityOriginalAlert=window.alert;window.__qualityNativeAlertCalled=false;window.alert=()=>{window.__qualityNativeAlertCalled=true};window.fetch=(input,init)=>{const url=typeof input==="string"?input:input.url;if(url.endsWith("/evaluate")&&!window.__qualityFailedOnce){window.__qualityFailedOnce=true;return new Promise(resolve=>setTimeout(()=>resolve(new Response(JSON.stringify({error:"确定性评价故障"}),{status:500,headers:{"Content-Type":"application/json"}})),1200))}return original(input,init)};return true})()')
  const started = await clickButtonInScope('body', '🔍 开始质量评价', '')
  await wait(0.3)
  const running = await js('(() => {const body=document.body.innerText;const button=[...document.querySelectorAll("button")].find(b=>b.innerText.includes("AI 评审中"));return {busy:!!button&&button.disabled,spin:body.includes("AI 正在阅读全文并评审"),scoreAbsent:!body.includes("总评分：")}})()')
  await wait(1.5)
  const nativeAlert = await js('!!window.__qualityNativeAlertCalled')
  const failed = await js('(() => {const body=document.body.innerText;const retry=[...document.querySelectorAll("button")].find(b=>b.getAttribute("aria-label")==="重试质量评价");return {inline:body.includes("评价失败")&&body.includes("确定性评价故障"),retry:!!retry,scoreAbsent:!body.includes("总评分："),noIssuesAbsent:!body.includes("未发现问题")}})()')
  const retried = failed.retry && await clickButtonInScope('body', '', '重试质量评价')
  if (retried) await wait(2)
  const succeeded = await js('(() => {const body=document.body.innerText;return {completed:body.includes("评价完成"),zeroScore:body.includes("总评分：")&&body.includes("0")&&body.includes("/ 10"),noIssues:body.includes("未发现问题"),notInitial:!body.includes("尚未评价")}})()')
  await js('(() => {if(window.__qualityOriginalAlert)window.alert=window.__qualityOriginalAlert;return true})()')
  const pass = initial.empty && initial.scoreAbsent && initial.noIssuesAbsent && started &&
    running.busy && running.spin && running.scoreAbsent && !nativeAlert && failed.inline && failed.retry &&
    failed.scoreAbsent && failed.noIssuesAbsent && retried && succeeded.completed && succeeded.zeroScore &&
    succeeded.noIssues && succeeded.notInitial
  return { pass, initial, started, running, nativeAlert, failed, retried, succeeded }
})

await runCase('mobile-critical-path', async () => {
  const pressKey = async (key, code, keyCode) => {
    const down = { type: 'keyDown', key, code, windowsVirtualKeyCode: keyCode, nativeVirtualKeyCode: keyCode }
    if (code === 'Enter') {
      down.text = '\r'
      down.unmodifiedText = '\r'
    }
    await cdp('Input.dispatchKeyEvent', down)
    await cdp('Input.dispatchKeyEvent', { type: 'keyUp', key, code, windowsVirtualKeyCode: keyCode, nativeVirtualKeyCode: keyCode })
  }
  const marker = Date.now()
  await cdp('Emulation.setDeviceMetricsOverride', { width: 375, height: 812, deviceScaleFactor: 2, mobile: true })
  await gotoAndWait(base + '/?mobileAuditRun=' + marker + '#/', { timeout: 30, settle: 1 })
  await wait(2)
  const list = await js('(() => {const h=document.querySelector("[data-project-list-title]");const header=document.querySelector("[data-project-list-header]");const actions=document.querySelector("[data-project-list-actions]");const buttons=actions?[...actions.querySelectorAll("button")]:[];const r=h?h.getBoundingClientRect():null;const hr=header?header.getBoundingClientRect():null;const ar=actions?actions.getBoundingClientRect():null;const within=x=>!!x&&x.left>=-0.5&&x.right<=window.innerWidth+0.5;const overlap=!!(r&&ar&&r.left<ar.right&&r.right>ar.left&&r.top<ar.bottom&&r.bottom>ar.top);return {titleWidth:r?r.width:0,titleSingleLine:!!h&&h.scrollHeight<=h.clientHeight+1,overflow:document.documentElement.scrollWidth-window.innerWidth,headerWithin:within(hr),actionsWithin:within(ar),actionsBelow:!!(r&&ar)&&!overlap&&ar.top>=r.bottom+7,primaryReachable:buttons.length>=2&&buttons.every(b=>{const x=b.getBoundingClientRect();return !b.disabled&&within(x)&&x.height>=43})}})()')
  await gotoAndWait(base + '/?mobileAuditRun=' + marker + '#/project/$P_REWRITE', { timeout: 30, settle: 1 })
  await wait(2)
  const workspaceInitial = await js('(() => {const trigger=document.querySelector("button[data-sidebar-toggle]");const alert=document.querySelector(".ant-layout-content .ant-alert");const tr=trigger?trigger.getBoundingClientRect():null;const ar=alert?alert.getBoundingClientRect():null;const overlap=!!(tr&&ar&&tr.left<ar.right&&tr.right>ar.left&&tr.top<ar.bottom&&tr.bottom>ar.top);return {triggerFocusable:!!trigger&&trigger.tabIndex>=0,triggerNamed:!!trigger&&!!trigger.getAttribute("aria-label"),triggerIsButton:!!trigger&&trigger.tagName==="BUTTON",triggerWithin:!!tr&&tr.left>=0&&tr.right<=window.innerWidth,overlap,pageOverflow:document.documentElement.scrollWidth-window.innerWidth}})()')
  const openFocused = await js('(() => {const b=document.querySelector("button[data-sidebar-toggle]");if(!b)return false;b.focus();return document.activeElement===b})()')
  if (openFocused) await pressKey('Enter', 'Enter', 13)
  await wait(0.4)
  const sidebarOpened = await js('(() => {const s=document.querySelector(".ant-layout-sider");const r=s?s.getBoundingClientRect():null;return !!r&&r.width>0})()')
  const closeFocused = await js('(() => {const b=[...document.querySelectorAll("button")].find(x=>x.getAttribute("aria-label")==="关闭项目侧栏");if(!b)return false;b.focus();return document.activeElement===b})()')
  if (closeFocused) await pressKey(' ', 'Space', 32)
  await wait(0.4)
  const sidebarClosed = await js('(() => {const s=document.querySelector(".ant-layout-sider");const r=s?s.getBoundingClientRect():null;return !!r&&r.width===0})()')
  await openTab('章节管理')
  await wait(3)
  const workspace = await js('(() => {const active=document.querySelector(".ant-tabs-tab-active");const holder=document.querySelector(".ant-tabs-content-holder");const within=x=>!!x&&x.left>=-0.5&&x.right<=window.innerWidth+0.5;const ar=active?active.getBoundingClientRect():null;const hr=holder?holder.getBoundingClientRect():null;return {tabReachable:within(ar)&&!!ar&&ar.bottom>0&&ar.top<window.innerHeight,contentReachable:within(hr)&&!!hr&&hr.bottom>0&&hr.top<window.innerHeight}})()')
  const paragraphFocused = await js('(() => {const b=[...document.querySelectorAll("button")].find(x=>(x.getAttribute("aria-label")||"").startsWith("编辑第 "));if(!b)return false;b.focus();return document.activeElement===b})()')
  if (paragraphFocused) await pressKey('Enter', 'Enter', 13)
  await wait(0.3)
  const enterOpened = await js('[...document.querySelectorAll(".ant-card-head-title")].some(x=>x.innerText.trim()==="段落编辑")')
  const paragraphCloseFocused = await js('(() => {const b=[...document.querySelectorAll("button")].find(x=>x.getAttribute("aria-label")==="关闭段落编辑");if(!b)return false;b.focus();return document.activeElement===b})()')
  if (paragraphCloseFocused) await pressKey(' ', 'Space', 32)
  await wait(0.3)
  const closedAfterEnter = await js('![...document.querySelectorAll(".ant-card-head-title")].some(x=>x.innerText.trim()==="段落编辑")')
  const paragraphSpaceFocused = await js('(() => {const b=[...document.querySelectorAll("button")].find(x=>(x.getAttribute("aria-label")||"").startsWith("编辑第 "));if(!b)return false;b.focus();return document.activeElement===b})()')
  if (paragraphSpaceFocused) await pressKey(' ', 'Space', 32)
  await wait(0.3)
  const spaceOpened = await js('[...document.querySelectorAll(".ant-card-head-title")].some(x=>x.innerText.trim()==="段落编辑")')
  const paragraph = { focusable: paragraphFocused && paragraphSpaceFocused, named: paragraphFocused, enterOpened, closedAfterEnter, spaceOpened }
  await cdp('Emulation.clearDeviceMetricsOverride')
  const pass = list.overflow <= 0 && list.titleWidth >= 160 && list.titleSingleLine && list.headerWithin &&
    list.actionsWithin && list.actionsBelow && list.primaryReachable && workspaceInitial.triggerFocusable &&
    workspaceInitial.triggerNamed && workspaceInitial.triggerIsButton && workspaceInitial.triggerWithin &&
    !workspaceInitial.overlap && workspaceInitial.pageOverflow <= 0 && openFocused && sidebarOpened &&
    closeFocused && sidebarClosed && workspace.tabReachable && workspace.contentReachable &&
    paragraph.focusable && paragraph.named && paragraph.enterOpened && paragraph.closedAfterEnter && paragraph.spaceOpened
  return { pass, list, workspaceInitial, openFocused, sidebarOpened, closeFocused, sidebarClosed, workspace, paragraph }
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
