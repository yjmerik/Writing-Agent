import { test, expect, type Page } from '@playwright/test';

// p36-08 契约：目录/深链接/页签/浏览器历史同步。
// 保留 #/project/:id/chapter/:n；查询参数 tab=review|world|outline|book|quality|run；
// ProjectWorkspace 路由是所选章与页签的权威来源；?version=V&anchor=HASH 打开对应历史正文，
// 缺历史版明确提示、不退回当前稿冒充；非法章号明确提示并选择存在章；轮询不抢选中章。
// fixture：真实 API 创建项目与 7 章（作者动作走真实 UI/端点）。

const rawBase = process.env.QA_BASE_URL;
if (!rawBase || rawBase.trim() === '') {
  throw new Error('QA_BASE_URL 未设置：浏览器回归拒绝猜测目标地址');
}
const parsed = new URL(rawBase);
if (parsed.hostname !== 'localhost' && parsed.hostname !== '127.0.0.1') {
  throw new Error(`QA_BASE_URL 仅允许 localhost/127.0.0.1，当前: ${parsed.hostname}`);
}
const BASE = rawBase.replace(/\/+$/, '');

const CHAPTER_BODY = '这一章的正文内容，长度满足已完成章节的入库门槛要求。'.repeat(30);

async function createProjectViaApi(title: string): Promise<string> {
  const resp = await fetch(`${BASE}/api/projects`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ title, concept: '导航验证', tags: '', mode: 'SOLO' }),
  });
  if (!resp.ok) throw new Error(`创建项目失败: ${resp.status}`);
  return ((await resp.json()) as { id: string }).id;
}

async function createChapterViaApi(projectId: string, title: string): Promise<void> {
  const resp = await fetch(`${BASE}/api/projects/${projectId}/chapters`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ title, content: CHAPTER_BODY }),
  });
  if (!resp.ok) throw new Error(`创建章节失败: ${resp.status} ${await resp.text()}`);
}

function observeRuntimeErrors(page: Page, bucket: string[]): void {
  page.on('pageerror', (error) => bucket.push(`${page.url()} :: ${error.message}`));
  page.on('console', (msg) => {
    if (msg.type() === 'error') bucket.push(`${page.url()} :: ${msg.text()}`);
  });
}

test.describe('workspace-navigation：目录/深链接/页签/历史同步', () => {
  let projectId: string;
  let otherProjectId: string;
  const runtimeErrors: string[] = [];

  test.beforeAll(async () => {
    projectId = await createProjectViaApi('导航验证-主项目');
    for (const title of ['第一章', '第二章', '第三章', '第四章', '第五章', '第六章', '测试第七章']) {
      await createChapterViaApi(projectId, title);
    }
    otherProjectId = await createProjectViaApi('导航验证-其他项目');
    await createChapterViaApi(otherProjectId, '第一章');
  });

  test('点击第7章→路由同步→刷新仍第7章', async ({ page }) => {
    observeRuntimeErrors(page, runtimeErrors);
    await page.goto(`${BASE}/#/project/${projectId}`);
    await page.getByRole('button', { name: '第7章 测试第七章', exact: true }).click();
    await expect(page).toHaveURL(/\/chapter\/7/);
    await page.reload();
    await expect(page).toHaveURL(/\/chapter\/7/);
    await expect(
      page.getByRole('button', { name: '让 Agent 修改第7章', exact: true }),
    ).toBeVisible({ timeout: 30000 });
  });

  test('切outline→tab参数入URL→刷新仍outline', async ({ page }) => {
    observeRuntimeErrors(page, runtimeErrors);
    await page.goto(`${BASE}/#/project/${projectId}/chapter/3`);
    await page.getByRole('tab', { name: /大纲/ }).click();
    await expect(page).toHaveURL(/tab=outline/);
    await page.reload();
    await expect(page).toHaveURL(/tab=outline/);
    await expect(page.getByRole('tab', { name: /大纲/ }))
      .toHaveAttribute('aria-selected', 'true', { timeout: 30000 });
  });

  test('章间跳转支持后退与前进', async ({ page }) => {
    observeRuntimeErrors(page, runtimeErrors);
    await page.goto(`${BASE}/#/project/${projectId}/chapter/7`);
    await expect(page).toHaveURL(/\/chapter\/7/);
    await page.getByRole('button', { name: '第3章 第三章', exact: true }).click();
    await expect(page).toHaveURL(/\/chapter\/3/);
    await page.goBack();
    await expect(page).toHaveURL(/\/chapter\/7/);
    await page.goForward();
    await expect(page).toHaveURL(/\/chapter\/3/);
  });

  test('后台新增章不抢当前选中焦点', async ({ page }) => {
    observeRuntimeErrors(page, runtimeErrors);
    await page.goto(`${BASE}/#/project/${projectId}/chapter/3`);
    await expect(
      page.getByRole('button', { name: '让 Agent 修改第3章', exact: true }),
    ).toBeVisible({ timeout: 30000 });

    await createChapterViaApi(projectId, '第八章');
    // 手动刷新列表后：新章可见，但选中章与路由不被抢占
    await page.getByRole('button', { name: '刷新' }).click();
    await expect(page.getByRole('button', { name: '第8章 第八章', exact: true }))
      .toBeVisible({ timeout: 30000 });
    await expect(page).toHaveURL(/\/chapter\/3/);
    await expect(
      page.getByRole('button', { name: '让 Agent 修改第3章', exact: true }),
    ).toBeVisible();
  });

  test('非法章号给出明确提示并选择存在章（跨项目不串章）', async ({ page }) => {
    observeRuntimeErrors(page, runtimeErrors);
    // 其他项目的第 99 章不存在：明确提示，回退到存在章，而非显示空/串台内容
    await page.goto(`${BASE}/#/project/${otherProjectId}/chapter/99`);
    await expect(page.getByText(/第 99 章不存在/)).toBeVisible({ timeout: 30000 });
    await expect(
      page.getByRole('button', { name: '让 Agent 修改第1章', exact: true }),
    ).toBeVisible({ timeout: 30000 });
  });

  test('version 深链接打开对应历史正文，缺失版本明确提示', async ({ page }) => {
    observeRuntimeErrors(page, runtimeErrors);
    // 为第 1 章制造新稿（正式编辑端点）：编辑前版本 V 会留下不可变快照
    const chapterResp = await fetch(`${BASE}/api/projects/${projectId}/chapters/1`);
    const chapter = (await chapterResp.json()) as { version: number; title: string };
    const versionBeforeEdit = chapter.version;
    const putResp = await fetch(`${BASE}/api/projects/${projectId}/chapters/1`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        expectedVersion: versionBeforeEdit,
        title: chapter.title,
        content: CHAPTER_BODY + '作者修订一次，产生新的版本。',
      }),
    });
    if (!putResp.ok) throw new Error(`制造新稿失败: ${putResp.status}`);

    await page.goto(`${BASE}/#/project/${projectId}/chapter/1?version=${versionBeforeEdit}`);
    await expect(page.getByText(/版本记录/)).toBeVisible({ timeout: 30000 });
    await expect(page.getByText(new RegExp(`^v${versionBeforeEdit} · `))).toBeVisible({ timeout: 30000 });

    await page.goto(`${BASE}/#/project/${projectId}/chapter/1?version=99`);
    await expect(page.getByText(/第 99 版历史不存在/)).toBeVisible({ timeout: 30000 });
  });

  test.afterAll(async () => {
    if (runtimeErrors.length > 0) {
      throw new Error('运行时错误: ' + runtimeErrors.join('; '));
    }
  });
});
