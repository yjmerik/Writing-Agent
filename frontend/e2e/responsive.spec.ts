import { test, expect } from '@playwright/test';
import { createProject, setPreferences, conceive, writeChapters } from './helpers';

/**
 * p21-06 / p23-04: 响应式——375/390 宽度下项目列表、工作台、章节管理无页面级横向溢出；
 * 侧栏折叠；章节双栏纵向堆叠。桌面侧栏可见。
 */

async function assertNoHorizontalOverflow(page: import('@playwright/test').Page) {
  const overflow = await page.evaluate(() => ({
    scroll: document.documentElement.scrollWidth,
    inner: window.innerWidth,
  }));
  expect(overflow.scroll).toBeLessThanOrEqual(overflow.inner + 1);
}

test.describe('移动端 375-390px', () => {
  test.skip(({ browserName, viewport }) => browserName !== 'chromium' || (viewport?.width ?? 0) >= 992, 'mobile only');

  test('项目列表无页面级横向溢出', async ({ page, request }) => {
    await createProject(request, 'e2e-mobile-list');
    await page.goto('/');
    await expect(page.getByText(/长篇小说写作系统/)).toBeVisible();
    await assertNoHorizontalOverflow(page);
  });

  test('工作台侧栏折叠且无横向溢出', async ({ page, request }) => {
    const id = await createProject(request, 'e2e-mobile-ws');
    await page.goto(`/project/${id}`);
    await expect(page.getByText(/运行控制台/).first()).toBeVisible();
    // antd Sider breakpoint=lg：小屏折叠（collapsed 或宽度 0）
    const siderWidth = await page.locator('.ant-layout-sider').first().evaluate(
      (el) => (el as HTMLElement).getBoundingClientRect().width,
    );
    expect(siderWidth).toBeLessThan(10);
    await assertNoHorizontalOverflow(page);
  });

  test('章节管理双栏纵向堆叠且无横向溢出', async ({ page, request }) => {
    const id = await createProject(request, 'e2e-mobile-review');
    await setPreferences(request, id, { targetChapters: 3 });
    await conceive(request, id);
    await writeChapters(request, id, 1);
    await page.goto(`/project/${id}`);
    await page.getByRole('tab', { name: /章节管理/ }).click();
    await expect(page.getByText(/📖 章节 \(/)).toBeVisible();

    const listBox = await page.getByText(/📖 章节 \(/).boundingBox();
    const contentHeading = await page.getByRole('heading', { name: /第1章/ }).first().boundingBox();
    expect(listBox && contentHeading ? listBox.y < contentHeading.y : true,
      '小屏章节列表应在正文上方（纵向堆叠）').toBeTruthy();
    await assertNoHorizontalOverflow(page);
  });
});

test.describe('桌面端', () => {
  test.skip(({ viewport }) => (viewport?.width ?? 0) < 992, 'desktop only');

  test('桌面侧栏可见且无横向溢出', async ({ page, request }) => {
    const id = await createProject(request, 'e2e-desktop-ws');
    await page.goto(`/project/${id}`);
    await expect(page.getByText(/运行控制台/).first()).toBeVisible();
    const siderWidth = await page.locator('.ant-layout-sider').first().evaluate(
      (el) => (el as HTMLElement).getBoundingClientRect().width,
    );
    expect(siderWidth).toBeGreaterThanOrEqual(300);
    await assertNoHorizontalOverflow(page);
  });
});
