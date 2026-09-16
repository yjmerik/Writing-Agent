import { test, expect } from '@playwright/test';
const rawBase = process.env.QA_BASE_URL;
if (!rawBase?.trim()) throw new Error('QA_BASE_URL required');
const BASE = rawBase.replace(/\/+$/, '');
test('p39-06: characters API available', async () => {
  const r = await fetch(`${BASE}/api/projects`, { method: 'POST', headers: {'Content-Type':'application/json'},
    body: JSON.stringify({title: 'char-impact', concept: 'ci', tags: '', mode: 'SOLO'}) });
  const pid = ((await r.json()) as {id:string}).id;
  const resp = await fetch(`${BASE}/api/projects/${pid}/characters`);
  expect(resp.status).toBe(200);
});
