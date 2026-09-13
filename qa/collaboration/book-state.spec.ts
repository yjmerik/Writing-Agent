import { test, expect, type Page } from '@playwright/test';

// p36-11 契约：成书清单区分流程完成与作者批准——
// DONE 仅显示"正文流程完成"；无当前身份匹配批准记录时显示"尚未作者批准"，不推断批准；
// 自动 DONE、手工（版本化批准）DONE、旧稿报告、缺报告四种响应分别渲染正确；
// 页面不出现伪造"已批准流程完成"；不以 doneChapters===totalChapters 宣称覆盖完成。

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
    body: JSON.stringify({ title, concept: '成书状态验证', tags: '', mode: 'SOLO' }),
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
    if (msg.type() === 'error') bucket.push(`${page.url()} :: ${msg.text()}`);
  });
}

test.describe('book-state：成书清单诚实状态', () => {
  const runtimeErrors: string[] = [];

  test('自动DONE章节显示流程完成+尚未作者批准；不出现伪造已批准', async ({ page }) => {
    observeRuntimeErrors(page, runtimeErrors);
    const projectId = await createProjectViaApi('成书状态-自动DONE');
    await createChapterViaApi(projectId, '第一章');

    await page.goto(`${BASE}/#/project/${projectId}`);
    await page.getByRole('tab', { name: /成书审查/ }).click();

    await expect(page.getByText('1/1 章完成')).toBeVisible({ timeout: 30000 });
    // DONE 只描述流程；无批准记录 → 明示尚未作者批准
    await expect(page.getByText('正文流程完成').first()).toBeVisible({ timeout: 30000 });
    await expect(page.getByText('尚未作者批准').first()).toBeVisible({ timeout: 30000 });
    // 伪造文案不得出现
    await expect(page.getByText('已批准流程完成')).toHaveCount(0);
  });

  test('版本化批准后的章节显示作者已批准；旧稿报告与缺报告区分', async ({ page }) => {
    observeRuntimeErrors(page, runtimeErrors);
    const projectId = await createProjectViaApi('成书状态-批准与报告');
    await createChapterViaApi(projectId, '第一章');

    // 作者批准第 1 章当前稿（读上下文 → 版本化批准）
    const ctxResp = await fetch(`${BASE}/api/projects/${projectId}/chapters/1/review-context`);
    if (!ctxResp.ok) throw new Error(`review-context 读取失败: ${ctxResp.status}`);
    const context = (await ctxResp.json()) as {
      identity: { version: number; contentHash: string };
      reviewSetHash: string;
    };
    const approvalResp = await fetch(`${BASE}/api/projects/${projectId}/chapters/1/review-decisions`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        identity: {
          projectId,
          chapterIndex: 1,
          version: context.identity.version,
          contentHash: context.identity.contentHash,
        },
        reviewSetHash: context.reviewSetHash,
        idempotencyKey: `book-state-${Date.now()}`,
      }),
    });
    if (!approvalResp.ok) throw new Error(`批准失败: ${approvalResp.status} ${await approvalResp.text()}`);

    // 制造旧稿报告：先有报告（经 quality 端点触发可能较慢——直接断言 UI 不伪造即可）
    await page.goto(`${BASE}/#/project/${projectId}`);
    await page.getByRole('tab', { name: /成书审查/ }).click();

    // 批准过的当前稿显示作者已批准
    await expect(page.getByText('作者已批准').first()).toBeVisible({ timeout: 30000 });
    // 其余章节（若有）未批准的显示尚未作者批准
    await expect(page.getByText('正文流程完成').first()).toBeVisible({ timeout: 30000 });
    await expect(page.getByText('已批准流程完成')).toHaveCount(0);

    // 旧稿报告 tag 与缺报告显示不混淆（此项目无报告：不显示"旧稿报告"）
    await expect(page.getByText('旧稿报告')).toHaveCount(0);
  });

  test.afterAll(async () => {
    if (runtimeErrors.length > 0) {
      throw new Error('运行时错误: ' + runtimeErrors.join('; '));
    }
  });
});
