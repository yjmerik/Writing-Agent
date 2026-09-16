import { test, expect } from '@playwright/test';
const rawBase = process.env.QA_BASE_URL;
if (!rawBase || !rawBase.trim()) throw new Error('QA_BASE_URL required');
const BASE = rawBase.replace(/\/+$/, '');
const BODY = 'Test chapter content for candidate recovery verification. '.repeat(30);

async function createProject(title: string) {
  const r = await fetch(`${BASE}/api/projects`, { method: 'POST', headers: {'Content-Type':'application/json'},
    body: JSON.stringify({title, concept: 'candidate recovery', tags: '', mode: 'SOLO'}) });
  return ((await r.json()) as {id:string}).id;
}
async function addChapter(pid: string, title: string) {
  await fetch(`${BASE}/api/projects/${pid}/chapters`, { method: 'POST', headers: {'Content-Type':'application/json'},
    body: JSON.stringify({title, content: BODY}) });
}

test('p37-04: candidate persists across page reload', async ({ page }) => {
  const pid = await createProject('candidate-recovery');
  await addChapter(pid, 'Chapter 1');
  await page.goto(`${BASE}/#/project/${pid}`);
  await page.getByRole('button', { name: '编辑正文' }).click();
  const body = page.getByRole('textbox', { name: '章节正文' });
  await body.fill(BODY + '\nUnsaved draft paragraph for recovery test.');
  await page.reload();
  await page.getByRole('button', { name: '编辑正文' }).click();
  await expect(page.getByRole('textbox', { name: '章节正文' })).toHaveValue(/recovery test/, { timeout: 30000 });
});
