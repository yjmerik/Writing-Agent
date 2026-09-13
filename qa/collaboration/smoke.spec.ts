import { test, expect, type BrowserContext, type Page } from '@playwright/test';
import fs from 'node:fs';
import path from 'node:path';

// p36-01 契约：读取 QA_BASE_URL，仅允许 localhost/127.0.0.1，未设置即失败；
// 正常本地服务通过；错误端口、空白页、页面运行异常均让命令非零退出
// （后三者使本文件的断言失败 → playwright 退出码非零）。
const rawBase = process.env.QA_BASE_URL;
if (!rawBase || rawBase.trim() === '') {
  throw new Error('QA_BASE_URL 未设置：浏览器回归拒绝猜测目标地址');
}
const parsed = new URL(rawBase);
if (parsed.hostname !== 'localhost' && parsed.hostname !== '127.0.0.1') {
  throw new Error(`QA_BASE_URL 仅允许 localhost/127.0.0.1，当前: ${parsed.hostname}`);
}
const BASE = rawBase.replace(/\/+$/, '');

const ARTIFACT_DIR = process.env.QA_ARTIFACT_DIR;

interface ObservedErrors {
  pageErrors: string[];
  consoleErrors: string[];
}

/** 常见框架错误遮罩选择器：出现即视为主界面不可用。 */
const FRAMEWORK_OVERLAYS = [
  '#vite-error-overlay',
  'text=Application error',
  'text=Internal Server Error',
];

function attachObservers(page: Page, observed: ObservedErrors): void {
  page.on('pageerror', (error) => observed.pageErrors.push(`${page.url()} :: ${error.message}`));
  page.on('console', (msg) => {
    if (msg.type() === 'error') observed.consoleErrors.push(`${page.url()} :: ${msg.text()}`);
  });
}

function persistErrorLog(observed: ObservedErrors, label: string): void {
  if (!ARTIFACT_DIR) return;
  fs.mkdirSync(ARTIFACT_DIR, { recursive: true });
  const logLines = [
    `# ${new Date().toISOString()} smoke ${label}`,
    `QA_BASE_URL=${BASE}`,
    ...observed.pageErrors.map((e) => `PAGEERROR ${e}`),
    ...observed.consoleErrors.map((e) => `CONSOLE_ERROR ${e}`),
  ];
  fs.writeFileSync(path.join(ARTIFACT_DIR, `smoke-errors-${label}.log`), logLines.join('\n') + '\n');
}

test.describe('smoke：项目列表真实浏览器回归', () => {
  let desktop: BrowserContext;
  let mobile: BrowserContext;
  const desktopErrors: ObservedErrors = { pageErrors: [], consoleErrors: [] };
  const mobileErrors: ObservedErrors = { pageErrors: [], consoleErrors: [] };

  test.beforeAll(async ({ browser }) => {
    desktop = await browser.newContext({ viewport: { width: 1280, height: 900 } });
    mobile = await browser.newContext({ viewport: { width: 390, height: 844 } });
  });

  test.afterAll(async () => {
    persistErrorLog(desktopErrors, 'desktop-1280x900');
    persistErrorLog(mobileErrors, 'mobile-390x844');
    await desktop?.close();
    await mobile?.close();
  });

  test('桌面 1280x900：标题、非空主界面、无错误遮罩与运行时错误', async () => {
    const page = await desktop.newPage();
    attachObservers(page, desktopErrors);
    const response = await page.goto(BASE + '/', { waitUntil: 'domcontentloaded' });
    expect(response?.status(), '服务必须可达且返回 2xx').toBeTruthy();
    expect(response!.status(), 'HTTP 状态必须 < 400').toBeLessThan(400);

    await expect(page).toHaveTitle('长篇小说写作系统');
    // 非空主界面：React 已挂载（#root 有内容），不是空白页
    await expect(page.locator('#root > *').first()).toBeVisible();
    // 无框架错误遮罩
    for (const overlay of FRAMEWORK_OVERLAYS) {
      await expect(page.locator(overlay).first()).toHaveCount(0);
    }
  });

  test('窄屏 390x844：同一主界面可达且无运行时错误', async () => {
    const page = await mobile.newPage();
    attachObservers(page, mobileErrors);
    await page.goto(BASE + '/', { waitUntil: 'domcontentloaded' });
    await expect(page).toHaveTitle('长篇小说写作系统');
    await expect(page.locator('#root > *').first()).toBeVisible();
  });

  test('主页面 pageerror 与未解释 console.error 必须失败', async () => {
    // 观察在前面两个用例中已完成；此处集中断言，保证任何运行时异常使命令非零退出
    expect(desktopErrors.pageErrors, '桌面视口 pageerror: ' + desktopErrors.pageErrors.join('; ')).toEqual([]);
    expect(mobileErrors.pageErrors, '窄屏视口 pageerror: ' + mobileErrors.pageErrors.join('; ')).toEqual([]);
    expect(desktopErrors.consoleErrors, '桌面视口 console.error: ' + desktopErrors.consoleErrors.join('; ')).toEqual([]);
    expect(mobileErrors.consoleErrors, '窄屏视口 console.error: ' + mobileErrors.consoleErrors.join('; ')).toEqual([]);
  });
});
