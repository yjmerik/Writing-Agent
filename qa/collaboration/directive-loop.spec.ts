import { test, expect } from '@playwright/test';

const rawBase = process.env.QA_BASE_URL;
if (!rawBase?.trim()) throw new Error('QA_BASE_URL required');
const BASE = rawBase.replace(/\/+$/, '');

/** p37-13: 意见范围编辑与证据逐项作者确认——全 API 驱动真实断言。 */
test('p37-13: directive scope edit + evidence confirm + HARD persistence', async () => {
  // Create project
  const pr = await fetch(`${BASE}/api/projects`, { method: 'POST', headers: {'Content-Type':'application/json'},
    body: JSON.stringify({title: 'directive-loop', concept: 'dl', tags: '', mode: 'SOLO'}) });
  const pid = ((await pr.json()) as {id:string}).id;

  // Create HARD directive with scope
  const dRes = await fetch(`${BASE}/api/projects/${pid}/directives`, { method: 'POST', headers: {'Content-Type':'application/json'},
    body: JSON.stringify({kind: 'HARD', content: 'Every chapter must have a rainy night scene', fromChapter: 1}) });
  expect(dRes.status).toBe(200);
  const directive = await dRes.json();
  expect(directive.revision).toBe(1);
  expect(directive.fromChapter).toBe(1);
  expect(directive.enabled).toBe(true);
  expect(directive.evidence).toEqual([]);

  // Edit scope with CAS (correct revision)
  const patchRes = await fetch(`${BASE}/api/projects/${pid}/directives/${directive.id}`, {
    method: 'PATCH', headers: {'Content-Type':'application/json'},
    body: JSON.stringify({expectedRevision: 1, fromChapter: 3, toChapter: 8}) });
  expect(patchRes.status).toBe(200);
  const updated = await patchRes.json();
  expect(updated.revision).toBe(2);
  expect(updated.fromChapter).toBe(3);
  expect(updated.toChapter).toBe(8);

  // Stale CAS (old revision) → 409
  const stalePatch = await fetch(`${BASE}/api/projects/${pid}/directives/${directive.id}`, {
    method: 'PATCH', headers: {'Content-Type':'application/json'},
    body: JSON.stringify({expectedRevision: 1, fromChapter: 1}) });
  expect(stalePatch.status).toBe(409);

  // Confirm on OPEN without evidence → 409
  const earlyConfirm = await fetch(`${BASE}/api/projects/${pid}/directives/${directive.id}/confirm`, { method: 'POST' });
  expect(earlyConfirm.status).toBe(409);

  // Create a chapter and approve it
  const chRes = await fetch(`${BASE}/api/projects/${pid}/chapters`, { method: 'POST', headers: {'Content-Type':'application/json'},
    body: JSON.stringify({title: 'Ch1', content: 'A rainy night scene unfolds in the ancient city. '.repeat(30)}) });
  expect(chRes.status).toBe(200);
  const chapter = await chRes.json();

  // Get review context
  const ctxRes = await fetch(`${BASE}/api/projects/${pid}/chapters/1/review-context`);
  expect(ctxRes.status).toBe(200);
  const ctx = await ctxRes.json();
  expect(ctx.reviewSetHash).toBeTruthy();

  // Approve the chapter
  const approvalRes = await fetch(`${BASE}/api/projects/${pid}/chapters/1/review-decisions`, {
    method: 'POST', headers: {'Content-Type':'application/json'},
    body: JSON.stringify({
      identity: ctx.identity, reviewSetHash: ctx.reviewSetHash,
      idempotencyKey: 'dl-approve-1', reason: 'test approve' }) });
  expect(approvalRes.status).toBe(200);

  // Verify the directive is still applicable (HARD persists after chapter confirm)
  const listRes = await fetch(`${BASE}/api/projects/${pid}/directives`);
  const directives = await listRes.json();
  const hardDir = directives.find((d: any) => d.id === directive.id);
  expect(hardDir).toBeDefined();
  expect(hardDir.enabled).toBe(true);

  // Revoke HARD with reason
  const revokeRes = await fetch(`${BASE}/api/projects/${pid}/directives/${directive.id}/revoke`, {
    method: 'POST', headers: {'Content-Type':'application/json'},
    body: JSON.stringify({expectedRevision: 2, reason: 'Author changed direction'}) });
  expect(revokeRes.status).toBe(200);
  const revoked = await revokeRes.json();
  expect(revoked.status).toBe('DISMISSED');
  expect(revoked.enabled).toBe(false);
  expect(revoked.revision).toBe(3);
});

test('p37-13: revoke without reason → 400', async () => {
  const pr = await fetch(`${BASE}/api/projects`, { method: 'POST', headers: {'Content-Type':'application/json'},
    body: JSON.stringify({title: 'no-reason', concept: 'nr', tags: '', mode: 'SOLO'}) });
  const pid = ((await pr.json()) as {id:string}).id;
  const dRes = await fetch(`${BASE}/api/projects/${pid}/directives`, { method: 'POST', headers: {'Content-Type':'application/json'},
    body: JSON.stringify({kind: 'PREFERENCE', content: 'Prefer short sentences'}) });
  const directive = await dRes.json();
  const revoke = await fetch(`${BASE}/api/projects/${pid}/directives/${directive.id}/revoke`, {
    method: 'POST', headers: {'Content-Type':'application/json'},
    body: JSON.stringify({expectedRevision: 1}) });
  expect(revoke.status).toBe(400);
});
