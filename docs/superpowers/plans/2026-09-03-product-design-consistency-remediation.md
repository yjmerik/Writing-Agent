# Product Design Consistency Remediation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the 14 in-scope findings from the 2026-09-02 product-design consistency audit with truthful behavior, persistent results, and browser-level evidence while explicitly deferring automatic-task architecture.

**Architecture:** Keep `NovelOrchestrator` as the only Agent scheduler, put target/state/version validation at service boundaries, and reuse `CanonPatchService` plus a single chapter-revision boundary for persistence. The React UI consumes explicit server capabilities and uses target-bound modal state so every action applies to the object the user selected. Each task is a separate feature and commit; the next task may start only after focused tests, the full Java suite, `./init.sh`, and the relevant browser scenario pass.

**Tech Stack:** Java 17, Spring Boot 3, JUnit 5, Mockito, SQLite/JDBC, React 18, TypeScript, Ant Design, Vite, Bash, ego-browser.

## Global Constraints

- Work only on branch `codex/product-design-consistency-fixes`.
- Do not implement P1-01 automatic-mode continuous execution or P0-03 interactive-to-automatic resume; `auto-zero-intervention` and `interactive-to-auto` remain the only final XFAIL cases.
- Do not add a task queue, background scheduler, `AUTO_RUN` task type, external service, front-end framework, or database product.
- Agents never call each other; all Agent scheduling remains in `NovelOrchestrator`.
- Agent prompts remain external `.st` resources rendered through `PromptTemplateService`.
- Bug fixes begin with a failing regression test or a failing strict browser assertion.
- Only the current `feature_list.json` entry may be `in-progress`; finish its evidence and commit before starting the next.
- Every feature runs its `verify_cmd`, `mvn compile -q && mvn test`, `./init.sh`, and its relevant browser scenario. Prompt/Agent changes also run `./scripts/e2e.sh` and `python3 scripts/compare-baseline.py`.
- Never run `python3 scripts/compare-baseline.py --bless` without separate user approval.
- User-facing terms are `章节管理`, `种下伏笔`, and `回收`; the strings `章节阅读`, `人種`, and `人收` are forbidden.
- Automatic-mode copy must not promise unattended completion.

## Shared delivery procedure

Every task below ends with the same evidence transaction; execute it literally rather than batching tasks:

```bash
# focused command is specified in the task
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home mvn compile -q
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home mvn test
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home PATH=/Users/erik/.local/bin:$PATH ./init.sh
```

After those commands and the task's browser command pass, set the current entry to `completed`, record command/result evidence in `feature_list.json`, append the dated result to `progress.md`, update `session-handoff.md`, stage only the task files plus those three state files, and make the exact commit listed by the task.

---

### Task 1: Checkpoint target ownership and status validation (`p26-01`)

**Files:**
- Create: `src/main/java/com/writing/novel/service/CheckpointTargetService.java`
- Modify: `src/main/java/com/writing/novel/web/CheckpointController.java`
- Test: `src/test/java/com/writing/novel/web/CheckpointTargetValidationTest.java`

**Interfaces:**
- Produces: `CheckpointTargetService.requirePending(String projectId, String checkpointId): Checkpoint`.
- Rule: missing target is 404/argument error; wrong project or non-`PENDING` target is 409/state error; no mutation occurs.

- [ ] **Step 1: Mark `p26-01` in progress and write the failing MVC test.** Cover approve, reject, and modify with a checkpoint belonging to another project, then with an already approved checkpoint. Assert 409 and unchanged checkpoint status.

```java
assertThat(repository.findCheckpoint(checkpointId).orElseThrow().getStatus())
        .isEqualTo(Checkpoint.Status.PENDING);
mockMvc.perform(post("/api/projects/{id}/checkpoints/{cp}/reject", otherProjectId, checkpointId)
        .contentType(APPLICATION_JSON).content("{\"instructions\":\"改写\"}"))
        .andExpect(status().isConflict());
```

- [ ] **Step 2: Prove the regression test is red.**

Run: `mvn test -Dtest=CheckpointTargetValidationTest`

Expected: FAIL because cross-project or processed checkpoints are accepted.

- [ ] **Step 3: Add one validation boundary and route all three endpoints through it.**

```java
public Checkpoint requirePending(String projectId, String checkpointId) {
    Checkpoint checkpoint = repository.findCheckpoint(checkpointId)
            .orElseThrow(() -> new IllegalArgumentException("Checkpoint 不存在: " + checkpointId));
    if (!projectId.equals(checkpoint.getProjectId())) throw new IllegalStateException("Checkpoint 不属于当前项目");
    if (checkpoint.getStatus() != Checkpoint.Status.PENDING) throw new IllegalStateException("Checkpoint 已处理");
    return checkpoint;
}
```

- [ ] **Step 4: Run focused and browser checks.**

Run: `mvn test -Dtest=CheckpointTargetValidationTest,ApproveSpecificCheckpointTest && ./scripts/product-audit-e2e.sh --case checkpoint-card-targeting --expect-known-failures`

Expected: Java tests PASS; browser case remains an expected product failure because card binding is Task 3, with zero infrastructure errors.

- [ ] **Step 5: Run the shared delivery procedure and commit.**

Commit: `fix(checkpoint): validate action target ownership`

### Task 2: Real initial-setup and volume-outline checkpoint revision (`p26-02`)

**Files:**
- Modify: `src/main/java/com/writing/novel/orchestrator/NovelOrchestrator.java`
- Modify: `src/main/java/com/writing/novel/agent/WorldBuilderAgent.java`
- Modify: `src/main/java/com/writing/novel/agent/PlotterAgent.java`
- Test: `src/test/java/com/writing/novel/orchestrator/CheckpointRevisionRoutingTest.java`

**Interfaces:**
- Consumes: `CheckpointTargetService.requirePending(...)` from Task 1.
- Produces: `NovelOrchestrator.reviseCheckpoint(String projectId, Checkpoint checkpoint, String instructions): CheckpointRevisionResult` and nested/standalone immutable result fields `targetType`, `beforeSummary`, `afterSummary`, `beforeVersion`, `afterVersion`.
- Produces candidate-only Agent methods: `WorldBuilderAgent.propose(...)` and `PlotterAgent.proposeVolumeRevision(...)`; these must not persist before validation.

- [ ] **Step 1: Write failing orchestration tests.** Assert initial-setup instructions change persisted world content, volume instructions change only unwritten outline content, returned summaries differ, and an empty/invalid Agent result leaves the checkpoint `PENDING` and original data unchanged.

```java
CheckpointRevisionResult result = orchestrator.reviseCheckpoint(projectId, checkpoint, "改成低魔历史题材");
assertThat(result.targetType()).isEqualTo("INITIAL_SETUP");
assertThat(result.afterSummary()).isNotEqualTo(result.beforeSummary());
assertThat(repository.findCheckpoint(checkpoint.getId()).orElseThrow().getStatus())
        .isEqualTo(Checkpoint.Status.PENDING);
```

- [ ] **Step 2: Run red tests.**

Run: `mvn test -Dtest=CheckpointRevisionRoutingTest`

Expected: FAIL because only chapter checkpoints currently trigger rewriting.

- [ ] **Step 3: Separate candidate generation from persistence.** Make existing `build`/`plan` call candidate methods and persist only the validated candidate. Append the user instruction to each candidate request; reject empty candidates instead of falling back during an explicit revision.

```java
public Worldbuilding propose(String projectId, String concept, String tags, String diversityGuide,
                             String instructions) { /* render, complete, parse without save */ }
public record CheckpointRevisionResult(String targetType, String beforeSummary, String afterSummary,
                                       int beforeVersion, int afterVersion) {}
```

- [ ] **Step 4: Route checkpoint type in `NovelOrchestrator`.** For `AFTER_INITIAL_SETUP`, replace the validated setup candidate atomically through repository operations; for `AFTER_VOLUME_OUTLINE`, merge only chapter indices greater than the project's written boundary; for `AFTER_CHAPTER`, delegate to the existing chapter rewrite. Do not change checkpoint status in this method.

- [ ] **Step 5: Return the result from reject/modify and change status only after success.** Update `CheckpointController` so the response includes `revision`, then marks `REJECTED` or `MODIFIED`; on exception, it leaves `PENDING`.

- [ ] **Step 6: Run focused, quality, and browser checks.**

Run: `mvn test -Dtest=CheckpointRevisionRoutingTest,WorldBuilderAgentTest,PlotterAgentTest && ./scripts/e2e.sh && python3 scripts/compare-baseline.py && ./scripts/product-audit-e2e.sh --case initial-setup-modify && ./scripts/product-audit-e2e.sh --case volume-outline-modify`

Expected: all commands PASS; remove exactly `initial-setup-modify` and `volume-outline-modify` from `KNOWN_FAILURES`.

- [ ] **Step 7: Run the shared delivery procedure and commit.**

Commit: `fix(review): apply setup and outline revisions`

### Task 3: Bind review-card actions to the selected checkpoint (`p26-03`)

**Files:**
- Modify: `frontend/src/components/InteractiveReview.tsx`
- Modify: `frontend/src/api/client.ts`
- Modify: `scripts/product-audit-e2e.sh`

**Interfaces:**
- Consumes: checkpoint response field `revision` from Task 2.
- Produces: modal target state `{ kind: 'checkpoint'; checkpoint: Checkpoint; action: 'reject' | 'modify' }`; every submit passes `checkpoint.id` explicitly.

- [ ] **Step 1: Strengthen the strict browser assertion.** Seed two pending checkpoints and exercise the older card. Assert only that ID leaves the pending set, the newer ID remains, and the success alert contains the clicked card's description plus before/after summary.

- [ ] **Step 2: Run the browser case red.**

Run: `./scripts/product-audit-e2e.sh --case checkpoint-card-targeting`

Expected: non-zero with the newer checkpoint incorrectly handled or an absent result summary.

- [ ] **Step 3: Replace shared/latest checkpoint state with explicit modal target state.**

```tsx
type RewriteTarget = { kind: 'checkpoint'; checkpoint: Checkpoint; action: 'reject' | 'modify' };
const [rewriteTarget, setRewriteTarget] = useState<RewriteTarget | null>(null);
await api.modifyCheckpoint(projectId, rewriteTarget.checkpoint.id, instructions);
```

Render description/payload summary on each card, and render the returned before/after summaries in the operation result.

- [ ] **Step 4: Run focused checks.**

Run: `cd frontend && npm run build && cd .. && ./scripts/product-audit-e2e.sh --case checkpoint-card-targeting`

Expected: PASS; remove `checkpoint-card-targeting` from `KNOWN_FAILURES`.

- [ ] **Step 5: Run the shared delivery procedure and commit.**

Commit: `fix(review): bind actions to selected checkpoint`

### Task 4: Wire standalone chapter rewrite (`p26-04`)

**Files:**
- Modify: `frontend/src/components/InteractiveReview.tsx`
- Modify: `frontend/src/api/client.ts`
- Modify: `scripts/product-audit-e2e.sh`

**Interfaces:**
- Produces modal target `{ kind: 'chapter'; chapterIndex: number; sourceVersion: number }` independent of pending checkpoints.
- Uses existing `POST /api/projects/{id}/rewrite-chapter/{chapterIndex}`.

- [ ] **Step 1: Keep the strict browser case red and record its failure.** Assert modal close, content change, and `after.version > before.version` with zero pending checkpoints.

Run: `./scripts/product-audit-e2e.sh --case standalone-chapter-rewrite`

Expected: non-zero because submit currently depends on `currentCheckpoint`.

- [ ] **Step 2: Implement a discriminated modal target and separate submit branches.**

```tsx
type RewriteTarget =
  | { kind: 'checkpoint'; checkpoint: Checkpoint; action: 'reject' | 'modify' }
  | { kind: 'chapter'; chapterIndex: number; sourceVersion: number };
if (target.kind === 'chapter') {
  const chapter = await api.rewriteChapter(projectId, target.chapterIndex, instructions);
  setBodies(previous => ({ ...previous, [chapter.chapterIndex]: chapter }));
}
```

Close/reset the modal only after the request succeeds; keep it open with an inline error on failure.

- [ ] **Step 3: Run focused checks.**

Run: `cd frontend && npm run build && cd .. && ./scripts/product-audit-e2e.sh --case standalone-chapter-rewrite`

Expected: PASS; remove `standalone-chapter-rewrite` from `KNOWN_FAILURES`.

- [ ] **Step 4: Run the shared delivery procedure and commit.**

Commit: `fix(chapter): wire standalone rewrite dialog`

### Task 5: Keep chapter body visible after approval (`p26-05`)

**Files:**
- Modify: `frontend/src/components/InteractiveReview.tsx`
- Modify: `scripts/product-audit-e2e.sh`

**Interfaces:**
- Rule: metadata refresh preserves a body whose `(chapterIndex, version)` is still current and explicitly reloads the selected body when its version changed.

- [ ] **Step 1: Run the existing strict browser case red.**

Run: `./scripts/product-audit-e2e.sh --case approve-keeps-chapter-visible`

Expected: non-zero because `refresh()` clears the cache while the selected index does not change.

- [ ] **Step 2: Stop clearing all bodies in `refresh()` and add explicit reload.**

```tsx
const loadBody = useCallback(async (chapterIndex: number, force = false) => {
  if (!force && bodiesRef.current[chapterIndex]) return;
  const chapter = await api.getChapter(projectId, chapterIndex);
  setBodies(previous => ({ ...previous, [chapterIndex]: chapter }));
}, [projectId]);
```

After approval, refresh metadata and call `loadBody(selectedChapterIndex, true)` when a selected chapter exists.

- [ ] **Step 3: Run focused checks.**

Run: `cd frontend && npm run build && cd .. && ./scripts/product-audit-e2e.sh --case approve-keeps-chapter-visible`

Expected: PASS; remove `approve-keeps-chapter-visible` from `KNOWN_FAILURES`.

- [ ] **Step 4: Run the shared delivery procedure and commit.**

Commit: `fix(chapter): retain body after review refresh`

### Task 6: Server-owned state/action policy (`p26-06`)

**Files:**
- Create: `src/main/java/com/writing/novel/orchestrator/ProjectActionPolicy.java`
- Modify: `src/main/java/com/writing/novel/web/OrchestratorController.java`
- Modify: `frontend/src/components/RunConsole.tsx`
- Test: `src/test/java/com/writing/novel/orchestrator/ProjectActionPolicyTest.java`

**Interfaces:**
- Produces enum `ProjectAction { ADVANCE, WRITE_CHAPTER, WRITE_ALL, FINAL_AUDIT }`.
- Produces `ProjectActionPolicy.evaluate(Project, boolean hasOutline, boolean hasPendingCheckpoint): ActionAvailability` and `requireAllowed(...)`.
- Status JSON adds `allowedActions: string[]` and `blockedReasons: Record<string,String>`.

- [ ] **Step 1: Write parameterized failing policy tests.** Assert `WRITE_CHAPTER` and `WRITE_ALL` are allowed only in `WRITING` with an outline and no pending checkpoint; terminal states allow neither; `NEEDS_REVISION` allows final audit; paused state exposes no writing command.

- [ ] **Step 2: Run red tests.**

Run: `mvn test -Dtest=ProjectActionPolicyTest`

Expected: FAIL because the policy does not exist and endpoints rely on incidental downstream errors.

- [ ] **Step 3: Implement the policy and enforce it before task creation or Agent work.**

```java
public void requireAllowed(Project project, ProjectAction action, boolean hasOutline,
                           boolean hasPendingCheckpoint) {
    ActionAvailability availability = evaluate(project, hasOutline, hasPendingCheckpoint);
    if (!availability.allowedActions().contains(action))
        throw new IllegalStateException(availability.blockedReasons().get(action));
}
```

- [ ] **Step 4: Render only valid primary actions.** Extend `ProjectStatus` in `frontend/src/types.ts` and use `status.allowedActions.includes('WRITE_CHAPTER')` for visibility/disable state. Show the server reason as tooltip/help text. Keep automatic-task semantics unchanged.

- [ ] **Step 5: Run focused and browser checks.**

Run: `mvn test -Dtest=ProjectActionPolicyTest,TerminalStateCommandGateTest,OrchestratorControllerTest && cd frontend && npm run build && cd .. && ./scripts/browser-e2e.sh`

Expected: PASS.

- [ ] **Step 6: Run the shared delivery procedure and commit.**

Commit: `fix(workflow): expose and enforce valid actions`

### Task 7: Initial-setup review payload summary (`p26-07`)

**Files:**
- Modify: `src/main/java/com/writing/novel/orchestrator/NovelOrchestrator.java`
- Test: `src/test/java/com/writing/novel/orchestrator/InitialSetupCheckpointPayloadTest.java`
- Modify: `frontend/src/components/InteractiveReview.tsx`

**Interfaces:**
- Produces JSON payload keys `worldSummary`, `characterSummary`, `premiseSummary`, and `links`, where links use workspace tabs `world`, `outline`, or `chapters`.

- [ ] **Step 1: Write a failing checkpoint payload test.** After conception, parse the pending initial-setup checkpoint payload and assert nonblank summaries and valid link targets.

```java
JsonNode payload = objectMapper.readTree(checkpoint.getPayload());
assertThat(payload.path("worldSummary").asText()).isNotBlank();
assertThat(payload.path("characterSummary").asText()).isNotBlank();
assertThat(payload.path("premiseSummary").asText()).isNotBlank();
```

- [ ] **Step 2: Run red, implement bounded summaries, and render them.** Bound each summary to 300 Unicode code points, JSON-encode through Jackson rather than string concatenation, and show links that switch the existing workspace tab.

Run: `mvn test -Dtest=InitialSetupCheckpointPayloadTest`

Expected before implementation: FAIL; after implementation: PASS.

- [ ] **Step 3: Run UI and browser checks.**

Run: `cd frontend && npm run build && cd .. && ./scripts/product-audit-e2e.sh --case checkpoint-card-targeting`

Expected: PASS.

- [ ] **Step 4: Run the shared delivery procedure and commit.**

Commit: `feat(review): summarize initial setup checkpoint`

### Task 8: Complete world and character field editing (`p26-08`)

**Files:**
- Modify: `src/main/java/com/writing/novel/service/CanonPatchService.java`
- Modify: `frontend/src/components/WorldbuildingView.tsx`
- Test: `src/test/java/com/writing/novel/web/WorldCharacterFullEditTest.java`

**Interfaces:**
- World type allowlist: `GEOGRAPHY`, `POWER_SYSTEM`, `FACTION`, `HISTORY`, `RULE`, `CULTURE`, `ECONOMY`, `RACE`, `CUSTOM` with Chinese labels.
- Character fields: `name`, `role`, `personality`, `ability`, `motivation`, `appearance`, `relationships`, `growthArc`.

- [ ] **Step 1: Write failing API tests.** Update a world entry's type/name/content and all character fields, reload them, and assert exact persistence. Send type `INVALID` and assert 400 with no mutation.

- [ ] **Step 2: Run red tests.**

Run: `mvn test -Dtest=WorldCharacterFullEditTest`

Expected: FAIL because world type is ignored and invalid types are accepted.

- [ ] **Step 3: Validate and persist the complete patch.**

```java
private static final Set<String> WORLD_TYPES = Set.of(
    "GEOGRAPHY", "POWER_SYSTEM", "FACTION", "HISTORY", "RULE",
    "CULTURE", "ECONOMY", "RACE", "CUSTOM");
if (!WORLD_TYPES.contains(patch.getType())) throw new IllegalArgumentException("无效的世界观类型");
existing.setType(patch.getType());
```

- [ ] **Step 4: Replace free text type with a Chinese-labelled select and expose all character fields.** Keep API values as enum strings; use explicit form labels and preserve values on failed save.

- [ ] **Step 5: Run focused/browser checks.**

Run: `mvn test -Dtest=WorldCharacterFullEditTest,CanonPatchServiceTest && cd frontend && npm run build && cd .. && ./scripts/product-audit-e2e.sh --case manual-content-management --expect-known-failures`

Expected: backend assertions PASS; the aggregate browser case may remain expected until Tasks 9–16, with zero ERROR.

- [ ] **Step 6: Run the shared delivery procedure and commit.**

Commit: `fix(content): edit complete world and character fields`

### Task 9: Validate and persist unwritten outline structure (`p26-09`)

**Files:**
- Create: `src/main/java/com/writing/novel/service/OutlineStructureService.java`
- Modify: `src/main/java/com/writing/novel/web/ContentController.java`
- Test: `src/test/java/com/writing/novel/web/OutlineStructureEditTest.java`

**Interfaces:**
- Produces `OutlineStructureService.replaceUnwritten(String projectId, Outline incoming): Outline`.
- Rules: written indices `<= currentChapterIndex` retain their existing volume, index, title, and synopsis; all incoming chapter indices are unique, positive, and strictly increasing; unwritten volumes/chapters may be added, removed, or reordered; validation precedes a single repository save.

- [ ] **Step 1: Write failing integration tests.** Cover add/delete/reorder beyond the written boundary and reject duplicate, descending, nonpositive, or written-index replacement payloads. Reload after every accepted call.

- [ ] **Step 2: Run red tests.**

Run: `mvn test -Dtest=OutlineStructureEditTest`

Expected: FAIL because current merge does not support delete/reorder and lacks full validation.

- [ ] **Step 3: Implement one validate-then-save transaction boundary.** Build a detached merged `Outline`, compare written prefix fields, validate a global ordered index set, then call `canonPatch.patchOutline` once.

```java
List<Integer> indices = merged.getVolumes().stream()
        .flatMap(v -> v.getSynopses().stream())
        .map(ChapterSynopsis::getChapterIndex).toList();
if (new HashSet<>(indices).size() != indices.size())
    throw new IllegalArgumentException("章号必须唯一");
```

- [ ] **Step 4: Run focused/browser checks.**

Run: `mvn test -Dtest=OutlineStructureEditTest,UnwrittenSynopsisEditTest && ./scripts/product-audit-e2e.sh --case manual-content-management --expect-known-failures`

Expected: Java PASS; aggregate browser case remains expected until its UI is complete.

- [ ] **Step 5: Run the shared delivery procedure and commit.**

Commit: `feat(outline): edit unwritten structure safely`

### Task 10: Outline structural editing UI (`p26-10`)

**Files:**
- Modify: `frontend/src/components/OutlineView.tsx`
- Modify: `frontend/src/api/client.ts`
- Modify: `scripts/product-audit-e2e.sh`

**Interfaces:**
- Consumes Task 9's existing `PUT /outline` semantics.
- UI operations update a local draft only until `保存未写大纲`; written rows render locked.

- [ ] **Step 1: Extend the browser scenario to add one unwritten chapter, move it within an unwritten volume, delete another unwritten chapter, save, reload, and assert the exact ordered IDs/indices.**

- [ ] **Step 2: Run the browser case red.**

Run: `./scripts/product-audit-e2e.sh --case manual-content-management`

Expected: non-zero because structural controls do not exist.

- [ ] **Step 3: Add explicit add/delete/move controls and draft validation.** Use Ant Design `Button` components with accessible names; disable controls for written items and display the server validation message via `message.error`.

```tsx
<Button aria-label={`上移第${synopsis.chapterIndex}章`} disabled={locked || first}
        onClick={() => moveChapter(volumeIndex, synopsisIndex, -1)}>上移</Button>
```

- [ ] **Step 4: Run focused checks.**

Run: `cd frontend && npm run build && cd .. && ./scripts/product-audit-e2e.sh --case manual-content-management --expect-known-failures`

Expected: outline sub-assertions PASS; aggregate remains expected until chapter and Agent editing are complete.

- [ ] **Step 5: Run the shared delivery procedure and commit.**

Commit: `feat(outline): add structural editing controls`

### Task 11: Immutable chapter version history storage (`p26-11`)

**Files:**
- Create: `src/main/java/com/writing/novel/domain/ChapterVersion.java`
- Modify: `src/main/java/com/writing/novel/spi/NovelRepository.java`
- Modify: `src/main/java/com/writing/novel/persistence/sqlite/SqliteSchemaInitializer.java`
- Modify: `src/main/java/com/writing/novel/persistence/sqlite/SqliteNovelRepository.java`
- Test: `src/test/java/com/writing/novel/persistence/ChapterVersionHistoryTest.java`

**Interfaces:**
- Produces `ChapterVersion(id, projectId, chapterIndex, version, title, content, source, createdAt)`.
- Repository methods: `saveChapterVersion(ChapterVersion snapshot)`, `findChapterVersions(String projectId, int chapterIndex)`, `findChapterVersion(String projectId, int chapterIndex, int version)`.
- Unique key: `(project_id, chapter_index, version)`; rows are insert-only.

- [ ] **Step 1: Write failing repository contract tests.** Save versions 1 and 2, assert descending query order and exact immutable content; assert a duplicate key fails and no update/delete API exists.

- [ ] **Step 2: Run red tests.**

Run: `mvn test -Dtest=ChapterVersionHistoryTest`

Expected: FAIL because table/type/repository methods do not exist.

- [ ] **Step 3: Add the forward-only schema and repository mapping.**

```sql
CREATE TABLE IF NOT EXISTS chapter_versions (
  id TEXT PRIMARY KEY, project_id TEXT NOT NULL, chapter_index INTEGER NOT NULL,
  version INTEGER NOT NULL, title TEXT NOT NULL, content TEXT NOT NULL,
  source TEXT NOT NULL, created_at TEXT NOT NULL,
  UNIQUE(project_id, chapter_index, version)
)
```

- [ ] **Step 4: Run focused migration tests.**

Run: `mvn test -Dtest=ChapterVersionHistoryTest,SqliteSchemaInitializerTest,SqliteNovelRepositoryTest`

Expected: PASS against both a fresh and an existing-schema test database.

- [ ] **Step 5: Run the shared delivery procedure and commit.**

Commit: `feat(chapter): store immutable version history`

### Task 12: Chapter create, direct edit, and restore API (`p26-12`)

**Files:**
- Create: `src/main/java/com/writing/novel/service/ChapterRevisionService.java`
- Modify: `src/main/java/com/writing/novel/web/ContentController.java`
- Test: `src/test/java/com/writing/novel/web/ChapterDirectEditTest.java`

**Interfaces:**
- Produces `createDraft(projectId, title, content)`, `edit(projectId, chapterIndex, expectedVersion, title, content, source)`, `restore(projectId, chapterIndex, historyVersion, expectedVersion)`, and `history(projectId, chapterIndex)`.
- Endpoints: `POST /chapters`, `PUT /chapters/{index}`, `GET /chapters/{index}/versions`, `POST /chapters/{index}/restore/{version}`.
- Every mutation snapshots the pre-change value, checks the expected current version, saves the chapter, clears/rebuilds chapter vectors, and invokes existing post-processing invalidation.

- [ ] **Step 1: Write failing MVC/integration tests.** Assert create draft at the next index, edit title/body with version increment, history query, restore creating another version, 409 on stale `expectedVersion`, and no partial change.

- [ ] **Step 2: Run red tests.**

Run: `mvn test -Dtest=ChapterDirectEditTest`

Expected: FAIL because endpoints and revision boundary do not exist.

- [ ] **Step 3: Implement the single revision boundary.**

```java
public Chapter edit(String projectId, int chapterIndex, int expectedVersion,
                    String title, String content, String source) {
    Chapter current = requireChapter(projectId, chapterIndex);
    if (current.getVersion() != expectedVersion) throw new IllegalStateException("章节版本已变化");
    repository.saveChapterVersion(ChapterVersion.from(current, source));
    current.setTitle(title);
    current.setContent(content);
    repository.saveChapter(current);
    refreshDerivedState(current);
    return current;
}
```

Reject blank titles and bodies under the existing 500-character chapter rule where applicable; a manually created draft may remain `DRAFT` until content meets the publication threshold.

- [ ] **Step 4: Route existing rewrite and passage edit through this boundary.** Preserve their public behavior while ensuring all content changes create history.

- [ ] **Step 5: Run focused checks.**

Run: `mvn test -Dtest=ChapterDirectEditTest,RewriteRecheckTest,EditPassageReturnsBeforeHarvestTest,VectorCleanupOnRewritePathsTest`

Expected: PASS.

- [ ] **Step 6: Run the shared delivery procedure and commit.**

Commit: `feat(chapter): add direct edit and restore API`

### Task 13: Chapter editing and history UI (`p26-13`)

**Files:**
- Modify: `frontend/src/components/InteractiveReview.tsx`
- Modify: `frontend/src/api/client.ts`
- Modify: `frontend/src/types.ts`

**Interfaces:**
- Consumes Task 12 endpoints and `ChapterVersion` DTO.
- Produces visible actions `新增章节`, `编辑正文`, `版本记录`, `比较`, and `回退到此版本`.

- [ ] **Step 1: Extend the aggregate browser scenario.** Create a draft, edit title/body, reload, open version history, compare old/current text, restore, reload, and assert restored content plus another version increment.

- [ ] **Step 2: Run red.**

Run: `./scripts/product-audit-e2e.sh --case manual-content-management`

Expected: non-zero because UI actions are absent.

- [ ] **Step 3: Add target-bound edit/history dialogs.** Keep edited text local until save; show a side-by-side or line-preserving `<pre>` comparison; submit `expectedVersion`; on 409 reload and show “章节已被更新，请重新确认”.

```tsx
await api.updateChapter(projectId, chapter.chapterIndex, {
  expectedVersion: chapter.version, title: draftTitle, content: draftContent,
});
```

- [ ] **Step 4: Run focused/browser checks.**

Run: `cd frontend && npm run build && cd .. && ./scripts/product-audit-e2e.sh --case manual-content-management --expect-known-failures`

Expected: chapter-management sub-assertions PASS; aggregate may remain expected until Agent modification is complete.

- [ ] **Step 5: Run the shared delivery procedure and commit.**

Commit: `feat(chapter): add editing and version controls`

### Task 14: Generic content-revision Agent and external prompt (`p26-14`)

**Files:**
- Create: `src/main/java/com/writing/novel/agent/ContentRevisionAgent.java`
- Create: `src/main/resources/prompts/content-revision.st`
- Test: `src/test/java/com/writing/novel/agent/ContentRevisionAgentTest.java`

**Interfaces:**
- Produces `ContentRevisionAgent.preview(TargetType type, String currentJson, String instructions): RevisionCandidate`.
- `TargetType` values: `WORLD`, `CHARACTER`, `VOLUME`, `CHAPTER`, `PASSAGE`.
- `RevisionCandidate` fields: `targetType`, `title`, `content`, `structuredJson`, `summary`; malformed/empty responses throw and never write repository state.

- [ ] **Step 1: Write failing unit tests with a stub gateway.** Assert all five target types render the target/current content/instructions, valid JSON parses, empty content and invalid JSON throw, and repository state is untouched.

- [ ] **Step 2: Run red tests.**

Run: `mvn test -Dtest=ContentRevisionAgentTest`

Expected: FAIL because Agent and template do not exist.

- [ ] **Step 3: Add the external prompt and strict parser.** Template output is one JSON object and contains explicit invariants: revise only the selected object, preserve IDs/indices, and return no Markdown fence.

```java
String prompt = promptService.render("content-revision", Map.of(
    "targetType", type.name(), "currentJson", currentJson, "instructions", instructions));
RevisionCandidate candidate = objectMapper.readValue(response.getContent(), RevisionCandidate.class);
if (candidate.content() == null || candidate.content().isBlank())
    throw new IllegalStateException("Agent 未返回有效修改候选");
```

- [ ] **Step 4: Run focused and quality checks.**

Run: `mvn test -Dtest=ContentRevisionAgentTest,LlmGatewayWiringTest && ./scripts/e2e.sh && python3 scripts/compare-baseline.py`

Expected: PASS without baseline blessing.

- [ ] **Step 5: Run the shared delivery procedure and commit.**

Commit: `feat(agent): preview scoped content revisions`

### Task 15: Revision preview/apply API with optimistic version checks (`p26-15`)

**Files:**
- Create: `src/main/java/com/writing/novel/service/AgentRevisionService.java`
- Create: `src/main/java/com/writing/novel/web/RevisionController.java`
- Test: `src/test/java/com/writing/novel/web/AgentRevisionApiTest.java`

**Interfaces:**
- `POST /api/projects/{id}/revisions/preview` request: `{targetType,targetId,chapterIndex,passageIndex,instructions}`.
- Preview response: `{previewId,targetType,targetId,sourceVersion,before,after,summary}`; preview is server-held or signed/serialized without domain mutation.
- `POST /api/projects/{id}/revisions/{previewId}/apply` checks project, target identity, active writing task, and source version, then delegates to `CanonPatchService`, `OutlineStructureService`, or `ChapterRevisionService`.

- [ ] **Step 1: Write failing full-stack tests.** For each target type, fingerprint storage before preview, assert unchanged; apply and assert only the target changed; mutate the target between preview/apply and assert 409 with preview still unapplied.

- [ ] **Step 2: Run red tests.**

Run: `mvn test -Dtest=AgentRevisionApiTest`

Expected: FAIL because endpoints do not exist.

- [ ] **Step 3: Implement immutable preview records and target-specific delegates.** Use a UUID preview ID, explicit source version, and one-time apply semantics. Never let `ContentRevisionAgent` write a repository.

```java
if (currentVersion != preview.sourceVersion())
    throw new IllegalStateException("目标内容已变化，请重新生成预览");
return switch (preview.targetType()) {
    case WORLD -> canonPatch.patchWorld(...).entity();
    case CHARACTER -> canonPatch.patchCharacter(...).entity();
    case VOLUME -> outlineStructure.replaceUnwritten(...);
    case CHAPTER, PASSAGE -> chapterRevision.edit(...);
};
```

- [ ] **Step 4: Run focused, quality, and browser checks.**

Run: `mvn test -Dtest=AgentRevisionApiTest,ContentRevisionAgentTest && ./scripts/e2e.sh && python3 scripts/compare-baseline.py && ./scripts/product-audit-e2e.sh --case manual-content-management --expect-known-failures`

Expected: API and quality checks PASS; aggregate browser case remains expected until Task 16.

- [ ] **Step 5: Run the shared delivery procedure and commit.**

Commit: `feat(agent): apply revision previews safely`

### Task 16: Unified “让 Agent 修改” UI at five levels (`p26-16`)

**Files:**
- Create: `frontend/src/components/AgentRevisionDialog.tsx`
- Modify: `frontend/src/components/WorldbuildingView.tsx`
- Modify: `frontend/src/components/OutlineView.tsx`
- Modify: `frontend/src/components/InteractiveReview.tsx`
- Modify: `frontend/src/api/client.ts`
- Modify: `scripts/product-audit-e2e.sh`

**Interfaces:**
- `AgentRevisionDialog` props: `{open,target,onApplied,onCancel}` where target contains `targetType`, `targetId`, optional `chapterIndex`/`passageIndex`, label, and sourceVersion.
- Dialog states: instruction entry → generating → preview diff → applying; cancel never writes.

- [ ] **Step 1: Extend the browser scenario across WORLD, CHARACTER, VOLUME, CHAPTER, and PASSAGE.** For each, generate preview, fingerprint storage before confirmation, assert unchanged, apply, then assert only selected target changes. Exercise cancel once and assert no write.

- [ ] **Step 2: Run red.**

Run: `./scripts/product-audit-e2e.sh --case manual-content-management`

Expected: non-zero because unified preview controls are absent.

- [ ] **Step 3: Build the shared dialog and add explicit entry buttons at all five levels.** Paragraph buttons must be real `Button` elements with `aria-label="让 Agent 修改第 N 段"`; do not make paragraph text itself the only click target.

```tsx
<AgentRevisionDialog open={target !== null} target={target}
  onApplied={() => { setTarget(null); refresh(); }} onCancel={() => setTarget(null)} />
```

- [ ] **Step 4: Run focused/browser checks.**

Run: `cd frontend && npm run build && cd .. && ./scripts/product-audit-e2e.sh --case manual-content-management`

Expected: PASS; remove `manual-content-management` from `KNOWN_FAILURES`.

- [ ] **Step 5: Run the shared delivery procedure and commit.**

Commit: `feat(ui): add scoped agent revision previews`

### Task 17: Truthful paused stage and project progress (`p26-17`)

**Files:**
- Modify: `src/main/java/com/writing/novel/web/OrchestratorController.java`
- Modify: `frontend/src/components/RunConsole.tsx`
- Modify: `frontend/src/pages/ProjectListPage.tsx`
- Test: `src/test/java/com/writing/novel/web/ProjectProgressViewTest.java`

**Interfaces:**
- Status/list JSON exposes `targetChapters`, `progressPercent`, `pauseStage`, and `pauseReason`.
- `pauseStage` maps `AFTER_INITIAL_SETUP→PLANNING`, `AFTER_VOLUME_OUTLINE→PLANNING`, `AFTER_CHAPTER→WRITING`; no checkpoint means null.
- Writing progress is `min(100, floor(currentChapterIndex * 100 / targetChapters))`; conception/planning use named milestone percentages only.

- [ ] **Step 1: Write failing controller tests.** Seed each checkpoint type and assert pause stage/reason; seed `current=3,target=12` and assert 25 plus visible numeric values in DTO.

- [ ] **Step 2: Run red tests.**

Run: `mvn test -Dtest=ProjectProgressViewTest`

Expected: FAIL because values are hard-coded in the UI and absent from responses.

- [ ] **Step 3: Compute status values server-side and render without local guesses.** Make the Steps component use `pauseStage`; show `3 / 12 章 · 25%` on the list. For missing preferences use the domain default 24, not a UI constant.

- [ ] **Step 4: Correct automatic-mode copy.** Replace “全自动生成，无需干预” with a truthful description such as “自动模式减少审核停顿；写作推进仍由当前任务触发”. Do not alter mode-switch behavior.

- [ ] **Step 5: Run focused/UI checks.**

Run: `mvn test -Dtest=ProjectProgressViewTest && cd frontend && npm run build && cd .. && ./scripts/browser-e2e.sh`

Expected: PASS.

- [ ] **Step 6: Run the shared delivery procedure and commit.**

Commit: `fix(progress): show real stage and chapter progress`

### Task 18: Four-state quality evaluation and consistent feedback (`p26-18`)

**Files:**
- Modify: `frontend/src/components/QualityView.tsx`
- Modify: `frontend/src/api/client.ts`
- Modify: `scripts/product-audit-e2e.sh`

**Interfaces:**
- UI state: `NOT_EVALUATED | RUNNING | SUCCEEDED | FAILED` plus optional result/error.
- `NOT_EVALUATED` shows no score; `FAILED` shows error and retry; `SUCCEEDED` may truthfully show score 0 and empty issues.

- [ ] **Step 1: Add `quality-evaluation-state` to the browser suite.** Assert initial absence of “0分/未发现问题”, running busy state, a deterministic failure with inline alert and retry, and a successful zero-score fixture distinguishable from not evaluated.

- [ ] **Step 2: Run red.**

Run: `./scripts/product-audit-e2e.sh --case quality-evaluation-state`

Expected: non-zero because missing results currently render as zero/empty and errors use `alert()`.

- [ ] **Step 3: Implement the state machine and in-app feedback.**

```tsx
type EvaluationState = 'NOT_EVALUATED' | 'RUNNING' | 'SUCCEEDED' | 'FAILED';
if (state === 'NOT_EVALUATED') return <Empty description="尚未评价" />;
if (state === 'FAILED') return <Alert type="error" message="评价失败" action={<Button onClick={evaluate}>重试</Button>} />;
```

Use Ant Design `message.error` for transient errors and never browser `alert`.

- [ ] **Step 4: Run focused checks.**

Run: `cd frontend && npm run build && cd .. && ./scripts/product-audit-e2e.sh --case quality-evaluation-state`

Expected: PASS.

- [ ] **Step 5: Run the shared delivery procedure and commit.**

Commit: `fix(quality): distinguish evaluation states`

### Task 19: Mobile critical layout and accessible controls (`p26-19`)

**Files:**
- Modify: `frontend/src/pages/ProjectListPage.tsx`
- Modify: `frontend/src/pages/ProjectWorkspace.tsx`
- Modify: `frontend/src/components/InteractiveReview.tsx`
- Modify: `scripts/product-audit-e2e.sh`

**Interfaces:**
- 375px header layout stacks title/actions, does not horizontally clip the project title, and leaves primary controls reachable.
- Sidebar collapse and paragraph edit use focusable buttons with accessible names and Enter/Space activation.

- [ ] **Step 1: Strengthen the existing mobile case.** At 375×812 assert no element extends beyond viewport, header actions do not overlap title, sidebar toggle is a `button`, tab/content controls remain reachable, and paragraph edit can be activated by keyboard.

- [ ] **Step 2: Run red.**

Run: `./scripts/product-audit-e2e.sh --case mobile-critical-path`

Expected: non-zero with title compression and inaccessible controls.

- [ ] **Step 3: Add responsive classes and semantic buttons.** Put media-query rules in the existing app stylesheet; use `flex-wrap`/column layout below 576px; replace clickable decorative elements with Ant Design buttons. Preserve desktop layout.

- [ ] **Step 4: Run focused checks.**

Run: `cd frontend && npm run build && cd .. && ./scripts/product-audit-e2e.sh --case mobile-critical-path`

Expected: PASS; remove `mobile-critical-path` from `KNOWN_FAILURES`.

- [ ] **Step 5: Run the shared delivery procedure and commit.**

Commit: `fix(ui): make critical paths mobile accessible`

### Task 20: Terminology and copy regression gate (`p26-20`)

**Files:**
- Create: `scripts/user-copy-check.sh`
- Modify: `frontend/src/components/RunConsole.tsx`
- Modify: `frontend/src/components/WorldbuildingView.tsx`

**Interfaces:**
- Script scans user-facing frontend sources and exits nonzero for exact forbidden terms `人種`, `人收`, `章节阅读`, or `全自动生成，无需干预`.

- [ ] **Step 1: Create the gate with known failing terms still present and run it red.**

```bash
for forbidden in '人種' '人收' '章节阅读' '全自动生成，无需干预'; do
  if rg -n --fixed-strings "$forbidden" frontend/src; then failed=1; fi
done
exit "${failed:-0}"
```

Run: `./scripts/user-copy-check.sh`

Expected: non-zero and exact file/line findings.

- [ ] **Step 2: Correct the visible terms.** Use `种下伏笔`, `回收`, and `章节管理`; use the truthful automatic-mode wording from Task 17.

- [ ] **Step 3: Run focused checks.**

Run: `./scripts/user-copy-check.sh && cd frontend && npm run build && cd ..`

Expected: PASS.

- [ ] **Step 4: Run the shared delivery procedure and commit.**

Commit: `fix(copy): enforce consistent product terminology`

### Task 21: Phase 26 end-to-end acceptance (`p26-21`)

**Files:**
- Modify: `scripts/product-audit-e2e.sh`
- Modify: `feature_list.json`
- Modify: `progress.md`
- Modify: `session-handoff.md`

**Interfaces:**
- Final `CASES` includes all audit paths added during the phase.
- Final `KNOWN_FAILURES` is exactly `auto-zero-intervention interactive-to-auto`.

- [ ] **Step 1: Make the known-failure set exact.** Do not delete or weaken either deferred automatic-task assertion; ensure every repaired scenario is absent from `KNOWN_FAILURES` and still runs in strict mode.

```bash
KNOWN_FAILURES=(
  auto-zero-intervention
  interactive-to-auto
)
```

- [ ] **Step 2: Run every repaired product scenario in strict mode.**

Run: `for c in initial-setup-modify volume-outline-modify standalone-chapter-rewrite approve-keeps-chapter-visible checkpoint-card-targeting manual-content-management mobile-critical-path quality-evaluation-state; do ./scripts/product-audit-e2e.sh --case "$c" || exit 1; done`

Expected: every case PASS with zero ERROR.

- [ ] **Step 3: Run the complete product suite in known-failure mode.**

Run: `./scripts/product-audit-e2e.sh --expect-known-failures`

Expected: all repaired cases PASS, exactly two XFAIL (`auto-zero-intervention`, `interactive-to-auto`), zero ERROR.

- [ ] **Step 4: Run all release-quality gates.**

Run: `./scripts/e2e.sh && python3 scripts/compare-baseline.py && ./init.sh`

Expected: deterministic writing E2E PASS, baseline comparison PASS, Maven full suite PASS, frontend production build PASS.

- [ ] **Step 5: Audit scope and state.** Run `git diff --check`, confirm no `AUTO_RUN`, scheduler, queue, or restart-recovery implementation entered the branch, confirm all Phase 26 evidence is dated 2026-09-03 or the actual completion date, and confirm no feature remains `in-progress`.

- [ ] **Step 6: Request final code review and resolve only in-scope findings.** The reviewer must compare the branch against the spec, inspect concurrency/version guards, and verify the two automatic-task exclusions remain untouched.

- [ ] **Step 7: Mark `p26-21` complete and commit.**

Commit: `test(p26): close product consistency audit`

## Self-review result

- Spec coverage: all 14 in-scope findings map to Tasks 1–20; Task 21 verifies them together. P1-01 and P0-03 remain explicit, exercised exclusions.
- Placeholder scan: the plan contains no deferred implementation markers; every task identifies files, interfaces, red test, passing command, evidence transaction, and commit.
- Type consistency: checkpoint revisions use `CheckpointRevisionResult`; content previews use `RevisionCandidate` and source-versioned preview IDs; UI target unions preserve the selected checkpoint/chapter/object through submission.
- Scope guard: no task adds background automation, automatic resume, durable auto workers, or changes the writing-task type model.
