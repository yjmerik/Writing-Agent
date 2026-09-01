# Human Canon Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make million-word writing operable: delete/conceive fail-loud, long jobs observable, mid-run human edits complete.

**Architecture:** Fix persistence cascade and exception surfacing first; then split persist vs post-process; then wire existing CanonPatch/facts APIs into UI and fix write-all/checkpoint contracts. No new Agents.

**Tech Stack:** Spring Boot 3 / Java 17 / SQLite / React 18 / JUnit 5

## Global Constraints

- Bugfix gate: failing test first, then implementation
- No prompt `.st` variable renames
- No new detectors / Agents
- Do not auto-rewrite published chapters
- `mvn test` must stay green after each feature
- One feature commit at a time; do not mix PassageLocator leftover unless reviewing it separately

---

### Task 1: p14-01 deleteProject cascade

**Files:**
- Create: `src/test/java/com/writing/novel/persistence/sqlite/SqliteNovelRepositoryDeleteTest.java`
- Modify: `src/main/java/com/writing/novel/persistence/sqlite/SqliteNovelRepository.java` `deleteProject`
- Update: `feature_list.json` p14-01, `progress.md`

See spec D1. Test inserts project + knowledge + outline + story_fact, calls deleteProject, asserts no exception and zero leftover rows.

### Task 2: p14-02 conceiving fail-loud

**Files:**
- Create: `src/test/java/com/writing/novel/web/GlobalExceptionHandlerTest.java` (or extend existing)
- Create: `src/test/java/com/writing/novel/orchestrator/ConceivingCompletenessTest.java`
- Modify: `GlobalExceptionHandler.java`, `NovelOrchestrator.doConceiving`

### Task 3: p14-03 persist-then-async + INTERACTIVE pause

**Files:**
- Create: `EditPassageReturnsBeforeHarvestTest.java`, `InteractivePauseAfterPersistTest.java`
- Modify: `NovelOrchestrator.editPassage` / `doWriteOneChapter`

### Task 4: p14-04 write-all poll + copy

**Files:**
- Modify: `frontend/src/api/client.ts`, `frontend/src/components/RunConsole.tsx`

### Task 5: p14-05 approve-by-id

**Files:**
- Create: `ApproveSpecificCheckpointTest.java`
- Modify: `InteractiveReview.tsx`, approve/resume path

### Task 6: p14-06 human-edit UI

**Files:**
- Modify: `WorldbuildingView.tsx`, `ProjectWorkspace.tsx`, `ProjectListPage.tsx`
- Optional: `FactsView.tsx`

Each task: RED test → GREEN impl → `mvn test` for that class → update feature_list + progress → commit.
