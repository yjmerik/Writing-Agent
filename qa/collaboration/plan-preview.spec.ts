import { test, expect, type Page } from '@playwright/test';

// p36-10 契约：目标章数改变使旧预览失效——只有 newTarget === previewTargetNumber 且
// 未生效才启用「确认生效」；旧预览请求迟到不能恢复 22 章预览；确认 20 后大纲树已裁剪，
// 再保存总纲不会复活 21+ 章。作者动作走真实 UI 与正式端点。

const rawBase = process.env.QA_BASE_URL;
if (!rawBase || rawBase.trim() === '') {
  throw new Error('QA_BASE_URL 未设置：浏览器回归拒绝猜测目标地址');
}
const parsed = new URL(rawBase);
if (parsed.hostname !== 'localhost' && parsed.hostname !== '127.0.0.1') {
  throw new Error(`QA_BASE_URL 仅允许 localhost/127.0.0.1，当前: ${parsed.hostname}`);
}
const BASE = rawBase.replace(/\/+$/, '');

const CHAPTER_BODY = '这一章的正文内容，长度满足已完成章节的入库门槛要求。'.repeat(30);

async function createProjectViaApi(title: string): Promise<string> {
  const resp = await fetch(`${BASE}/api/projects`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ title, concept: '篇幅预览验证', tags: '', mode: 'INTERACTIVE' }),
  });
  if (!resp.ok) throw new Error(`创建项目失败: ${resp.status}`);
  return ((await resp.json()) as { id: string }).id;
}

function observeRuntimeErrors(page: Page, bucket: string[]): void {
  page.on('pageerror', (error) => bucket.push(`${page.url()} :: ${error.message}`));
  page.on('console', (msg) => {
    if (msg.type() === 'error') bucket.push(`${page.url()} :: ${msg.text()}`);
  });
}

test.describe('plan-preview：目标章数预览绑定与确认', () => {
  let projectId: string;
  const runtimeErrors: string[] = [];

  test.beforeAll(async () => {
    test.setTimeout(300000);
    projectId = await createProjectViaApi('篇幅预览验证');
    // 大纲只能经构思流程产生：协作 start → 初始设定 checkpoint（scenario）→ 批准
    const startResp = await fetch(`${BASE}/api/projects/${projectId}/collaboration/start`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{}',
    });
    if (!startResp.ok) throw new Error(`协作启动失败: ${startResp.status}`);
    const deadline = Date.now() + 180000;
    let checkpointId: string | null = null;
    while (Date.now() < deadline && !checkpointId) {
      const cpsResp = await fetch(`${BASE}/api/projects/${projectId}/checkpoints`);
      if (cpsResp.ok) {
        const cps = (await cpsResp.json()) as Array<{ id: string; type: string; status: string }>;
        checkpointId = cps.find(c => c.status === 'PENDING' && c.type === 'AFTER_INITIAL_SETUP')?.id ?? null;
      }
      if (!checkpointId) await new Promise(r => setTimeout(r, 1000));
    }
    if (!checkpointId) throw new Error('初始设定 checkpoint 未出现');
    const approveResp = await fetch(`${BASE}/api/projects/${projectId}/checkpoints/${checkpointId}/approve`, {
      method: 'POST',
    });
    if (!approveResp.ok) throw new Error(`初始设定批准失败: ${approveResp.status}`);

    // 等待大纲落库（构思阶段一次性生成；scenario 目标章数由偏好决定）
    const outlineDeadline = Date.now() + 180000;
    let outlineReady = false;
    while (Date.now() < outlineDeadline && !outlineReady) {
      const oResp = await fetch(`${BASE}/api/projects/${projectId}/outline`);
      outlineReady = oResp.ok && (await oResp.text()).length > 10;
      if (!outlineReady) await new Promise(r => setTimeout(r, 1000));
    }
    if (!outlineReady) throw new Error('构思后大纲未出现');
    // 用 PUT 把未写部分裁到 24 章（generateOutlines 用偏好目标章数，这里统一到 24）
    const currentResp = await fetch(`${BASE}/api/projects/${projectId}/outline`);
    const currentOutline = (await currentResp.json()) as {
      projectId: string;
      premise: string;
      volumes: Array<{ id?: string; volumeIndex: number; title: string; summary: string; synopses: Array<{ id?: string; chapterIndex: number; title: string; synopsis: string }> }>;
    };
    const trimmedSynopses = Array.from({ length: 24 }, (_, i) => ({
      projectId,
      chapterIndex: i + 1,
      title: `第${i + 1}章`,
      synopsis: `第${i + 1}章梗概：推进主线。`,
    }));
    // 保留服务端卷 id（卷归属校验需要）
    const firstVolume = currentOutline.volumes[0];
    const outlineResp = await fetch(`${BASE}/api/projects/${projectId}/outline`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        projectId,
        premise: currentOutline.premise || '总纲：篇幅预览验证。',
        volumes: [{
          id: firstVolume?.id,
          projectId,
          volumeIndex: 1,
          title: '第一卷',
          summary: '卷摘要',
          synopses: trimmedSynopses,
        }],
      }),
    });
    if (!outlineResp.ok) throw new Error(`裁大纲失败: ${outlineResp.status} ${await outlineResp.text()}`);
    for (let i = 1; i <= 3; i++) {
      const resp = await fetch(`${BASE}/api/projects/${projectId}/chapters`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ title: `第${i}章`, content: CHAPTER_BODY }),
      });
      if (!resp.ok) throw new Error(`写章节失败: ${resp.status}`);
    }
  });

  test('22预览后改20必须禁用确认，改回22恢复；确认20后大纲裁剪且保存总纲不复活', async ({ page }) => {
    observeRuntimeErrors(page, runtimeErrors);
    await page.goto(`${BASE}/#/project/${projectId}`);
    await page.getByRole('tab', { name: /大纲/ }).click();

    const targetInput = page.getByRole('spinbutton', { name: '目标章数' });
    // AntD loading 态会给 accessible name 加前缀——非精确匹配
    const confirmButton = page.getByRole('button', { name: '确认生效' });
    const previewButton = page.getByRole('button', { name: '影响预览' });

    // 22 章预览：确认按钮就绪
    await targetInput.fill('22');
    await previewButton.click();
    await expect(page.getByText(/影响预览：将裁剪 2 章/)).toBeVisible({ timeout: 30000 });
    await expect(confirmButton).toBeEnabled();

    // 改成 20：旧预览立即失效——确认必须禁用
    await targetInput.fill('20');
    await expect(confirmButton).toBeDisabled();

    // 改回 22：与新输入一致的旧预览仍然可用（预览绑定的是 22）
    await targetInput.fill('22');
    await expect(confirmButton).toBeEnabled();

    // 再改 20 → 预览 20 → 确认生效
    await targetInput.fill('20');
    await expect(confirmButton).toBeDisabled();
    await previewButton.click();
    await expect(page.getByText(/影响预览：将裁剪 4 章/)).toBeVisible({ timeout: 30000 });
    await expect(confirmButton).toBeEnabled();
    await confirmButton.click();
    await expect(page.getByText(/已生效：目标 20 章/)).toBeVisible({ timeout: 30000 });

    // 大纲树已裁剪：21+ 章不再出现
    await expect(page.locator('[data-outline-chapter-index="21"]')).toHaveCount(0);
    await expect(page.locator('[data-outline-chapter-index="20"]')).toBeVisible();
    // 目标输入被禁用（确认期间/生效后重读），提示明确
    await expect(page.getByText(/已生效：目标 20 章/)).toBeVisible();

    // 保存总纲不会复活 21+ 章（persistedOutline 已是裁剪后）
    await page.getByRole('button', { name: '保存总纲' }).click();
    await expect(page.getByText(/总纲已保存/)).toBeVisible({ timeout: 30000 });
    await expect(page.locator('[data-outline-chapter-index="21"]')).toHaveCount(0);

    // 服务端复核：大纲确实只有 20 章
    const outlineResp = await fetch(`${BASE}/api/projects/${projectId}/outline`);
    const outline = (await outlineResp.json()) as {
      volumes: Array<{ synopses: Array<{ chapterIndex: number }> }>;
    };
    const maxChapter = Math.max(...outline.volumes.flatMap(v => v.synopses.map(s => s.chapterIndex)));
    expect(maxChapter).toBeLessThanOrEqual(20);
  });

  test('乱序返回的旧预览不能覆盖新输入的预览', async ({ page }) => {
    observeRuntimeErrors(page, runtimeErrors);
    // 独立项目（主用例已把共享项目裁到 20 章）：走真实构思流程建大纲
    const projectId2 = await createProjectViaApi('篇幅预览验证-乱序');
    const startResp = await fetch(`${BASE}/api/projects/${projectId2}/collaboration/start`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{}',
    });
    if (!startResp.ok) throw new Error(`协作启动失败: ${startResp.status}`);
    const cpDeadline = Date.now() + 180000;
    let checkpointId: string | null = null;
    while (Date.now() < cpDeadline && !checkpointId) {
      const cpsResp = await fetch(`${BASE}/api/projects/${projectId2}/checkpoints`);
      if (cpsResp.ok) {
        const cps = (await cpsResp.json()) as Array<{ id: string; type: string; status: string }>;
        checkpointId = cps.find(c => c.status === 'PENDING' && c.type === 'AFTER_INITIAL_SETUP')?.id ?? null;
      }
      if (!checkpointId) await new Promise(r => setTimeout(r, 1000));
    }
    if (!checkpointId) throw new Error('乱序项目初始设定 checkpoint 未出现');
    const approveResp = await fetch(`${BASE}/api/projects/${projectId2}/checkpoints/${checkpointId}/approve`, { method: 'POST' });
    if (!approveResp.ok) throw new Error(`乱序项目批准失败: ${approveResp.status}`);
    const outlineDeadline = Date.now() + 180000;
    let outlineReady = false;
    while (Date.now() < outlineDeadline && !outlineReady) {
      const oResp = await fetch(`${BASE}/api/projects/${projectId2}/outline`);
      outlineReady = oResp.ok && (await oResp.text()).length > 10;
      if (!outlineReady) await new Promise(r => setTimeout(r, 1000));
    }
    if (!outlineReady) throw new Error('乱序项目大纲未出现');

    await page.goto(`${BASE}/#/project/${projectId2}`);
    await page.getByRole('tab', { name: /大纲/ }).click();

    const targetInput = page.getByRole('spinbutton', { name: '目标章数' });
    const previewButton = page.getByRole('button', { name: '影响预览' });

    // 快速连续预览 22 → 21：以最后一次输入为准（序号丢弃乱序返回）
    await targetInput.fill('22');
    await previewButton.click();
    await targetInput.fill('21');
    await previewButton.click();
    await expect(page.getByText(/影响预览：将裁剪 \d+ 章/)).toBeVisible({ timeout: 30000 });
    // 展示的预览必须与当前输入 21 一致（裁剪 3 章），不得是迟到的 22 预览
    await expect(page.getByText(/将裁剪 3 章/)).toBeVisible({ timeout: 30000 });
  });

  test.afterAll(async () => {
    if (runtimeErrors.length > 0) {
      throw new Error('运行时错误: ' + runtimeErrors.join('; '));
    }
  });
});
