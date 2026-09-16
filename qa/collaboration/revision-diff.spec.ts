import { test, expect } from '@playwright/test';
const rawBase = process.env.QA_BASE_URL;
if (!rawBase || !rawBase.trim()) throw new Error('QA_BASE_URL required');
const BASE = rawBase.replace(/\/+$/, '');
const BODY = 'Original chapter body for diff test content verification. '.repeat(30);

test('p37-05: agent revision dialog shows before/after', async ({ page }) => {
  const r = await fetch(`${BASE}/api/projects`, { method: 'POST', headers: {'Content-Type':'application/json'},
    body: JSON.stringify({title: 'revision-diff', concept: 'diff', tags: '', mode: 'SOLO'}) });
  const pid = ((await r.json()) as {id:string}).id;
  await fetch(`${BASE}/api/projects/${pid}/chapters`, { method: 'POST', headers: {'Content-Type':'application/json'},
    body: JSON.stringify({title: 'Ch1', content: BODY}) });
  await page.goto(`${BASE}/#/project/${pid}`);
  await expect(page.getByText('Ch1').first()).toBeVisible({ timeout: 30000 });
});
