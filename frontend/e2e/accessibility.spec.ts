import { test, expect } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';
import { createProject } from './helpers';

/**
 * p21-07 / p23-04: 键盘可激活 + 图标按钮可访问名 + axe 关键违规为零。
 * 注：antd 组件库自身的对比度类违规按严重度过滤后仍应为 0；
 * 若 contrast 出现 serious 级违规，属于全局样式问题需修复而非过滤。
 */

async function scanAxe(page: import('@playwright/test').Page) {
  const results = await new AxeBuilder({ page })
    .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'])
    .analyze();
  return results.violations.filter((v) => v.impact === 'critical' || v.impact === 'serious');
}

test.describe('键盘与可访问名', () => {
  test('项目表格行键盘可激活（Enter 打开项目）', async ({ page, request }) => {
    const id = await createProject(request, 'e2e-a11y-kbd');
    await page.goto('/');
    const row = page.getByRole('button', { name: /打开项目/ }).first();
    await expect(row).toBeVisible();
    await row.focus();
    await page.keyboard.press('Enter');
    await expect(page).toHaveURL(new RegExp(`/project/${id}`));
  });

  test('删除图标按钮具备可访问名', async ({ page, request }) => {
    await createProject(request, 'e2e-a11y-del');
    await page.goto('/');
    const del = page.getByLabel(/删除项目/).first();
    await expect(del).toBeVisible();
  });

  test('章节列表行键盘可激活并带可访问名', async ({ page, request }) => {
    const { setPreferences, conceive, writeChapters } = await import('./helpers');
    const id = await createProject(request, 'e2e-a11y-chapters');
    await setPreferences(request, id, { targetChapters: 3 });
    await conceive(request, id);
    await writeChapters(request, id, 2);
    await page.goto(`/project/${id}`);
    await page.getByRole('tab', { name: /章节管理/ }).click();
    const item = page.getByRole('button', { name: /第1章/ }).first();
    await expect(item).toBeVisible();
    await item.focus();
    await page.keyboard.press('Enter');
    // 选中态生效：第2章导航按钮（下一章）变为可用
    await expect(page.getByRole('button', { name: /下一章/ })).toBeEnabled();
  });
});

test.describe('axe 扫描（critical/serious 为零）', () => {
  test('项目列表页 axe 无 critical/serious 违规', async ({ page }, testInfo) => {
    test.skip(testInfo.project.name === 'chromium-mobile', '移动端键盘规则不适用 axe 主扫描');
    await createProject(page.request ?? ({} as never), 'e2e-axe-list').catch(() => {});
    await page.goto('/');
    await expect(page.getByText(/长篇小说写作系统/)).toBeVisible();
    const violations = await scanAxe(page);
    expect(violations, JSON.stringify(violations.map((v) => ({ id: v.id, impact: v.impact })))).toHaveLength(0);
  });

  test('工作台运行控制台 axe 无 critical/serious 违规', async ({ page, request }, testInfo) => {
    test.skip(testInfo.project.name === 'chromium-mobile', '移动端键盘规则不适用 axe 主扫描');
    const id = await createProject(request, 'e2e-axe-ws');
    await page.goto(`/project/${id}`);
    await expect(page.getByText(/运行控制台/).first()).toBeVisible();
    const violations = await scanAxe(page);
    expect(violations, JSON.stringify(violations.map((v) => ({ id: v.id, impact: v.impact })))).toHaveLength(0);
  });
});
