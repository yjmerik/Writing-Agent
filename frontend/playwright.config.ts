import { defineConfig, devices } from '@playwright/test';

/**
 * p23-04: 仓库自有浏览器 E2E 套件。
 * webServer 启动打包的 Spring 应用（确定性场景网关，零 LLM 成本），前端静态资源由应用服务。
 * HARSH=1 时以 harsh 档启动（第1章注入 HIGH 矛盾）——manual-review.spec 专用；
 * 其余 spec 在 HARSH 下跳过，两档互斥：
 *   npm run e2e                → 标准档全部 spec（manual-review 除外）
 *   npm run e2e:manual-review  → harsh 档 manual-review.spec
 */
const HARSH = !!process.env.HARSH;
const PORT = Number(process.env.E2E_PORT || 8095);

export default defineConfig({
  testDir: './e2e',
  timeout: 120_000,
  expect: { timeout: 15_000 },
  fullyParallel: false,
  workers: 1,
  reporter: [['list'], ['html', { open: 'never', outputFolder: '../data/release/playwright-report' }]],
  outputDir: '../data/release/playwright-artifacts',
  use: {
    baseURL: `http://localhost:${PORT}`,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  projects: [
    { name: 'chromium-desktop', use: { ...devices['Desktop Chrome'] } },
    { name: 'chromium-mobile', use: { ...devices['iPhone 13'] } },
  ],
  webServer: {
    command:
      `mvn -q spring-boot:run ` +
      `-Dspring-boot.run.arguments="--server.port=${PORT} --llm.scenario.enabled=true ` +
      `--llm.scenario.harsh=${HARSH} --llm.replay.mode=off --persistence.embedding=stub ` +
      `--persistence.sqlite.db-path=data/e2e/browser/novel.db --persistence.vector-store.index-path=data/e2e/browser/vectors.idx"`,
    url: `http://localhost:${PORT}/api/projects`,
    reuseExistingServer: false,
    timeout: 120_000,
  },
});
