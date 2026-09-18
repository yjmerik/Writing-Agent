import { test, expect, type Page, type APIRequestContext } from '@playwright/test';

const BASE = process.env.QA_BASE_URL;
if (!BASE) throw new Error('QA_BASE_URL required');
const BODY = ('雨夜的旧案留下了新的线索，来客将铜钥匙放在案上，等待守门人说明原委。'.repeat(18) + '\n\n').repeat(3);
const LONG = '这条审查意见需要完整显示，人物动机与历史事实应逐一核对。'.repeat(12);

async function seed(request: APIRequestContext, chapters = 2) {
  const created = await request.post(`${BASE}/api/projects`, { data: { title: '审阅体验回归', concept: 'QA', tags: '', mode: 'SOLO' } });
  expect(created.ok()).toBeTruthy();
  const { id } = await created.json();
  for (let i = 1; i <= chapters; i++) {
    const saved = await request.post(`${BASE}/api/projects/${id}/chapters`, { data: { title: `长标题第${i}章：雨夜旧案与人物的选择`, content: BODY } });
    expect(saved.ok()).toBeTruthy();
  }
  return id as string;
}

async function open(page: Page, id: string) {
  await page.goto(`${BASE}/#/project/${id}/chapter/1`);
  await expect(page.locator('[data-paragraph-index="0"]').first()).toBeVisible();
}
const panel = (page: Page) => page.getByRole('dialog', { name: /审查面板/ });

test('审阅内容同页签打开、关闭和再次打开完整面板', async ({ page, request }) => {
  const id = await seed(request);
  await open(page, id);
  await page.getByRole('button', { name: /^审阅内容/ }).click();
  await expect(panel(page)).toBeVisible();
  await panel(page).getByText(/意见（/).click();
  await expect(panel(page).getByRole('textbox', { name: '登记意见', exact: true })).toBeVisible();
  await panel(page).getByRole('textbox', { name: '登记意见', exact: true }).fill('保留当前未提交意见');
  await panel(page).getByRole('button', { name: '返回正文', exact: true }).click();
  await expect(panel(page)).not.toBeVisible();
  await page.getByRole('button', { name: /^审阅内容/ }).click();
  await expect(panel(page).getByRole('textbox', { name: '登记意见', exact: true })).toHaveValue('保留当前未提交意见');
});

test('其他页签待审入口定位章节且打开审查', async ({ page, request }) => {
  const id = await seed(request);
  await page.route(`**/api/projects/${id}/workspace`, async route => {
    const response = await route.fetch(); const data = await response.json();
    await route.fulfill({ json: { ...data, collabState: 'AWAITING_REVIEW', todoList: [{ chapterIndex: 2, chapterVersion: 1 }] } });
  });
  await open(page, id);
  await page.getByRole('tab', { name: /大纲/ }).click();
  await expect(page).toHaveURL(/tab=outline/);
  await page.getByRole('button', { name: /第 2 章第 1 稿待审/ }).click();
  await expect(page).toHaveURL(new RegExp(`/project/${id}/chapter/2$`));
  await expect(page.getByRole('tab', { name: /章节管理/ })).toHaveAttribute('aria-selected', 'true');
  await expect(panel(page)).toBeVisible();
});

test('1280px正文宽度、标题及专注阅读恢复', async ({ page, request }) => {
  await page.setViewportSize({ width: 1280, height: 900 });
  const id = await seed(request);
  await open(page, id);
  const paragraph = page.locator('[data-paragraph-index="0"]').first();
  expect((await paragraph.boundingBox())!.width).toBeGreaterThanOrEqual(550);
  await expect(page.getByRole('heading', { name: /第1章.*雨夜旧案/, level: 3 })).toBeVisible();
  await expect(page.getByRole('button', { name: /返回列表/ })).toBeVisible();
  await page.getByRole('button', { name: '专注阅读', exact: true }).click();
  await expect.poll(async () => (await paragraph.boundingBox())!.width).toBeGreaterThanOrEqual(850);
  await expect(page.getByRole('button', { name: '编辑第 1 段', exact: true })).not.toBeVisible();
  await page.screenshot({ path: `${process.env.QA_ARTIFACT_DIR}/reading-focus.png`, animations: 'disabled' });
  await expect(page.getByRole('button', { name: /返回列表/ })).not.toBeVisible();
  await page.getByRole('button', { name: '退出专注阅读', exact: true }).click();
  await expect(page.getByRole('button', { name: /返回列表/ })).toBeVisible();
});

for (const width of [1440, 390]) {
  test(`${width}px长审查报告清晰且无横向溢出`, async ({ page, request }, info) => {
    await page.setViewportSize({ width, height: 900 });
    const errors: string[] = []; page.on('pageerror', e => errors.push(e.message));
    page.on('console', m => { if (m.type() === 'error') errors.push(m.text()); });
    const id = await seed(request);
    await page.route(`**/api/projects/${id}/chapters/*/quality`, route => route.fulfill({ json: {
      grade: 'B', reportCurrent: true, reportForVersion: 1, clicheCount: 0, topCliches: [],
      review: { overallScore: 7, engagement: 8, coherence: 7, character: 6, hook: 7, summary: LONG, improvement: '建议分段核查。\n逐条给出理由。' },
      issues: [{ severity: 'HIGH', type: 'CHARACTER_MOTIVATION_CONTINUITY', description: LONG }],
    } }));
    await open(page, id);
    await page.getByRole('button', { name: /^审阅内容/ }).click();
    await expect(panel(page)).toBeVisible();
    await expect(panel(page).getByText('角色塑造', { exact: true })).toBeVisible();
    expect((await panel(page).getByText('角色塑造', { exact: true }).boundingBox())!.width).toBeGreaterThanOrEqual(56);
    await panel(page).getByText(/检测问题（/).click();
    await expect(panel(page).getByText('CHARACTER_MOTIVATION_CONTINUITY', { exact: true })).toBeVisible();
    expect(await panel(page).evaluate(el => el.scrollWidth <= el.clientWidth + 1)).toBeTruthy();
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBeTruthy();
    expect(await page.title()).not.toBe('');
    expect(await page.locator('body').innerText()).not.toMatch(/Internal Server Error|Failed to compile/);
    expect(errors).toEqual([]);
    await page.screenshot({ path: `${process.env.QA_ARTIFACT_DIR ?? info.outputDir}/review-${width}.png`, animations: 'disabled' });
  });
}

test('没有章节时审阅入口仍显示明确空状态', async ({ page, request }) => {
  const id = await seed(request, 0);
  await page.goto(`${BASE}/#/project/${id}`);
  await page.getByRole('button', { name: /^审阅内容/ }).click();
  await expect(panel(page)).toBeVisible();
  await expect(panel(page).getByText(/暂无章节/)).toBeVisible();
});
