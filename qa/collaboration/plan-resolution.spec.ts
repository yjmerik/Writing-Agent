import { test, expect } from '@playwright/test';
const rawBase = process.env.QA_BASE_URL;
if (!rawBase || !rawBase.trim()) throw new Error('QA_BASE_URL required');
const BASE = rawBase.replace(/\/+$/, '');

test('p38-04: plan target preview shows in UI', async ({ page }) => {
  const r = await fetch(`${BASE}/api/projects`, { method: 'POST', headers: {'Content-Type':'application/json'},
    body: JSON.stringify({title: 'plan-resolution', concept: 'plan', tags: '', mode: 'SOLO'}) });
  const pid = ((await r.json()) as {id:string}).id;
  await page.goto(`${BASE}/#/project/${pid}`);
  await page.getByRole('tab', { name: /大纲/ }).click();
  await expect(page.getByText('篇幅与计划')).toBeVisible({ timeout: 30000 });
});
