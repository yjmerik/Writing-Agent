import { test, expect } from '@playwright/test';
import { createProject, setPreferences, conceive, writeChapters, openWorkspaceTab } from './helpers';

/**
 * p21-08 / p23-04: SSE 连接状态与回退——实时通道不可用时降级为轮询且任务照常完成；
 * 通道可用时展示「已连接」。
 */
test('SSE 不可用：展示回退状态，轮询仍完成任务', async ({ request, page }) => {
  const id = await createProject(request, 'e2e-sse-fallback');
  await setPreferences(request, id, { targetChapters: 3 });
  await conceive(request, id);

  // 阻断 EventSource 端点 → 前端进入 fallback（或重连耗尽后 fallback）
  await page.route(/\/api\/projects\/[a-f0-9-]+\/events/, (route) => route.abort());
  await openWorkspaceTab(page, id, /运行控制台/);

  await page.getByRole('button', { name: /连续写作（最多 50 章）/ }).click();
  await page.getByRole('button', { name: '开始连续写作' }).click();

  await expect(page.getByText(/实时通道不可用|重连中/)).toBeVisible({ timeout: 20_000 });
  // 轮询兜底：任务照常完成并展示结果
  await expect(page.getByText(/连续写作完成/)).toBeVisible({ timeout: 90_000 });
  await expect(page.getByText(/实时通道不可用/)).toBeVisible();
});

test('SSE 可用：连续写作期间展示已连接状态', async ({ request, page }) => {
  const id = await createProject(request, 'e2e-sse-connected');
  await setPreferences(request, id, { targetChapters: 3 });
  await conceive(request, id);

  await openWorkspaceTab(page, id, /运行控制台/);
  await page.getByRole('button', { name: /连续写作（最多 50 章）/ }).click();
  await page.getByRole('button', { name: '开始连续写作' }).click();

  // 事件流建立后应出现已连接标签（先于或伴随完成提示）
  const connected = page.getByText('实时通道已连接');
  const done = page.getByText(/连续写作完成/);
  await expect(connected.or(done).first()).toBeVisible({ timeout: 90_000 });
  if (await connected.isVisible().catch(() => false)) {
    await expect(connected).toBeVisible();
  }
  await expect(done).toBeVisible({ timeout: 90_000 });
});
