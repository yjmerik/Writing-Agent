import { test, expect } from '@playwright/test';
const rawBase = process.env.QA_BASE_URL;
if (!rawBase || !rawBase.trim()) throw new Error('QA_BASE_URL required');
const BASE = rawBase.replace(/\/+$/, '');

test('p40-03: run details API returns NO_TASK for new project', async ({ page }) => {
  const r = await fetch(`${BASE}/api/projects`, { method: 'POST', headers: {'Content-Type':'application/json'},
    body: JSON.stringify({title: 'run-details', concept: 'run', tags: '', mode: 'SOLO'}) });
  const pid = ((await r.json()) as {id:string}).id;
  const resp = await fetch(`${BASE}/api/projects/${pid}/run-details`);
  const data = await resp.json();
  expect(data.status).toBe('NO_TASK');
});
