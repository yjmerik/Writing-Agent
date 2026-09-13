import { defineConfig } from '@playwright/test';
import path from 'node:path';

// p36-01: 协作系统浏览器回归配置。
// QA_BASE_URL 未设置时由各 spec 显式失败（不允许静默连到默认地址）。
// 所有产物（截图/trace/report）写入 QA_ARTIFACT_DIR，不进 Git。
const artifactDir = process.env.QA_ARTIFACT_DIR ?? path.join('qa', 'collaboration', 'artifacts-default');

export default defineConfig({
  testDir: '.',
  testMatch: /.*\.spec\.ts/,
  workers: 1,
  outputDir: path.join(artifactDir, 'test-results'),
  reporter: [['list'], ['json', { outputFile: path.join(artifactDir, 'report.json') }]],
  use: {
    baseURL: process.env.QA_BASE_URL,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
});
