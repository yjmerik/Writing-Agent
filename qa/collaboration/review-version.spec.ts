import { test, expect, type Page } from '@playwright/test';

// p36-05 契约：两个真实窗口 A 显示 v2、B 保存 v3 后，A 批准必须 409 且仍不能续写；
// A 重新读取审阅（409 后强制重读或显式刷新）后才可批准 v3；
// 章节卡、章节类检查点卡与窄屏抽屉共用同一「批准当前稿」handler；390px 同样验证。
// fixture：空 tags 互动项目 → 协作 start → 批准初始设定 → 协作 start → scenario 首章人审。
// 作者动作全部走真实 API/真实 UI；不直接改数据库伪造状态。

const rawBase = process.env.QA_BASE_URL;
if (!rawBase || rawBase.trim() === '') {
  throw new Error('QA_BASE_URL 未设置：浏览器回归拒绝猜测目标地址');
}
const parsed = new URL(rawBase);
if (parsed.hostname !== 'localhost' && parsed.hostname !== '127.0.0.1') {
  throw new Error(`QA_BASE_URL 仅允许 localhost/127.0.0.1，当前: ${parsed.hostname}`);
}
const BASE = rawBase.replace(/\/+$/, '');

async function apiPost(path: string, body?: unknown): Promise<Response> {
  return fetch(`${BASE}${path}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
}

async function waitFor<T>(probe: () => Promise<T | null>, timeoutMs: number, label: string): Promise<T> {
  const deadline = Date.now() + timeoutMs;
  let lastError: unknown = null;
  while (Date.now() < deadline) {
    try {
      const value = await probe();
      if (value !== null && value !== undefined && value !== false) return value as T;
    } catch (e) {
      lastError = e;
    }
    await new Promise((r) => setTimeout(r, 1000));
  }
  throw new Error(`等待超时(${timeoutMs}ms): ${label}${lastError ? ` · 最后错误: ${String(lastError)}` : ''}`);
}

interface Fixture {
  projectId: string;
  version: number;
  contentHash: string;
  reviewSetHash: string;
}

async function fetchJson(path: string): Promise<unknown | null> {
  const resp = await fetch(`${BASE}${path}`);
  if (!resp.ok) return null;
  const text = await resp.text();
  if (!text) return null; // 后端对缺失资源返回 200 + 空 body
  try {
    return JSON.parse(text);
  } catch {
    return null;
  }
}

/** 真实作者路径建立"第1章待人审"状态，并返回审阅上下文要素（供 409 后核对）。
 *  scenario harsh 注入按网关调用序只覆盖前 2 次检查：同一服务上的后续项目首章
 *  检查通过后停在 AFTER_CHAPTER 检查点——同样是"待作者审阅"，fixture 两种形态都接受。 */
async function createReviewFixture(title: string): Promise<Fixture> {
  const createResp = await fetch(`${BASE}/api/projects`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ title, concept: '双窗口批准竞态验证', tags: '', mode: 'INTERACTIVE' }),
  });
  if (!createResp.ok) throw new Error(`创建项目失败: ${createResp.status} ${await createResp.text()}`);
  const project = (await createResp.json()) as { id: string };
  const id = project.id;

  const startResp = await apiPost(`/api/projects/${id}/collaboration/start`, {});
  if (!startResp.ok) throw new Error(`协作启动失败: ${startResp.status} ${await startResp.text()}`);

  // 等待初始设定 checkpoint（scenario 构思）
  const checkpointId = await waitFor(async () => {
    const cps = (await fetchJson(`/api/projects/${id}/checkpoints`)) as
      Array<{ id: string; type: string; status: string }> | null;
    return cps?.find((c) => c.status === 'PENDING' && c.type === 'AFTER_INITIAL_SETUP')?.id ?? null;
  }, 180000, '初始设定 checkpoint');

  // 初始设定/卷计划审批保留独立流程（fixture 作者动作走真实 API）
  const approveResp = await apiPost(`/api/projects/${id}/checkpoints/${checkpointId}/approve`);
  if (!approveResp.ok) throw new Error(`初始设定批准失败: ${approveResp.status}`);

  // 再次协作 start → scenario 撰写首章 → 人审暂停（屏障或 AFTER_CHAPTER 检查点）
  const secondStart = await apiPost(`/api/projects/${id}/collaboration/start`, {});
  if (!secondStart.ok) throw new Error(`第二次协作启动失败: ${secondStart.status} ${await secondStart.text()}`);

  await waitFor(async () => {
    const chapter = (await fetchJson(`/api/projects/${id}/chapters/1`)) as { status?: string } | null;
    if (chapter?.status === 'NEEDS_MANUAL_REVIEW') return chapter;
    const cps = (await fetchJson(`/api/projects/${id}/checkpoints`)) as
      Array<{ type: string; status: string; payload?: string }> | null;
    const pendingChapterCp = cps?.find((c) => c.status === 'PENDING' && c.type === 'AFTER_CHAPTER');
    return pendingChapterCp ? chapter : null;
  }, 300000, '第1章进入待人审状态（屏障或章节检查点）');

  const context = (await fetchJson(`/api/projects/${id}/chapters/1/review-context`)) as {
    identity: { version: number; contentHash: string };
    reviewSetHash: string;
    barrierIds: string[];
    pendingCheckpointIds: string[];
  } | null;
  if (!context) throw new Error('review-context 读取失败');
  if (!context.barrierIds?.length && !context.pendingCheckpointIds?.length) {
    throw new Error('fixture 结束时必须存在待审项（屏障或章节检查点）');
  }
  return {
    projectId: id,
    version: context.identity.version,
    contentHash: context.identity.contentHash,
    reviewSetHash: context.reviewSetHash,
  };
}

function observeRuntimeErrors(page: Page, bucket: string[]): void {
  page.on('pageerror', (error) => bucket.push(`${page.url()} :: ${error.message}`));
  page.on('console', (msg) => {
    if (msg.type() !== 'error') return;
    const text = msg.text();
    // 本 spec 显式等待并断言 /review-decisions 的 409（契约性旧稿拒绝）；
    // 浏览器会把它记为网络 console error——属"已解释"，其余 console.error 仍必须失败。
    if (text.includes('Failed to load resource') && text.includes('409')) return;
    bucket.push(`${page.url()} :: ${text}`);
  });
}

async function openChapter(page: Page, projectId: string): Promise<void> {
  await page.goto(`${BASE}/#/project/${projectId}/chapter/1`);
  // 等待正文与审阅上下文就绪：批准按钮可用。
  // barrier 路径下章节卡与章节类检查点卡都可出现「批准当前稿」——同一 handler，取其一。
  const approve = page.getByRole('button', { name: '批准当前稿', exact: true }).first();
  await expect(approve).toBeVisible({ timeout: 60000 });
  await expect(approve).toBeEnabled({ timeout: 60000 });
}

async function approveButton(page: Page) {
  return page.getByRole('button', { name: '批准当前稿', exact: true }).first();
}

async function continueButton(page: Page) {
  return page.getByRole('button', { name: '继续协作', exact: true });
}

test.describe('review-version：章节审阅按钮统一已读版本', () => {
  test('桌面 1280x900：A显示v2、B保存v3，A批准409且不能续写，重读后可批准v3', async ({ browser }) => {
    test.setTimeout(420000);
    const runtimeErrors: string[] = [];
    const contextA = await browser.newContext({ viewport: { width: 1280, height: 900 } });
    const contextB = await browser.newContext({ viewport: { width: 1280, height: 900 } });
    const pageA = await contextA.newPage();
    const pageB = await contextB.newPage();
    observeRuntimeErrors(pageA, runtimeErrors);
    observeRuntimeErrors(pageB, runtimeErrors);

    const fixture = await createReviewFixture('审阅竞态-桌面');

    // A 打开第1章（显示当前稿，批准 token 就绪）
    await openChapter(pageA, fixture.projectId);
    const versionTagA = pageA.locator('.ant-tag-purple').filter({ hasText: /^v\d+$/ }).first();
    await expect(versionTagA).toHaveText(`v${fixture.version}`);
    const continueA = await continueButton(pageA);
    await expect(continueA).toBeVisible({ timeout: 30000 });
    await expect(continueA).toBeDisabled({ timeout: 30000 });

    // B 经编辑器保存新稿（作者动作走真实 UI；按钮名匹配含图标的 accessible name）
    await pageB.goto(`${BASE}/#/project/${fixture.projectId}/chapter/1`);
    await pageB.getByRole('button', { name: '编辑正文' }).click();
    const contentBox = pageB.getByRole('textbox', { name: '章节正文' });
    await expect(contentBox).toBeVisible({ timeout: 30000 });
    const original = await contentBox.inputValue();
    await contentBox.fill(original + '\n\n作者在另一窗口追加的段落：两人在雨夜重逢，旧约未改。');
    await pageB.getByRole('button', { name: '保存正文', exact: true }).click();
    await expect(pageB.getByText(/已保存为 v\d+/)).toBeVisible({ timeout: 60000 });

    // A 不刷新页面直接批准：必须 409
    const approveA = await approveButton(pageA);
    const staleResponse = pageA.waitForResponse((r) => r.url().endsWith('/review-decisions'));
    await approveA.click();
    expect((await staleResponse).status()).toBe(409);

    // 409 后仍不能续写（新稿屏障未解除），且 A 被强制重读到新稿
    await expect(continueA).toBeDisabled({ timeout: 30000 });
    await expect(versionTagA).toHaveText(`v${fixture.version + 1}`, { timeout: 60000 });
    // 系统不自动重试批准：只有作者再次点击才发出第二次请求
    await expect(approveA).toBeEnabled({ timeout: 60000 });

    const freshResponse = pageA.waitForResponse((r) => r.url().endsWith('/review-decisions'));
    await approveA.click();
    expect((await freshResponse).status()).toBe(200);

    // 批准 v3 后屏障解除，续写解锁
    await expect(continueA).toBeEnabled({ timeout: 30000 });

    expect(runtimeErrors, '运行时错误: ' + runtimeErrors.join('; ')).toEqual([]);
    await contextA.close();
    await contextB.close();
  });

  test('窄屏 390x844：同一竞态与批准路径等价', async ({ browser }) => {
    test.setTimeout(420000);
    const runtimeErrors: string[] = [];
    const contextA = await browser.newContext({ viewport: { width: 390, height: 844 } });
    const contextB = await browser.newContext({ viewport: { width: 390, height: 844 } });
    const pageA = await contextA.newPage();
    const pageB = await contextB.newPage();
    observeRuntimeErrors(pageA, runtimeErrors);
    observeRuntimeErrors(pageB, runtimeErrors);

    const fixture = await createReviewFixture('审阅竞态-窄屏');

    await openChapter(pageA, fixture.projectId);
    const versionTagA = pageA.locator('.ant-tag-purple').filter({ hasText: /^v\d+$/ }).first();
    await expect(versionTagA).toHaveText(`v${fixture.version}`);
    const continueA = await continueButton(pageA);
    await expect(continueA).toBeDisabled({ timeout: 30000 });

    await pageB.goto(`${BASE}/#/project/${fixture.projectId}/chapter/1`);
    await pageB.getByRole('button', { name: '编辑正文' }).click();
    const contentBox = pageB.getByRole('textbox', { name: '章节正文' });
    await expect(contentBox).toBeVisible({ timeout: 30000 });
    const original = await contentBox.inputValue();
    await contentBox.fill(original + '\n\n窄屏窗口追加的段落：她在灯下重读旧信，落款已被雨水洇开。');
    await pageB.getByRole('button', { name: '保存正文', exact: true }).click();
    await expect(pageB.getByText(/已保存为 v\d+/)).toBeVisible({ timeout: 60000 });

    const approveA = await approveButton(pageA);
    const staleResponse = pageA.waitForResponse((r) => r.url().endsWith('/review-decisions'));
    await approveA.click();
    expect((await staleResponse).status()).toBe(409);
    await expect(continueA).toBeDisabled({ timeout: 30000 });
    await expect(versionTagA).toHaveText(`v${fixture.version + 1}`, { timeout: 60000 });
    await expect(approveA).toBeEnabled({ timeout: 60000 });

    const freshResponse = pageA.waitForResponse((r) => r.url().endsWith('/review-decisions'));
    await approveA.click();
    expect((await freshResponse).status()).toBe(200);
    await expect(continueA).toBeEnabled({ timeout: 30000 });

    expect(runtimeErrors, '运行时错误: ' + runtimeErrors.join('; ')).toEqual([]);
    await contextA.close();
    await contextB.close();
  });
});
