/** API 客户端：封装全部后端端点 */
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
  Checkpoint,
  ChapterQualityReport,
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

  updateWorldbuilding: (id: string, entryId: string, body: Partial<KnowledgeEntry>) =>
    request<KnowledgeEntry>(`/projects/${id}/worldbuilding/${entryId}`, {
      method: 'PUT',
      body: JSON.stringify(body),
    }),

  getCharacters: (id: string) =>
    request<Character[]>(`/projects/${id}/characters`),

  updateCharacter: (id: string, charId: string, body: Partial<Character>) =>
    request<Character>(`/projects/${id}/characters/${charId}`, {
      method: 'PUT',
      body: JSON.stringify(body),
    }),

  getOutline: (id: string) =>
    request<Outline | null>(`/projects/${id}/outline`),

  updateOutline: (id: string, outline: Partial<Outline>) =>
    request<Outline>(`/projects/${id}/outline`, {
      method: 'PUT',
      body: JSON.stringify(outline),
    }),

  getFacts: (id: string) =>
    request<Array<{ id: string; subject: string; assertion: string; factType: string }>>(`/projects/${id}/facts`),

  pause: (id: string) =>
    request<ProjectStatus>(`/projects/${id}/pause`, { method: 'POST' }),

  resume: (id: string) =>
    request<ProjectStatus>(`/projects/${id}/resume`, { method: 'POST' }),

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

  // --- p7: 交互式审核 + 章节重写 + 预设 ---

  /** 列出待审核 checkpoint */
  listCheckpoints: (id: string) =>
    request<Checkpoint[]>(`/projects/${id}/checkpoints`),

  /** 批准 checkpoint */
  approveCheckpoint: (id: string, checkpointId: string) =>
    request<{ status: string; checkpointId: string }>(`/projects/${id}/checkpoints/${checkpointId}/approve`, { method: 'POST' }),

  /** 拒绝 checkpoint（带修改意见重写当前章） */
  rejectCheckpoint: (id: string, checkpointId: string, instructions: string) =>
    request<{ status: string; checkpointId: string; instructions: string }>(
      `/projects/${id}/checkpoints/${checkpointId}/reject`,
      { method: 'POST', body: JSON.stringify({ instructions }) },
      300000,
    ),

  /** 修改 checkpoint（带意见重写后批准） */
  modifyCheckpoint: (id: string, checkpointId: string, instructions: string) =>
    request<{ status: string; checkpointId: string; instructions: string }>(
      `/projects/${id}/checkpoints/${checkpointId}/modify`,
      { method: 'POST', body: JSON.stringify({ instructions }) },
      300000,
    ),

  /** 重写指定章节 */
  rewriteChapter: (id: string, chapterIndex: number, instructions?: string) =>
    request<Chapter>(
      `/projects/${id}/rewrite-chapter/${chapterIndex}`,
      { method: 'POST', body: JSON.stringify({ instructions: instructions || '' }) },
      300000,
    ),

  /** 获取偏好配置 */
  getPreferences: (id: string) =>
    request<Record<string, unknown>>(`/projects/${id}/preferences`),

  /** 对话式修改偏好 */
  chatPreferences: (id: string, message: string) =>
    request<{ reply: string; preferences: Record<string, unknown> }>(
      `/projects/${id}/preferences/chat`,
      { method: 'POST', body: JSON.stringify({ message }) },
    ),

  /** p11-I3: 获取章节质量报告 */
  getChapterQuality: (id: string, chapterIndex: number) =>
    request<ChapterQualityReport>(
      `/projects/${id}/chapters/${chapterIndex}/quality`,
    ),

  /** p11-G2: 段落级编辑 */
  editPassage: (id: string, chapterIndex: number, body: {
    paragraphIndex: number;
    selectedText: string;
    instruction: string;
    mode: string;
  }) =>
    request<Chapter>(
      `/projects/${id}/chapters/${chapterIndex}/passage`,
      { method: 'POST', body: JSON.stringify(body) },
      120000,
    ),
};
