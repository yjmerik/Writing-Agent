# Spec: Isolated E2E Replay Fixtures

## Background And Motivation

The shared `data/fixtures/llm` directory can contain fixtures from different record runs. Replay may then follow a fixture branch that does not match the current recording and fail on a later request fingerprint.

## Goals

- Route E2E record and replay through an explicit fixture directory that survives E2E workspace cleanup.
- Default E2E fixture storage to `data/e2e-fixtures/llm` without changing the shared fixture directory.

## Non-Goals

- Change request fingerprinting or RecordReplayLlmGateway behavior.
- Delete, relocate, or regenerate `data/fixtures/llm`.

## Acceptance Criteria

- [x] `bash scripts/e2e-fixture-dir-test.sh` verifies both the default and an `E2E_FIXTURE_DIR` override reach `--llm.replay.dir`.
- [x] A three-chapter record followed by replay using the same isolated directory completes with zero LLM and chapter errors.

## Impact

- **Modules:** `scripts/e2e.sh`
- **Prompt templates:** None
- **Database schema:** None
- **Quality baseline:** Verified with `python3 scripts/compare-baseline.py`; no baseline update required.

## Feature Breakdown

| id | name | dependencies | acceptance | verify_cmd |
|----|------|--------------|------------|------------|
| p15-07 | Isolated E2E fixtures | [p15-05] | Record and replay use the same configurable directory | `bash scripts/e2e-fixture-dir-test.sh` |

## Risks And Alternatives

The isolated directory is intentionally persistent, so an explicit re-record must use a new directory when a fresh fixture corpus is required. Keeping fixtures outside `data/e2e/` avoids their removal during each E2E run.
