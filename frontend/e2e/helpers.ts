import { APIRequestContext, Page } from '@playwright/test';

/** p23-04: 浏览器套件公共工具——用场景网关后端（零成本确定性）做 API 侧种子，UI 只做行为断言 */
const PORT = process.env.E2E_PORT || 8095;
export const BASE = `http://localhost:${PORT}`;

export async function createProject(request: APIRequestContext, title: string): Promise<string> {
  const resp = await request.post(`${BASE}/api/projects`, {
    data: {
      title,
      concept: `${title} 的验证用故事：雨夜旧书店里一本会自行续写的账册`,
      tags: '悬疑,古代',
      mode: 'SOLO',
      preset: 'mystery',
    },
  });
  if (!resp.ok()) throw new Error(`createProject failed: ${resp.status()}`);
  return (await resp.json()).id;
}

export async function setPreferences(
  request: APIRequestContext,
  id: string,
  prefs: Record<string, unknown>,
): Promise<void> {
  await request.put(`${BASE}/api/projects/${id}/preferences`, { data: prefs });
}

export async function waitTask(request: APIRequestContext, id: string, taskId: string): Promise<Record<string, unknown>> {
  for (let i = 0; i < 240; i++) {
    const r = await request.get(`${BASE}/api/projects/${id}/writing-tasks/${taskId}`);
    const j = (await r.json()) as Record<string, unknown>;
    if (['COMPLETED', 'FAILED', 'CANCELLED', 'PAUSED'].includes(String(j.status))) return j;
    await new Promise((resolve) => setTimeout(resolve, 400));
  }
  throw new Error(`task ${taskId} timeout`);
}

/** 构思（CONCEIVING 异步任务 + PLANNING 补一步推进） */
export async function conceive(request: APIRequestContext, id: string): Promise<void> {
  let resp = await request.post(`${BASE}/api/projects/${id}/run`);
  let body = (await resp.json()) as Record<string, unknown>;
  if (body.taskId) await waitTask(request, id, String(body.taskId));
  const st = await request.get(`${BASE}/api/projects/${id}/status`);
  if ((await st.json()).state === 'PLANNING') {
    resp = await request.post(`${BASE}/api/projects/${id}/run`);
    body = (await resp.json()) as Record<string, unknown>;
    if (body.taskId) await waitTask(request, id, String(body.taskId));
  }
}

export async function writeChapters(
  request: APIRequestContext,
  id: string,
  n: number,
): Promise<Record<string, unknown>> {
  const r = await request.post(`${BASE}/api/projects/${id}/write-all?maxChapters=${n}`);
  const { taskId } = (await r.json()) as { taskId: string };
  return waitTask(request, id, taskId);
}

export async function addMustResolveForeshadowing(request: APIRequestContext, id: string, content: string): Promise<void> {
  const resp = await request.post(`${BASE}/api/projects/${id}/foreshadowings`, {
    data: { content, mustResolve: true },
  });
  if (!resp.ok()) throw new Error(`addForeshadowing failed: ${resp.status()}`);
}

/** 打开工作台并切到指定 Tab（正则匹配 Tab 名） */
export async function openWorkspaceTab(page: Page, id: string, tabPattern: RegExp): Promise<void> {
  await page.goto(`/project/${id}`);
  await page.getByRole('tab', { name: tabPattern }).click();
  await page.waitForLoadState('networkidle');
}
