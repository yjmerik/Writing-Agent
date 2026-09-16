import { test, expect } from '@playwright/test';
const rawBase = process.env.QA_BASE_URL;
if (!rawBase?.trim()) throw new Error('QA_BASE_URL required');
const BASE = rawBase.replace(/\/+$/, '');
test('p38-12: disposition API round-trip', async () => {
  const r = await fetch(`${BASE}/api/projects`, { method: 'POST', headers: {'Content-Type':'application/json'},
    body: JSON.stringify({title: 'review-disp', concept: 'rd', tags: '', mode: 'SOLO'}) });
  const pid = ((await r.json()) as {id:string}).id;
  const body = { issueId: 'i1', action: 'FALSE_POSITIVE', reason: 'test reason',
    identity: { projectId: pid, chapterIndex: 1, version: 1, contentHash: 'h1' } };
  const dr = await fetch(`${BASE}/api/projects/${pid}/dispositions`, { method: 'POST', headers: {'Content-Type':'application/json'},
    body: JSON.stringify(body) });
  expect(dr.status).toBe(200);
});
