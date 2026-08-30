import { test, expect } from '@playwright/test';
import { createProject, setPreferences, conceive, writeChapters } from './helpers';

/**
 * p23-04: 人工复核浏览器场景——harsh 档（第1章注入 HIGH 连续性矛盾）：
 * 写作在待人工章节暂停（任务 PAUSED），复核「已处理」后可继续写。
 * 仅 HARSH=1 档运行：npm run e2e:manual-review
 */
test.skip(!process.env.HARSH, 'manual-review spec runs only in HARSH profile');

test('待人工章节在 UI 暂停并可复核放行', async ({ request, page }) => {
  const id = await createProject(request, 'e2e-manual-review');
  await setPreferences(request, id, { targetChapters: 3 });
  await conceive(request, id);

  const task = await writeChapters(request, id, 3);
  expect(String(task.status)).toBe('PAUSED');
  expect(String(task.errorMessage)).toContain('人工复核');

  // 章节管理：第1章标记待人审，且提供「已处理」复核入口
  await openWorkspaceTab(page, id, /章节管理/);
  const ch1Item = page.getByRole('button', { name: /第1章/ }).first();
  await expect(ch1Item).toBeVisible();
  await expect(page.getByText('待人审')).toBeVisible();

  await page.getByRole('button', { name: '已处理' }).click();
  await expect(page.getByText('已标为处理完毕')).toBeVisible({ timeout: 10_000 });

  // 复核放行后续写：第2章可写完
  const next = await writeChapters(request, id, 3);
  expect(String(next.status)).toBe('COMPLETED');
  expect(Number(next.chaptersWritten)).toBe(2);
});
