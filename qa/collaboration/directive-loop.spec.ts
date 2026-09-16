import { test, expect } from '@playwright/test';
const rawBase = process.env.QA_BASE_URL;
if (!rawBase?.trim()) throw new Error('QA_BASE_URL required');
const BASE = rawBase.replace(/\/+$/, '');
test('p37-13: directive API round-trip', async () => {
  const r = await fetch(`${BASE}/api/projects`, { method: 'POST', headers: {'Content-Type':'application/json'},
    body: JSON.stringify({title: 'directive-loop', concept: 'dl', tags: '', mode: 'SOLO'}) });
  const pid = ((await r.json()) as {id:string}).id;
  const dr = await fetch(`${BASE}/api/projects/${pid}/directives`, { method: 'POST', headers: {'Content-Type':'application/json'},
    body: JSON.stringify({kind: 'HARD', content: 'Book-wide HARD requirement'}) });
  expect(dr.status).toBe(200);
  const lr = await fetch(`${BASE}/api/projects/${pid}/directives`);
  const directives = await lr.json();
  expect(directives.length).toBeGreaterThanOrEqual(1);
});
