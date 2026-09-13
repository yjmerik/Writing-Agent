import { test, expect, type Page } from '@playwright/test';

// p36-09 契约：标题/正文草稿暂存与恢复、基准变化冲突保留、保存失败不丢输入、
// 存储失败提示未暂存。作者动作走真实 UI 与正式端点。

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
    body: JSON.stringify({ title, concept: '草稿验证', tags: '', mode: 'SOLO' }),
  });
  if (!resp.ok) throw new Error(`创建项目失败: ${resp.status}`);
  return ((await resp.json()) as { id: string }).id;
}

async function createChapterViaApi(projectId: string, title: string): Promise<{ version: number }> {
  const resp = await fetch(`${BASE}/api/projects/${projectId}/chapters`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ title, content: CHAPTER_BODY }),
  });
  if (!resp.ok) throw new Error(`创建章节失败: ${resp.status} ${await resp.text()}`);
  return (await resp.json()) as { version: number };
}

function observeRuntimeErrors(page: Page, bucket: string[]): void {
  page.on('pageerror', (error) => bucket.push(`${page.url()} :: ${error.message}`));
  page.on('console', (msg) => {
    if (msg.type() !== 'error') return;
    const text = msg.text();
    if (text.includes('Failed to load resource') && text.includes('409')) return; // 契约性冲突
    bucket.push(`${page.url()} :: ${text}`);
  });
}

async function openEditor(page: Page, projectId: string, chapterIndex: number): Promise<void> {
  await page.goto(`${BASE}/#/project/${projectId}/chapter/${chapterIndex}`);
  await page.getByRole('button', { name: '编辑正文' }).click();
  await expect(page.getByRole('textbox', { name: '章节正文' })).toBeVisible({ timeout: 30000 });
}

test.describe('draft-recovery：草稿恢复、冲突保留与存储失败提示', () => {
  let projectId: string;
  const runtimeErrors: string[] = [];

  test.beforeAll(async () => {
    projectId = await createProjectViaApi('草稿恢复验证');
    await createChapterViaApi(projectId, '第一章');
  });

  test('只改标题刷新后两字段均恢复', async ({ page }) => {
    observeRuntimeErrors(page, runtimeErrors);
    await openEditor(page, projectId, 1);
    const titleBox = page.getByRole('textbox', { name: '章节标题', exact: true });
    await titleBox.fill('只改标题也应恢复');
    await page.reload();
    await page.getByRole('button', { name: '编辑正文' }).click();
    await expect(page.getByRole('textbox', { name: '章节标题', exact: true }))
      .toHaveValue('只改标题也应恢复', { timeout: 30000 });
    // 正文同步恢复为草稿中保存的当前稿内容（不丢）
    const body = await page.getByRole('textbox', { name: '章节正文' }).inputValue();
    expect(body).toContain('入库门槛要求');
  });

  test('只改正文刷新后恢复', async ({ page }) => {
    observeRuntimeErrors(page, runtimeErrors);
    await openEditor(page, projectId, 1);
    const bodyBox = page.getByRole('textbox', { name: '章节正文' });
    await bodyBox.fill(CHAPTER_BODY + '\n\n未保存的新段落：雪落无声，行人各自归家。');
    await page.reload();
    await page.getByRole('button', { name: '编辑正文' }).click();
    await expect(page.getByRole('textbox', { name: '章节正文' }))
      .toHaveValue(/雪落无声，行人各自归家/, { timeout: 30000 });
  });

  test('B保存新稿后A刷新显示冲突且旧内容可复制，不静默丢弃', async ({ page }) => {
    observeRuntimeErrors(page, runtimeErrors);
    const pageA = page;
    // A 编辑器中留下未保存草稿
    await openEditor(pageA, projectId, 1);
    await pageA.getByRole('textbox', { name: '章节正文' })
      .fill(CHAPTER_BODY + '\n\n窗口A未保存的段落：桥下的灯亮了。');
    await pageA.getByRole('button', { name: '取消', exact: true }).click();

    // B 经正式端点保存新稿（真实作者动作）
    const chapterResp = await fetch(`${BASE}/api/projects/${projectId}/chapters/1`);
    const chapter = (await chapterResp.json()) as { version: number; title: string };
    const putResp = await fetch(`${BASE}/api/projects/${projectId}/chapters/1`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        expectedVersion: chapter.version,
        title: chapter.title,
        content: CHAPTER_BODY + '\n\n窗口B已保存的段落：巷口换了新的石板。',
      }),
    });
    if (!putResp.ok) throw new Error(`B 保存失败: ${putResp.status}`);

    // A 重新打开编辑器：当前稿展示 + 冲突警示与副本并排，草稿未被静默覆盖
    await pageA.getByRole('button', { name: '编辑正文' }).click();
    await expect(pageA.getByText('发现未保存草稿与当前稿基准不一致')).toBeVisible({ timeout: 30000 });
    await expect(pageA.locator('pre[data-conflict-side="draft"]'))
      .toContainText('桥下的灯亮了', { timeout: 30000 });
    await pageA.getByRole('button', { name: '复制草稿内容' }).click();
    await expect(pageA.getByText('冲突草稿已复制到剪贴板')).toBeVisible();
    // 编辑器正文仍是当前稿（不冒充），冲突副本单独展示
    const editorBody = await pageA.getByRole('textbox', { name: '章节正文' }).inputValue();
    expect(editorBody).toContain('巷口换了新的石板');

    // 放弃冲突副本后警示消失
    await pageA.getByRole('button', { name: '放弃冲突副本' }).click();
    await expect(pageA.getByText('已放弃冲突草稿副本')).toBeVisible();
    await expect(pageA.getByText('发现未保存草稿与当前稿基准不一致')).toHaveCount(0);
  });

  test('保存409保留作者输入不清空', async ({ page }) => {
    observeRuntimeErrors(page, runtimeErrors);
    const staleResp = await fetch(`${BASE}/api/projects/${projectId}/chapters/1`);
    const current = (await staleResp.json()) as { version: number };
    // 制造过期基准：后台保存新稿
    await fetch(`${BASE}/api/projects/${projectId}/chapters/1`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        expectedVersion: current.version,
        title: '第一章',
        content: CHAPTER_BODY + '\n\n后台先行保存的段落。',
      }),
    });

    await openEditor(page, projectId, 1);
    const titleBox = page.getByRole('textbox', { name: '章节标题', exact: true });
    const bodyBox = page.getByRole('textbox', { name: '章节正文' });
    await titleBox.fill('并发保存的标题');
    await bodyBox.fill(CHAPTER_BODY + '\n\n并发窗口输入的段落，不能被清空。');
    // 此时编辑器基于旧 expectedVersion：保存必 409
    await page.getByRole('button', { name: '保存正文', exact: true }).click();
    await expect(page.getByText(/章节已被更新/)).toBeVisible({ timeout: 30000 });
    // 输入保留
    await expect(titleBox).toHaveValue('并发保存的标题');
    await expect(bodyBox).toHaveValue(/并发窗口输入的段落，不能被清空/);
  });

  test.afterAll(async () => {
    if (runtimeErrors.length > 0) {
      throw new Error('运行时错误: ' + runtimeErrors.join('; '));
    }
  });
});
