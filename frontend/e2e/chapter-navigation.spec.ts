import { test, expect, Page } from '@playwright/test';
import { createProject, setPreferences, conceive, writeChapters } from './helpers';

/**
 * p21-05 / p23-04: 章节懒加载——初始审核加载只请求一次元数据；选中章节恰好请求该章正文；
 * 切章不重取元数据/checkpoints/status，也不取未选中章节的正文。
 */
test('懒加载：元数据一次、正文按选中章懒取、切章不重取', async ({ request, page }) => {
  const id = await createProject(request, 'e2e-lazy');
  await setPreferences(request, id, { targetChapters: 3 });
  await conceive(request, id);
  await writeChapters(request, id, 2);

  await page.goto(`/project/${id}`);
  const metaRequests: string[] = [];
  const bodyRequests: string[] = [];
  const checkpointRequests: string[] = [];
  const statusRequests: string[] = [];
  page.on('request', (req) => {
    const u = req.url();
    if (/\/chapters\?page/.test(u)) metaRequests.push(u);
    else if (/\/chapters\/\d+$/.test(u)) bodyRequests.push(u);
    else if (/\/checkpoints/.test(u)) checkpointRequests.push(u);
    else if (/\/status$/.test(u)) statusRequests.push(u);
  });

  // 打开章节管理——初始加载：恰好一次元数据请求 + 选中章（第1章）正文一次
  await page.getByRole('tab', { name: /章节管理/ }).click();
  await expect(page.getByText(/📖 章节 \(/)).toBeVisible();
  await page.waitForTimeout(800);
  expect(metaRequests.length).toBe(1);
  expect(bodyRequests.filter((u) => /\/chapters\/1$/.test(u)).length).toBe(1);
  expect(bodyRequests.filter((u) => /\/chapters\/2$/.test(u)).length).toBe(0);

  const cpAfterInitial = checkpointRequests.length;
  const statusAfterInitial = statusRequests.length;

  // 切到第2章：恰好新增 /chapters/2 一次；元数据零新增；checkpoints/status 零新增
  await page.getByRole('button', { name: /第2章/ }).first().click();
  await page.waitForTimeout(800);
  expect(metaRequests.length).toBe(1, '切章不得重取元数据');
  expect(bodyRequests.filter((u) => /\/chapters\/2$/.test(u)).length).toBe(1);
  expect(checkpointRequests.length).toBe(cpAfterInitial, '切章不得重取 checkpoints');
  expect(statusRequests.length).toBe(statusAfterInitial, '切章不得重取 status');

  // 正文确实展示（第2章标题出现在正文卡）
  await expect(page.getByRole('heading', { name: /第2章/ })).toBeVisible();
  void Page; // 保持 Page 导入（类型用途）
});
