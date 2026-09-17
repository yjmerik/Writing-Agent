import { test, expect, type Page, type APIRequestContext } from '@playwright/test';
import path from 'node:path';

const rawBase = process.env.QA_BASE_URL;
if (!rawBase?.trim()) throw new Error('QA_BASE_URL required');
const BASE = rawBase.replace(/\/+$/, '');
const BODY = '雨夜里，来客寻找院中的铜钥匙，旧账册留着尚未解释的线索。'.repeat(22)
  + '\n\n' + '守门人望向街口，等待迟来的消息。'.repeat(15);
const DRAFT = '  保留事实，增强雨夜氛围。\n不要改变人物动机。  ';
const input = (page: Page) => page.getByRole('textbox', { name: '修改要求', exact: true });
const dialog = (page: Page) => page.getByRole('dialog', { name: /让 Agent 修改/ });

async function seed(request: APIRequestContext, chapters = 1) {
  const created = await request.post(`${BASE}/api/projects`, {
    data: { title: 'revision instruction draft', concept: 'synthetic QA', tags: '', mode: 'SOLO' },
  });
  expect(created.ok()).toBeTruthy();
  const project = await created.json();
  for (let chapter = 1; chapter <= chapters; chapter++) {
    const response = await request.post(`${BASE}/api/projects/${project.id}/chapters`, {
      data: { title: `第${chapter}章`, content: BODY },
    });
    expect(response.ok()).toBeTruthy();
  }
  return project.id as string;
}

async function open(page: Page, pid: string, chapter = 1, passage?: number) {
  await page.goto(`${BASE}/#/project/${pid}/chapter/${chapter}`);
  await page.getByRole('button', {
    name: passage ? `让 Agent 修改第 ${passage} 段` : `让 Agent 修改第${chapter}章`, exact: true,
  }).click();
  await expect(input(page)).toBeVisible();
}

async function generate(page: Page) {
  await dialog(page).getByRole('button', { name: '生成预览', exact: true }).click();
  await expect(page.locator('[data-revision-side="after"]')).toBeVisible({ timeout: 30000 });
}

for (const viewport of [{ width: 1280, height: 900 }, { width: 390, height: 844 }]) {
  test(`ungenerated instructions survive reload verbatim at ${viewport.width}px`, async ({ page, request }, info) => {
    await page.setViewportSize(viewport);
    const errors: string[] = [];
    page.on('pageerror', error => errors.push(error.message));
    page.on('console', message => { if (message.type() === 'error') errors.push(message.text()); });
    const pid = await seed(request);
    await open(page, pid);
    await input(page).fill(DRAFT);
    await page.reload();
    await page.getByRole('button', { name: '让 Agent 修改第1章', exact: true }).click();
    await expect(input(page)).toHaveValue(DRAFT);
    await expect(page.locator('[data-revision-side="after"]')).toHaveCount(0);
    await expect(page).toHaveURL(new RegExp(`/project/${pid}/chapter/1`));
    expect(await page.title()).not.toBe('');
    expect(await page.locator('body').innerText()).not.toMatch(/Internal Server Error|Failed to compile/);
    expect(errors).toEqual([]);
    await page.screenshot({ animations: 'disabled', path: path.join(process.env.QA_ARTIFACT_DIR ?? info.outputDir, `draft-${viewport.width}.png`) });
  });
}

test('cancel preserves input; deliberate clearing removes it', async ({ page, request }) => {
  const pid = await seed(request);
  await open(page, pid);
  await input(page).fill(DRAFT);
  await dialog(page).getByRole('button', { name: /^取\s*消$/ }).click();
  await page.getByRole('button', { name: '让 Agent 修改第1章', exact: true }).click();
  await expect(input(page)).toHaveValue(DRAFT);
  await input(page).fill('');
  await page.reload();
  await page.getByRole('button', { name: '让 Agent 修改第1章', exact: true }).click();
  await expect(input(page)).toHaveValue('');
});

test('drafts are isolated by project, chapter, target type and passage', async ({ page, request }) => {
  const p1 = await seed(request, 2);
  const p2 = await seed(request);
  const targets = [
    { pid: p1, chapter: 1, passage: undefined, text: '第一章要求' },
    { pid: p1, chapter: 2, passage: undefined, text: '第二章要求' },
    { pid: p2, chapter: 1, passage: undefined, text: '另一项目要求' },
    { pid: p1, chapter: 1, passage: 1, text: '第一段要求' },
    { pid: p1, chapter: 1, passage: 3, text: '第二段要求' },
  ];
  for (const target of targets) {
    await open(page, target.pid, target.chapter, target.passage);
    await expect(input(page)).toHaveValue('');
    await input(page).fill(target.text);
    await dialog(page).getByRole('button', { name: /^取\s*消$/ }).click();
  }
  for (const target of targets) {
    await open(page, target.pid, target.chapter, target.passage);
    await expect(input(page)).toHaveValue(target.text);
    await dialog(page).getByRole('button', { name: /^取\s*消$/ }).click();
  }
});

test('generation failure keeps the draft across reload', async ({ page, request }) => {
  const pid = await seed(request);
  await page.route('**/revisions/preview', route => route.fulfill({
    status: 503, contentType: 'application/json', body: JSON.stringify({ message: 'QA generation failure' }),
  }));
  await open(page, pid);
  await input(page).fill(DRAFT);
  await dialog(page).getByRole('button', { name: '生成预览', exact: true }).click();
  await expect(dialog(page).getByText(/预览生成失败/)).toBeVisible();
  await expect(input(page)).toHaveValue(DRAFT);
  await page.reload();
  await page.getByRole('button', { name: '让 Agent 修改第1章', exact: true }).click();
  await expect(input(page)).toHaveValue(DRAFT);
});

test('stale candidate returns 409 and preserves instructions after a real source change', async ({ page, request }) => {
  const pid = await seed(request);
  await open(page, pid);
  await input(page).fill(DRAFT);
  await generate(page);
  const current = await (await request.get(`${BASE}/api/projects/${pid}/chapters/1`)).json();
  const edit = await request.put(`${BASE}/api/projects/${pid}/chapters/1`, {
    data: { expectedVersion: current.version, title: current.title, content: BODY + '并发的新稿。' },
  });
  expect(edit.ok()).toBeTruthy();
  const rejected = page.waitForResponse(response => response.url().endsWith('/apply'));
  await dialog(page).getByRole('button', { name: '确认应用', exact: true }).click();
  expect((await rejected).status()).toBe(409);
  await expect(dialog(page).getByText(/当前内容已变化/)).toBeVisible();
  const unchanged = await (await request.get(`${BASE}/api/projects/${pid}/chapters/1`)).json();
  expect(unchanged.content).toBe(BODY + '并发的新稿。');
  expect(unchanged.version).toBe(current.version + 1);
  await expect(input(page)).toHaveValue(DRAFT);
  await page.reload();
  await page.getByRole('button', { name: '让 Agent 修改第1章', exact: true }).click();
  await expect(input(page)).toHaveValue(DRAFT);
});

test('successful application clears only its own instructions', async ({ page, request }) => {
  const pid = await seed(request, 2);
  await open(page, pid, 2);
  await input(page).fill('保留第二章草稿');
  await dialog(page).getByRole('button', { name: /^取\s*消$/ }).click();
  await open(page, pid);
  await input(page).fill(DRAFT);
  await generate(page);
  const before = await (await request.get(`${BASE}/api/projects/${pid}/chapters/1`)).json();
  const applied = page.waitForResponse(response => response.url().endsWith('/apply'));
  await dialog(page).getByRole('button', { name: '确认应用', exact: true }).click();
  expect((await applied).ok()).toBeTruthy();
  await expect(dialog(page)).not.toBeVisible();
  const after = await (await request.get(`${BASE}/api/projects/${pid}/chapters/1`)).json();
  expect(after.version).toBeGreaterThan(before.version);
  await open(page, pid);
  await expect(input(page)).toHaveValue('');
  await dialog(page).getByRole('button', { name: /^取\s*消$/ }).click();
  await open(page, pid, 2);
  await expect(input(page)).toHaveValue('保留第二章草稿');
});

test('restoring an older candidate requires a choice before replacing a newer draft', async ({ page, request }) => {
  const pid = await seed(request);
  await open(page, pid);
  await input(page).fill('旧候选要求');
  await generate(page);
  await input(page).fill('新的尚未生成要求');
  await page.reload();
  await page.getByRole('button', { name: '让 Agent 修改第1章', exact: true }).click();
  await page.getByRole('button', { name: '恢复候选', exact: true }).first().click();
  await expect(page.getByRole('button', { name: '保留当前要求', exact: true })).toBeVisible();
  await page.getByRole('button', { name: '保留当前要求', exact: true }).click();
  await expect(input(page)).toHaveValue('新的尚未生成要求');
  await expect(page.locator('[data-revision-side="after"]')).toHaveCount(0);
  await page.getByRole('button', { name: '恢复候选', exact: true }).first().click();
  await page.getByRole('button', { name: '使用候选要求', exact: true }).click();
  await expect(input(page)).toHaveValue('旧候选要求');
  await expect(page.locator('[data-revision-side="after"]')).toBeVisible();
});

test('unavailable storage preserves memory input and shows a warning', async ({ page, request }) => {
  const pid = await seed(request);
  await page.addInitScript(() => {
    const original = Storage.prototype.setItem;
    Storage.prototype.setItem = function (key, value) {
      if (key.startsWith('writing-agent:revision-instructions:')) throw new DOMException('quota', 'QuotaExceededError');
      return original.call(this, key, value);
    };
  });
  await open(page, pid);
  await input(page).fill(DRAFT);
  await expect(dialog(page).getByText('修改要求尚未暂存，刷新可能丢失')).toBeVisible();
  await expect(input(page)).toHaveValue(DRAFT);
  await dialog(page).getByRole('button', { name: /^取\s*消$/ }).click();
  await page.getByRole('button', { name: '让 Agent 修改第1章', exact: true }).click();
  await expect(input(page)).toHaveValue(DRAFT);
  await expect(dialog(page).getByText('修改要求尚未暂存，刷新可能丢失')).toBeVisible();
});
