import { test, expect } from '@playwright/test';
import {
  createProject, setPreferences, conceive, writeChapters,
  addMustResolveForeshadowing, openWorkspaceTab, BASE,
} from './helpers';

/**
 * p21-03 / p23-04: 终态门禁 UI——FINAL 禁用写作控制；NEEDS_REVISION 渲染中文状态、
 * 原因与重审入口，无裸枚举。种子走 API（场景网关后端，零成本确定性）。
 */

test('FINAL 禁用全部写作控制并渲染完结提示', async ({ request, page }) => {
  const id = await createProject(request, 'e2e-final');
  await setPreferences(request, id, { targetChapters: 3 });
  await conceive(request, id);
  const task = await writeChapters(request, id, 3);
  expect(String(task.status)).toBe('COMPLETED');

  await openWorkspaceTab(page, id, /运行控制台/);
  await expect(page.getByText('全书已完结（FINAL）')).toBeVisible();
  await expect(page.getByRole('button', { name: '推进状态' })).toBeDisabled();
  await expect(page.getByRole('button', { name: /^写一章$/ })).toBeDisabled();
  await expect(page.getByRole('button', { name: /连续写作（最多 50 章）/ })).toBeDisabled();
});

test('NEEDS_REVISION 渲染中文状态、原因与重新评审入口，无裸枚举', async ({ request, page }) => {
  const id = await createProject(request, 'e2e-needs-revision');
  // 4 条 mustResolve 未回收 → 成书门禁硬性 FAIL → NEEDS_REVISION
  for (let i = 0; i < 4; i++) {
    await addMustResolveForeshadowing(request, id, `必须回收的伏笔${i}：账册缺页之谜`);
  }
  await setPreferences(request, id, { targetChapters: 3 });
  await conceive(request, id);
  await writeChapters(request, id, 3);

  const status = await (await request.get(`${BASE}/api/projects/${id}/status`)).json();
  expect(status.state).toBe('NEEDS_REVISION');

  await openWorkspaceTab(page, id, /运行控制台/);
  await expect(page.getByText('成书门禁未通过（NEEDS_REVISION）')).toBeVisible();
  await expect(page.getByText(/mustResolve 伏笔未回收|硬性门禁/).first()).toBeVisible();
  await expect(page.getByRole('button', { name: '重新评审' })).toBeVisible();
  await expect(page.getByRole('button', { name: /^写一章$/ })).toBeDisabled();

  // 无裸枚举暴露给用户
  const bodyText = await page.locator('body').innerText();
  expect(bodyText).not.toContain('NEEDS_REVISION');
  expect(bodyText).not.toContain('PAUSED_AT_CHECKPOINT');
});
