import { test, expect } from '@playwright/test';

const rawBase = process.env.QA_BASE_URL;
if (!rawBase?.trim()) throw new Error('QA_BASE_URL required');
const BASE = rawBase.replace(/\/+$/, '');

/** p41-02 regression: forged issues must be rejected; recheck retains identity validation.
 * Positive disposition creation/listing/approval with a real stored issue is covered by ReviewDispositionApiTest. */
test('p41-02: forged disposition rejection + recheck identity integration', async () => {
  const pr = await fetch(`${BASE}/api/projects`, { method: 'POST', headers: {'Content-Type':'application/json'},
    body: JSON.stringify({title: 'review-disp', concept: 'rd', tags: '', mode: 'SOLO'}) });
  const pid = ((await pr.json()) as {id:string}).id;

  // Create a real chapter; this API does not create a continuity issue.
  const chRes = await fetch(`${BASE}/api/projects/${pid}/chapters`, { method: 'POST', headers: {'Content-Type':'application/json'},
    body: JSON.stringify({title: 'Ch1', content: 'A chapter with some content for disposition testing. '.repeat(30)}) });
  expect(chRes.status).toBe(200);

  // Get review context (to get identity)
  const ctxRes = await fetch(`${BASE}/api/projects/${pid}/chapters/1/review-context`);
  const ctx = await ctxRes.json();
  const identity = ctx.identity;

  // A made-up issue cannot produce a disposition even with a valid current chapter identity.
  const dispRes = await fetch(`${BASE}/api/projects/${pid}/dispositions`, {
    method: 'POST', headers: {'Content-Type':'application/json'},
    body: JSON.stringify({
      issueId: 'issue-fp-1', action: 'FALSE_POSITIVE', reason: 'Not a real issue',
      identity: {projectId: pid, chapterIndex: 1, version: identity.version,
        contentHash: identity.contentHash}}) });
  expect(dispRes.status).toBe(400);

  // List dispositions by chapter identity
  const listRes = await fetch(
    `${BASE}/api/projects/${pid}/dispositions?chapterIndex=1&chapterVersion=${identity.version}&contentHash=${identity.contentHash}`);
  expect(listRes.status).toBe(200);
  const list = await listRes.json();
  expect(list).toEqual([]);
  const afterReject = await (await fetch(`${BASE}/api/projects/${pid}/chapters/1/review-context`)).json();
  expect(afterReject.reviewSetHash).toBe(ctx.reviewSetHash);

  // Missing reason → 400
  const noReasonRes = await fetch(`${BASE}/api/projects/${pid}/dispositions`, {
    method: 'POST', headers: {'Content-Type':'application/json'},
    body: JSON.stringify({
      issueId: 'issue-bad', action: 'FALSE_POSITIVE',
      identity: {projectId: pid, chapterIndex: 1, version: identity.version,
        contentHash: identity.contentHash}}) });
  expect(noReasonRes.status).toBe(400);

  // DEFER without deferUntilChapter → 400
  const noDeferRes = await fetch(`${BASE}/api/projects/${pid}/dispositions`, {
    method: 'POST', headers: {'Content-Type':'application/json'},
    body: JSON.stringify({
      issueId: 'issue-defer', action: 'DEFER', reason: 'Later',
      identity: {projectId: pid, chapterIndex: 1, version: identity.version,
        contentHash: identity.contentHash}}) });
  expect(noDeferRes.status).toBe(400);

  // Recheck with valid identity → 200
  const recheckRes = await fetch(`${BASE}/api/projects/${pid}/chapters/1/recheck`, {
    method: 'POST', headers: {'Content-Type':'application/json'},
    body: JSON.stringify({identity: {projectId: pid, chapterIndex: 1,
      version: identity.version, contentHash: identity.contentHash}}) });
  expect(recheckRes.status).toBe(200);
  const recheck = await recheckRes.json();
  expect(recheck.chapterVersion).toBe(identity.version);

  // Recheck with stale identity → 409
  const staleRecheck = await fetch(`${BASE}/api/projects/${pid}/chapters/1/recheck`, {
    method: 'POST', headers: {'Content-Type':'application/json'},
    body: JSON.stringify({identity: {projectId: pid, chapterIndex: 1,
      version: identity.version + 10, contentHash: identity.contentHash}}) });
  expect(staleRecheck.status).toBe(409);

  // Recheck without identity → 400
  const noIdRecheck = await fetch(`${BASE}/api/projects/${pid}/chapters/1/recheck`, {
    method: 'POST', headers: {'Content-Type':'application/json'}, body: '{}' });
  expect(noIdRecheck.status).toBe(400);

  // Cross-project disposition → 400
  const pr2 = await fetch(`${BASE}/api/projects`, { method: 'POST', headers: {'Content-Type':'application/json'},
    body: JSON.stringify({title: 'other', concept: 'o', tags: '', mode: 'SOLO'}) });
  const pid2 = ((await pr2.json()) as {id:string}).id;
  const crossRes = await fetch(`${BASE}/api/projects/${pid2}/dispositions`, {
    method: 'POST', headers: {'Content-Type':'application/json'},
    body: JSON.stringify({
      issueId: 'issue-cross', action: 'FALSE_POSITIVE', reason: 'Cross project',
      identity: {projectId: pid, chapterIndex: 1, version: identity.version,
        contentHash: identity.contentHash}}) });
  expect(crossRes.status).toBe(400);
});
