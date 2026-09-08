/** API 客户端：封装全部后端端点 */
import type {
  Project,
  CollaborationStatus,
  CollaborationTask,
  ReviewPolicy,
  ProjectListItem,
  ProjectStatus,
  Character,
  KnowledgeEntry,
  Outline,
  Chapter,
  ChapterVersion,
  Summary,
  CreateProjectRequest,
  ProjectMode,
  Checkpoint,
  ChapterQualityReport,
  Foreshadowing,
  ChapterPage,
  SseState,
} from '../types';

const BASE = '/api';

export class ApiError extends Error {
  /** p32-12: 服务端结构化错误码（如 WORK_OWNERSHIP_CONFLICT） */
  constructor(public readonly status: number, message: string, public readonly code?: string) {
    super(message);
    this.name = 'ApiError';
  }
}

export interface CheckpointRevision {
  targetType: string;
  beforeSummary: string;
  afterSummary: string;
  beforeVersion: number;
  afterVersion: number;
}

export interface CheckpointOperationResponse {
  status: string;
  checkpointId: string;
  instructions: string;
  revision?: CheckpointRevision;
}

export type AgentRevisionTargetType = 'WORLD' | 'CHARACTER' | 'VOLUME' | 'CHAPTER' | 'PASSAGE';

export interface AgentRevisionTarget {
  projectId: string;
  targetType: AgentRevisionTargetType;
  targetId: string;
  chapterIndex?: number;
  passageIndex?: number;
  label: string;
}

export interface AgentRevisionPreview {
  previewId: string;
  targetType: AgentRevisionTargetType;
  targetId: string;
  sourceVersion: string;
  before: Record<string, unknown>;
  after: Record<string, unknown>;
  summary: string;
}

export interface AgentRevisionApplyResult {
  previewId: string;
  targetType: AgentRevisionTargetType;
  targetId: string;
  sourceVersion: string;
  applied: boolean;
  result: unknown;
  /** p32-01: 修改已保存，但派生数据（摘要/索引/收割）刷新未完成 */
  derivedRefreshPending?: boolean;
}

export interface QualityEvaluationResult {
  scores: Record<string, number>;
  overallScore: number;
  summary: string;
  issues: { dimension: string; severity: string; description: string }[];
}

/** 封装 fetch，统一处理错误和超时 */
async function request<T>(url: string, options?: RequestInit, timeoutMs = 300000): Promise<T> {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    const resp = await fetch(`${BASE}${url}`, {
      ...options,
      // p32-14: 显式禁用 HTTP 缓存——普通刷新必须读到最新数据
      cache: 'no-store',
      signal: controller.signal,
      headers: {
        'Content-Type': 'application/json',
        ...options?.headers,
      },
    });
    if (!resp.ok) {
      let msg = `HTTP ${resp.status}`;
      let code: string | undefined;
      try {
        const body = await resp.json();
        msg = body.error || msg;
        code = body.code;
      } catch {
        /* 非 JSON 错误响应 */
      }
      throw new ApiError(resp.status, msg, code);
    }
    // 空响应（204 或空 body）
    const text = await resp.text();
    return (text ? JSON.parse(text) : null) as T;
  } finally {
    clearTimeout(timer);
  }
}

export const api = {
  collaboration: (id: string) => request<CollaborationStatus>(`/projects/${id}/collaboration`),
  startCollaboration: (id: string, instruction?: string) => request<CollaborationTask>(`/projects/${id}/collaboration/start`, {
    method: 'POST', body: JSON.stringify({ instruction }),
  }),
  stopCollaboration: (id: string) => request<CollaborationStatus>(`/projects/${id}/collaboration/stop`, { method: 'POST' }),
  setReviewPolicy: (id: string, policy: ReviewPolicy) => request<CollaborationStatus>(`/projects/${id}/collaboration/review-policy`, {
    method: 'PUT', body: JSON.stringify({ policy }),
  }),
  // --- 项目 ---
  listProjects: () => request<ProjectListItem[]>('/projects'),

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
  run: async (id: string) => {
    const started = await request<{ taskId?: string; status?: string; state?: string; currentChapter?: number }>(
      `/projects/${id}/run`,
      { method: 'POST' },
    );
    if (started.taskId) {
      const deadline = Date.now() + 3600000;
      while (Date.now() < deadline) {
        const task = await request<{ status: string; errorMessage?: string; error?: string }>(`/projects/${id}/writing-tasks/${started.taskId}`);
        if (task.status === 'FAILED') throw new Error(task.errorMessage || '构思任务失败');
        if (task.status && task.status !== 'RUNNING') break;
        await new Promise((r) => setTimeout(r, 1000));
      }
    }
    return request<ProjectStatus>(`/projects/${id}/status`);
  },

  getStatus: (id: string) => request<ProjectStatus>(`/projects/${id}/status`),

  // --- 内容查询 ---
  getWorldbuilding: (id: string) =>
    request<KnowledgeEntry[]>(`/projects/${id}/worldbuilding`),

  createWorldbuilding: (id: string, body: Partial<KnowledgeEntry>) =>
    request<KnowledgeEntry>(`/projects/${id}/worldbuilding`, {
      method: 'POST',
      body: JSON.stringify(body),
    }),

  updateWorldbuilding: (id: string, entryId: string, body: Partial<KnowledgeEntry>) =>
    request<KnowledgeEntry>(`/projects/${id}/worldbuilding/${entryId}`, {
      method: 'PUT',
      body: JSON.stringify(body),
    }),

  getCharacters: (id: string) =>
    request<Character[]>(`/projects/${id}/characters`),

  createCharacter: (id: string, body: Partial<Character>) =>
    request<Character>(`/projects/${id}/characters`, {
      method: 'POST',
      body: JSON.stringify(body),
    }),

  expandCharacters: (id: string, instruction: string) =>
    request<Character>(`/projects/${id}/expand/characters`, {
      method: 'POST',
      body: JSON.stringify({ instruction }),
    }),

  expandWorld: (id: string, instruction: string) =>
    request<KnowledgeEntry[]>(`/projects/${id}/expand/world`, {
      method: 'POST',
      body: JSON.stringify({ instruction }),
    }),

  updateCharacter: (id: string, charId: string, body: Partial<Character>) =>
    request<Character>(`/projects/${id}/characters/${charId}`, {
      method: 'PUT',
      body: JSON.stringify(body),
    }),

  getOutline: (id: string) =>
    request<Outline | null>(`/projects/${id}/outline`),

  updateOutline: (id: string, outline: Outline) =>
    request<Outline>(`/projects/${id}/outline`, {
      method: 'PUT',
      body: JSON.stringify(outline),
    }),

  replanOutline: (id: string) =>
    request<Outline>(`/projects/${id}/replan`, { method: 'POST' }),

  previewAgentRevision: (target: AgentRevisionTarget, instructions: string) =>
    request<AgentRevisionPreview>(`/projects/${target.projectId}/revisions/preview`, {
      method: 'POST',
      body: JSON.stringify({
        targetType: target.targetType,
        targetId: target.targetId,
        chapterIndex: target.chapterIndex,
        passageIndex: target.passageIndex,
        instructions,
      }),
    }, 300000),

  applyAgentRevision: (projectId: string, previewId: string) =>
    request<AgentRevisionApplyResult>(`/projects/${projectId}/revisions/${previewId}/apply`, {
      method: 'POST',
    }, 300000),

  getFacts: (id: string) =>
    request<Array<{ id: string; subject: string; assertion: string; factType: string }>>(`/projects/${id}/facts`),

  addFact: (id: string, body: { factType: string; subject: string; assertion: string }) =>
    request<{ id: string; subject: string; assertion: string; factType: string }>(`/projects/${id}/facts`, {
      method: 'POST',
      body: JSON.stringify(body),
    }),

  updateFact: (id: string, factId: string, assertion: string) =>
    request<{ id: string; assertion: string }>(`/projects/${id}/facts/${factId}`, {
      method: 'PUT',
      body: JSON.stringify({ assertion }),
    }),

  retireFact: (id: string, factId: string) =>
    request<{ id: string }>(`/projects/${id}/facts/${factId}/retire`, { method: 'POST' }),

  getForeshadowings: (id: string) =>
    request<Foreshadowing[]>(`/projects/${id}/foreshadowings`),

  addForeshadowing: (id: string, body: { content: string; mustResolve?: boolean }) =>
    request<Foreshadowing>(`/projects/${id}/foreshadowings`, {
      method: 'POST',
      body: JSON.stringify(body),
    }),

  resolveForeshadowing: (id: string, fid: string) =>
    request<Foreshadowing>(`/projects/${id}/foreshadowings/${fid}/resolve`, { method: 'POST' }),

  resolveManualReview: (id: string, chapterIndex: number) =>
    request<Chapter>(`/projects/${id}/chapters/${chapterIndex}/resolve-review`, { method: 'POST' }),

  pause: (id: string) =>
    request<ProjectStatus>(`/projects/${id}/pause`, { method: 'POST' }),

  resume: (id: string) =>
    request<ProjectStatus>(`/projects/${id}/resume`, { method: 'POST' }),

  /**
   * p21-04: 章节元数据分页——不含正文；正文经 getChapter 单章懒加载。
   */
  getChapterPage: (id: string, page = 0, size = 1000) =>
    request<ChapterPage>(`/projects/${id}/chapters?page=${page}&size=${size}`),

  getChapter: (id: string, chapterIndex: number) =>
    request<Chapter | null>(`/projects/${id}/chapters/${chapterIndex}`),

  createChapter: (id: string, body: { title: string; content: string }) =>
    request<Chapter>(`/projects/${id}/chapters`, {
      method: 'POST',
      body: JSON.stringify(body),
    }),

  updateChapter: (id: string, chapterIndex: number, body: {
    expectedVersion: number;
    title: string;
    content: string;
  }) => request<Chapter>(`/projects/${id}/chapters/${chapterIndex}`, {
    method: 'PUT',
    body: JSON.stringify(body),
  }),

  getChapterVersions: (id: string, chapterIndex: number) =>
    request<ChapterVersion[]>(`/projects/${id}/chapters/${chapterIndex}/versions`),

  restoreChapter: (id: string, chapterIndex: number, version: number, expectedVersion: number) =>
    request<Chapter>(`/projects/${id}/chapters/${chapterIndex}/restore/${version}`, {
      method: 'POST',
      body: JSON.stringify({ expectedVersion }),
    }),

  /** p21-02: NEEDS_REVISION 人工修订后的显式成书门禁重试 */
  finalAudit: (id: string) =>
    request<{ projectId: string; passed: boolean; state: string }>(
      `/projects/${id}/final-audit`, { method: 'POST' }, 120000),

  getSummaries: (id: string) =>
    request<Summary[]>(`/projects/${id}/memory/summary`),

  // --- 写作 ---
  /** p18-03: 写一章（可带作者指令——对下一章的定向要求） */
  writeChapter: (id: string, instruction?: string) =>
    request<Chapter>(`/projects/${id}/write-chapter`, {
      method: 'POST',
      body: JSON.stringify(instruction ? { instruction } : {}),
    }),

  startWriteAll: (id: string, maxChapters = 50, instruction?: string) =>
    request<{ projectId: string; taskId: string; status: string; totalChapters: number; message: string }>(
      `/projects/${id}/write-all?maxChapters=${maxChapters}` + (instruction ? `&instruction=${encodeURIComponent(instruction)}` : ''),
      { method: 'POST' },
    ),

  cancelWritingTask: (id: string, taskId: string) =>
    request<{ taskId: string; status: string }>(`/projects/${id}/writing-tasks/${taskId}/cancel`, { method: 'POST' }),

  getWritingTask: (id: string, taskId: string) =>
    request<{
      taskId: string; projectId: string; status: string;
      currentChapter: number; chaptersWritten: number; totalChapters: number;
      totalWords: number; errorMessage: string;
    }>(`/projects/${id}/writing-tasks/${taskId}`),

  writeAll: async (id: string, maxChapters = 50, onStarted?: (taskId: string) => void, instruction?: string) => {
    const started = await api.startWriteAll(id, maxChapters, instruction);
    const taskId = started.taskId;
    onStarted?.(taskId);
    const deadline = Date.now() + 3600000;
    while (Date.now() < deadline) {
      const task = await api.getWritingTask(id, taskId);
      if (task.status === 'DONE' || task.status === 'COMPLETED' || task.status === 'CANCELLED' || task.status === 'FAILED' || task.status === 'PAUSED') {
        if (task.status === 'FAILED') throw new Error(task.errorMessage || '连续写作失败');
        return task;
      }
      await new Promise((r) => setTimeout(r, 2000));
    }
    throw new Error('连续写作超时');
  },

  /** p18-04: SSE 订阅项目实时事件（CHAPTER_WRITTEN/CHAPTER_FAILED/QUALITY_REVIEW 等）。
   *  返回关闭句柄；浏览器不支持 EventSource 时静默降级（保留轮询）。 */
  /**
   * p21-08: SSE 订阅——带连接状态回调与有界退避重连。
   * 重连 6 次仍失败 → onState('fallback')（调用方降级为轮询/手动刷新）。
   * 返回取消订阅函数（组件卸载必须调用，防泄漏）。
   */
  subscribeEvents: (
    id: string,
    onEvent: (eventName: string, data: any) => void,
    onState?: (state: SseState) => void,
  ): (() => void) | null => {
    if (typeof EventSource === 'undefined') {
      onState?.('fallback');
      return null;
    }
    let es: EventSource | null = null;
    let closed = false;
    let attempts = 0;
    const MAX_ATTEMPTS = 6;
    let names: string[] = [];
    const handler = (e: MessageEvent) => {
      try { onEvent(e.type || 'EVENT', JSON.parse(e.data)); } catch { /* 忽略坏帧 */ }
    };

    const connect = () => {
      if (closed) return;
      try {
        es = new EventSource(`${BASE}/projects/${id}/events`);
      } catch {
        onState?.('fallback');
        return;
      }
      es.onopen = () => { attempts = 0; onState?.('connected'); };
      names = ['CHAPTER_WRITTEN', 'CHAPTER_FAILED', 'QUALITY_REVIEW',
        'QUALITY_CIRCUIT_BREAKER', 'LLM_ERROR', 'FINAL_GATE', 'EVENT'];
      names.forEach((n) => es!.addEventListener(n, handler as EventListener));
      es.onerror = () => {
        if (closed) return;
        es?.close();
        es = null;
        attempts += 1;
        if (attempts > MAX_ATTEMPTS) {
          onState?.('fallback');
          return;
        }
        onState?.('reconnecting');
        // 有界指数退避：1s, 2s, 4s, 8s, 15s, 15s…
        const delay = Math.min(15000, 1000 * Math.pow(2, attempts - 1));
        setTimeout(connect, delay);
      };
    };
    connect();

    return () => {
      closed = true;
      names.forEach((n) => es?.removeEventListener(n, handler as EventListener));
      es?.close();
    };
  },

  /** 质量评价（慢操作，调 LLM 分析） */
  evaluate: (id: string) =>
    request<QualityEvaluationResult>(`/projects/${id}/evaluate`, { method: 'POST' }, 300000),

  /** p32-11: 读回最近一次全书评价（未评价时 evaluation=null） */
  getQualityEvaluation: (id: string) =>
    request<{ evaluation: QualityEvaluationResult | null; evaluatedAt?: string }>(
      `/projects/${id}/evaluate`),

  // --- p7: 交互式审核 + 章节重写 + 预设 ---

  /** 列出待审核 checkpoint */
  listCheckpoints: (id: string) =>
    request<Checkpoint[]>(`/projects/${id}/checkpoints`),

  /** 批准 checkpoint */
  approveCheckpoint: (id: string, checkpointId: string) =>
    request<{ status: string; checkpointId: string }>(`/projects/${id}/checkpoints/${checkpointId}/approve`, { method: 'POST' }),

  /** 拒绝 checkpoint（带修改意见重写当前章） */
  rejectCheckpoint: (id: string, checkpointId: string, instructions: string) =>
    request<CheckpointOperationResponse>(
      `/projects/${id}/checkpoints/${checkpointId}/reject`,
      { method: 'POST', body: JSON.stringify({ instructions }) },
      300000,
    ),

  /** 修改 checkpoint（带意见重写后批准） */
  modifyCheckpoint: (id: string, checkpointId: string, instructions: string) =>
    request<CheckpointOperationResponse>(
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

  updatePreferences: (id: string, body: Record<string, unknown>) =>
    request<Record<string, unknown>>(`/projects/${id}/preferences`, {
      method: 'PUT',
      body: JSON.stringify(body),
    }),

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
