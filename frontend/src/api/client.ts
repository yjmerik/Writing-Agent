/** API 客户端：封装全部 16 个后端端点 */
import type {
  Project,
  ProjectStatus,
  Character,
  KnowledgeEntry,
  Outline,
  Chapter,
  Summary,
  CreateProjectRequest,
  ProjectMode,
} from '../types';

const BASE = '/api';

/** 封装 fetch，统一处理错误和超时 */
async function request<T>(url: string, options?: RequestInit, timeoutMs = 300000): Promise<T> {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    const resp = await fetch(`${BASE}${url}`, {
      ...options,
      signal: controller.signal,
      headers: {
        'Content-Type': 'application/json',
        ...options?.headers,
      },
    });
    if (!resp.ok) {
      let msg = `HTTP ${resp.status}`;
      try {
        const body = await resp.json();
        msg = body.error || msg;
      } catch {
        /* 非 JSON 错误响应 */
      }
      throw new Error(msg);
    }
    // 空响应（204 或空 body）
    const text = await resp.text();
    return (text ? JSON.parse(text) : null) as T;
  } finally {
    clearTimeout(timer);
  }
}

export const api = {
  // --- 项目 ---
  listProjects: () => request<Project[]>('/projects'),

  getProject: (id: string) => request<Project>(`/projects/${id}`),

  createProject: (req: CreateProjectRequest) =>
    request<Project>('/projects', {
      method: 'POST',
      body: JSON.stringify(req),
    }),

  switchMode: (id: string, mode: ProjectMode) =>
    request<void>(`/projects/${id}/mode`, {
      // 注意：后端期望裸 JSON 字符串，如 "INTERACTIVE"
      method: 'PUT',
      body: JSON.stringify(mode),
    }),

  deleteProject: (id: string) =>
    request<void>(`/projects/${id}`, { method: 'DELETE' }),

  // --- 运行控制 ---
  run: (id: string) =>
    request<ProjectStatus>(`/projects/${id}/run`, { method: 'POST' }),

  getStatus: (id: string) => request<ProjectStatus>(`/projects/${id}/status`),

  // --- 内容查询 ---
  getWorldbuilding: (id: string) =>
    request<KnowledgeEntry[]>(`/projects/${id}/worldbuilding`),

  getCharacters: (id: string) =>
    request<Character[]>(`/projects/${id}/characters`),

  getOutline: (id: string) =>
    request<Outline | null>(`/projects/${id}/outline`),

  getChapters: (id: string) =>
    request<Chapter[]>(`/projects/${id}/chapters`),

  getChapter: (id: string, chapterIndex: number) =>
    request<Chapter | null>(`/projects/${id}/chapters/${chapterIndex}`),

  getSummaries: (id: string) =>
    request<Summary[]>(`/projects/${id}/memory/summary`),

  // --- 写作 ---
  writeChapter: (id: string) =>
    request<Chapter>(`/projects/${id}/write-chapter`, { method: 'POST' }),

  /** 连续写作：批量写所有章节（慢操作，可能几十分钟） */
  writeAll: (id: string, maxChapters = 50) =>
    request<{ projectId: string; chaptersWritten: number; state: string; currentChapter: number; totalWords: number }>(
      `/projects/${id}/write-all?maxChapters=${maxChapters}`,
      { method: 'POST' },
      3600000, // 超时 1 小时
    ),

  /** 质量评价（慢操作，调 LLM 分析） */
  evaluate: (id: string) =>
    request<{
      scores: Record<string, number>;
      overallScore: number;
      summary: string;
      issues: { dimension: string; severity: string; description: string }[];
    }>(`/projects/${id}/evaluate`, { method: 'POST' }, 300000),
};
