import { test, expect } from '@playwright/test';
const rawBase = process.env.QA_BASE_URL;
if (!rawBase || !rawBase.trim()) throw new Error('QA_BASE_URL required');
const BASE = rawBase.replace(/\/+$/, '');

test('p38-08: book review tab shows coverage list', async ({ page }) => {
  const r = await fetch(`${BASE}/api/projects`, { method: 'POST', headers: {'Content-Type':'application/json'},
    body: JSON.stringify({title: 'edition-export', concept: 'export', tags: '', mode: 'SOLO'}) });
  const pid = ((await r.json()) as {id:string}).id;
  await page.goto(`${BASE}/#/project/${pid}`);
  await page.getByRole('tab', { name: /成书审查/ }).click();
  await expect(page.getByText(/成书审查/).first()).toBeVisible({ timeout: 30000 });
});
