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
  LlmModelEntry,
  LlmModelOverview,
  LlmModelUpdateRequest,
  AuthorIntent,
  AuthorIntentHistory,
  WorkspaceView,
  CharacterAsOf,
  CharacterDecisionItem,
  CharacterDecisionExtractResult,
  RevisionRequestItem,
  ImpactAnalysisResult,
  RepairGenerationResult,
  GroupAcceptResult,
  SceneContractResult,
  PlanDeviationItem,
  VolumeReadbackItem,
  AestheticProfileData,
  ReviewClassification,
  DelegationRules,
  BookEditionSummary,
  BookEditionDetail,
  EditionValidation,
} from '../types';

export interface PersistedWritingTask extends CollaborationTask {
  projectId: string;
  type: 'CONCEIVE' | 'WRITE_CHAPTER' | 'WRITE_ALL' | 'AUTO_RUN';
  currentChapter: number;
  totalChapters: number;
  chaptersWritten: number;
  totalWords: number;
  createdAt: string;
  updatedAt: string;
}

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

/** p36-05: 不可变稿件身份——批准只能绑定作者已读的具体版本与正文指纹 */
export interface ReviewIdentity {
  projectId: string;
  chapterIndex: number;
  version: number;
  contentHash: string;
}

/** p36-04: 审阅上下文快照——待审集合指纹与既有批准；空集合不表示已通过 */
export interface ReviewContext {
  identity: ReviewIdentity;
  reviewSetHash: string;
  pendingCheckpointIds: string[];
  barrierIds: string[];
  reportCurrent: string | null;
  approval: {
    approvalId: string;
    identity: ReviewIdentity;
    reviewSetHash: string;
    idempotencyKey: string;
    reason: string | null;
    createdAt: string;
  } | null;
  issues: string[];
  dispositions: string[];
  directives: string[];
}

export interface ReviewApprovalResult {
  approvalId: string;
  identity: ReviewIdentity;
  replayed: boolean;
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

/** p37-03/04: 持久修订候选（DB 资源，刷新/重启后可恢复） */
export interface RevisionCandidateRecord {
  id: string;
  projectId: string;
  targetType: AgentRevisionTargetType;
  targetId: string;
  chapterIndex: number | null;
  passageIndex: number | null;
  baseVersion: number;
  sourceHash: string;
  instructions: string | null;
  beforeJson: string;
  afterJson: string;
  summary: string | null;
  status: 'READY' | 'APPLIED' | 'STALE' | 'CANCELLED';
  requestKey: string;
  resultJson: string | null;
  createdAt: string;
  updatedAt: string;
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

export interface PlanTargetPreview {
  applied: boolean;
  targetChapters: number;
  /** p38-03: 预览凭证——确认必须原样带回 */
  previewId?: string;
  sourcePlanHash?: string;
  prunedChapters?: number[];
  affectedPromises?: Array<{
    id: string;
    description: string;
    expectedResolveChapter: number;
    plantedAtChapter: number;
    mustResolve?: boolean;
    priority?: string;
  }>;
  preview: {
    prunedChapters: number[];
    affectedPromises: Array<{
      id?: string;
      description: string;
      expectedResolveChapter: number;
      plantedAtChapter: number;
      mustResolve?: boolean;
      priority?: string;
    }>;
    writtenChapters: number;
    oldTarget: number;
  };
}

/** p38-03: 承诺处置决定 */
export interface PromiseDecisionInput {
  id: string;
  promiseId: string;
  action: 'MIGRATE' | 'OPEN_END';
  targetChapter?: number;
  reason?: string;
}

export interface ForeshadowCandidateView {
  id: string;
  description: string;
  plantedContext: string;
  plantedAtChapter: number;
  status: 'PENDING' | 'PROMOTED' | 'REJECTED';
}

export interface BookReviewData {
  projectId: string;
  title: string;
  state: string;
  targetChapters: number;
  chapters: Array<{
    chapterIndex: number;
    title: string;
    version: number;
    status: string;
    wordCount: number;
    grade: string;
    reportCurrent: boolean;
    planRef?: string | null;
    /** p36-11: 当前稿件身份是否有作者批准记录——DONE 不等于批准 */
    approved?: boolean;
  }>;
  summary: { doneChapters: number; totalChapters: number; totalWords: number };
  unresolvedPromises: Array<{
    description: string;
    priority: string;
    plantedAtChapter: number;
    expectedResolveChapter: number;
    mustResolve: boolean;
  }>;
}

export interface DirectiveEvidenceView {
  id: string; directiveRevision: number; chapterIndex: number; chapterVersion: number;
  contentHash: string; quote: string | null; reason: string | null;
  status: 'APPLIED_SYSTEM' | 'CONFIRMED' | 'UNMET' | 'STALE';
}

export interface AuthorDirectiveView {
  id: string;
  kind: 'HARD' | 'ONCE' | 'PREFERENCE';
  chapterIndex: number | null;
  content: string;
  status: 'OPEN' | 'APPLIED_SYSTEM' | 'CONFIRMED' | 'DISMISSED';
  systemEvidence: string | null;
  systemEvidenceVersion: number | null;
  revision: number;
  evidence: DirectiveEvidenceView[];
  createdAt: string;
  confirmedAt?: string;
}

export interface WorkspaceTodo {
  chapterIndex: number;
  chapterVersion: number;
  kind: string;
  reason: string;
  raisedAt: string;
}

export interface WorkspaceStatus {
  projectId: string;
  stage: string;
  busy: boolean;
  collabState: 'WRITING' | 'HANDING_OVER' | 'AWAITING_REVIEW' | 'FINISHED' | 'BLOCKED';
  primaryAction: 'REVIEW_CHAPTER' | 'WRITE_NEXT' | 'TAKE_OVER' | 'EXPORT' | 'WAIT_HANDOVER' | 'RESOLVE_BLOCKED';
  canContinue: boolean;
  blockedReason: string | null;
  todoList: WorkspaceTodo[];
  currentChapter: number;
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
  listWritingTasks: (id: string) => request<PersistedWritingTask[]>(`/projects/${id}/writing-tasks`),
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
  // NCS-003/004: 创作约定
  getIntent: (id: string) => request<AuthorIntent>(`/projects/${id}/intent`),
  saveIntent: (id: string, intent: Partial<AuthorIntent>) =>
    request<AuthorIntent>(`/projects/${id}/intent`, { method: 'PUT', body: JSON.stringify(intent) }),
  getIntentHistory: (id: string) => request<AuthorIntentHistory>(`/projects/${id}/intent/history`),
  // NCS-005/006: 统一工作台读取
  getWorkspaceView: (id: string) => request<WorkspaceView>(`/projects/${id}/workspace-view`),
  // NCS-010/011: 人物时点读取
  getCharacterAsOf: (id: string, charName: string, chapter: number) =>
    request<CharacterAsOf>(`/projects/${id}/characters/${encodeURIComponent(charName)}/as-of?chapter=${chapter}`),
  // NCS-027: 成书版本
  listBookEditions: (id: string) =>
    request<BookEditionSummary[]>(`/projects/${id}/book-editions`),
  createBookEdition: (id: string) =>
    request<{ editionId: string; manifestHash: string; state: string; chapterCount: number; approvedChapters: number }>(
      `/projects/${id}/book-editions`, { method: 'POST' }),
  getBookEditionDetail: (id: string, editionId: string) =>
    request<BookEditionDetail>(`/projects/${id}/book-editions/${editionId}`),
  validateBookEdition: (id: string, editionId: string) =>
    request<EditionValidation>(`/projects/${id}/book-editions/${editionId}/validate`, { method: 'POST' }),
  getEditionValidation: (id: string, editionId: string) =>
    request<EditionValidation>(`/projects/${id}/book-editions/${editionId}/validation`),
  approveBookEdition: (id: string, editionId: string, manifestHash: string) =>
    request<Record<string, unknown>>(`/projects/${id}/book-editions/${editionId}/approve`,
      { method: 'POST', body: JSON.stringify({ manifestHash }) }),
  exportBookEditionUrl: (id: string, editionId: string) =>
    `/api/projects/${id}/book-editions/${editionId}/export`,

  // NCS-025: 委托策略
  getDelegationPolicy: (id: string) =>
    request<{ projectId: string; rules: DelegationRules }>(`/projects/${id}/delegation-policy`),
  saveDelegationPolicy: (id: string, rules: DelegationRules) =>
    request<{ projectId: string; rules: DelegationRules }>(`/projects/${id}/delegation-policy`,
      { method: 'PUT', body: JSON.stringify({ rules }) }),

  // NCS-023: 三类信息分层
  getReviewClassification: (id: string) =>
    request<ReviewClassification>(`/projects/${id}/review-classification`),

  // NCS-022: 审美档案
  getAestheticProfile: (id: string) =>
    request<AestheticProfileData>(`/projects/${id}/aesthetic-profile`),
  saveAestheticProfile: (id: string, body: Partial<AestheticProfileData>) =>
    request<AestheticProfileData>(`/projects/${id}/aesthetic-profile`, { method: 'PUT', body: JSON.stringify(body) }),

  // NCS-020: 卷末回读
  listVolumeReadbacks: (id: string) =>
    request<{ projectId: string; readbacks: VolumeReadbackItem[] }>(`/projects/${id}/volume-readbacks`),
  generateVolumeReadback: (id: string, volume: number) =>
    request<{ volumeIndex: number; generated: boolean }>(`/projects/${id}/volume-readbacks/generate?volume=${volume}`, { method: 'POST' }),
  acknowledgeVolumeReadback: (id: string, volume: number) =>
    request<{ volumeIndex: number; status: string }>(`/projects/${id}/volume-readbacks/${volume}/acknowledge`, { method: 'POST' }),

  // NCS-019: 计划偏离
  listPlanDeviations: (id: string) =>
    request<{ projectId: string; deviations: PlanDeviationItem[] }>(`/projects/${id}/plan-deviations`),
  analyzePlanDeviation: (id: string, chapter: number) =>
    request<{ chapterIndex: number; analyzed: boolean }>(`/projects/${id}/plan-deviations/analyze?chapter=${chapter}`, { method: 'POST' }),
  decidePlanDeviation: (id: string, chapter: number, action: string) =>
    request<PlanDeviationItem>(`/projects/${id}/plan-deviations/${chapter}/decide`,
      { method: 'POST', body: JSON.stringify({ action }) }),

  // NCS-018: 场景合同
  getSceneContract: (id: string, chapter: number) =>
    request<SceneContractResult>(`/projects/${id}/outline/scene-contract?chapter=${chapter}`),
  generateSceneContract: (id: string, chapter: number) =>
    request<SceneContractResult>(`/projects/${id}/outline/scene-contract?chapter=${chapter}`, { method: 'POST' }),
  saveSceneContract: (id: string, chapter: number, contract: string) =>
    request<SceneContractResult>(`/projects/${id}/outline/scene-contract?chapter=${chapter}`,
      { method: 'PUT', body: JSON.stringify({ contract }) }),

  // NCS-013..017: 统一修订请求
  listRevisionRequests: (id: string) =>
    request<{ projectId: string; requests: RevisionRequestItem[] }>(`/projects/${id}/revision-requests`),
  createRevisionRequest: (id: string, body: Record<string, unknown>) =>
    request<RevisionRequestItem>(`/projects/${id}/revision-requests`, { method: 'POST', body: JSON.stringify(body) }),
  approveRevisionScope: (id: string, requestId: string, decision: string) =>
    request<RevisionRequestItem>(`/projects/${id}/revision-requests/${requestId}/approve-scope`,
      { method: 'POST', body: JSON.stringify({ decision }) }),
  analyzeImpact: (id: string, requestId: string, chapter: number) =>
    request<ImpactAnalysisResult>(`/projects/${id}/revision-requests/${requestId}/analyze-impact?chapter=${chapter}`,
      { method: 'POST' }),
  generateRepairs: (id: string, requestId: string, chapter: number, intent: string) =>
    request<RepairGenerationResult>(`/projects/${id}/revision-requests/${requestId}/generate-repairs?chapter=${chapter}`,
      { method: 'POST', body: JSON.stringify({ intent }) }),
  acceptRevisionGroup: (id: string, requestId: string, commandId: string) =>
    request<GroupAcceptResult>(`/projects/${id}/revision-requests/${requestId}/accept-group`,
      { method: 'POST', body: JSON.stringify({ commandId }) }),

  // NCS-012: 关键决策
  getCharacterDecisions: (id: string) =>
    request<{ projectId: string; decisions: CharacterDecisionItem[] }>(`/projects/${id}/decisions`),
  extractCharacterDecisions: (id: string, chapter: number) =>
    request<CharacterDecisionExtractResult>(`/projects/${id}/decisions/extract?chapter=${chapter}`, { method: 'POST' }),

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

  /** p39-04: 人物修改预览——confirm=false 不落库，返回影响清单与确认凭证 */
  previewCharacterUpdate: (id: string, charId: string, body: Partial<Character>) =>
    request<{persisted: boolean; previewToken: string; impact: {
      certainReferences: number[]; possibleReferences: number[];
      insufficientCoverage: number[]; changes: Record<string, unknown>;
    }}>(`/projects/${id}/characters/${charId}`, {
      method: 'PUT',
      body: JSON.stringify({...body, confirm: false}),
    }),

  /** p39-04: 人物修改确认——必须携带预览凭证 */
  confirmCharacterUpdate: (id: string, charId: string, body: Partial<Character>,
                           previewToken: string) =>
    request<{persisted: boolean; character: Character; previewToken: string}>(
      `/projects/${id}/characters/${charId}`, {
        method: 'PUT',
        body: JSON.stringify({...body, confirm: true, previewToken}),
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

  /** p37-04: 恢复入口——查询持久候选（刷新/重启后仍在） */
  listRevisionCandidates: (projectId: string, targetId?: string, status?: string) => {
    const params = new URLSearchParams();
    if (targetId) params.set('targetId', targetId);
    if (status) params.set('status', status);
    const qs = params.toString();
    return request<RevisionCandidateRecord[]>(
      `/projects/${projectId}/revisions${qs ? `?${qs}` : ''}`);
  },

  combineRevisionCandidates: (projectId: string, body: {
    requestId: string; chapterIndex: number; fragments: { previewId: string; paragraphs: number[] }[];
  }) => request<{ previewId: string; content: string; sources: string[]; needsRecheck: boolean }>(
    `/projects/${projectId}/selective-accept/combine`, { method: 'POST', body: JSON.stringify(body) }),

  recheckCombinedCandidate: (projectId: string, previewId: string) =>
    request<{ needsRecheck: boolean }>(`/projects/${projectId}/selective-accept/${previewId}/recheck`, { method: 'POST' }, 300000),

  acceptCombinedCandidate: (projectId: string, previewId: string) =>
    request<{ accepted: boolean }>(`/projects/${projectId}/selective-accept/${previewId}/accept`, { method: 'POST' }, 300000),

  cancelRevisionCandidate: (projectId: string, candidateId: string) =>
    request<RevisionCandidateRecord>(`/projects/${projectId}/revisions/${candidateId}/cancel`, {
      method: 'POST',
    }),

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

  /** p36-05: 已读正文身份、待审集合指纹与既有批准的一致快照 */
  getReviewContext: (id: string, chapterIndex: number) =>
    request<ReviewContext>(`/projects/${id}/chapters/${chapterIndex}/review-context`),

  /** p36-05: 版本化批准——identity/reviewSetHash 必须与作者已读内容一致，服务端不补填 */
  recheckChapter: (id: string, chapterIndex: number, identity: ReviewIdentity) =>
    request<{ reportCurrent: boolean }>(`/projects/${id}/chapters/${chapterIndex}/recheck`, {
      method: 'POST', body: JSON.stringify({ identity }),
    }, 300000),

  approveChapterRevision: (id: string, chapterIndex: number, body: {
    identity: ReviewIdentity;
    reviewSetHash: string;
    idempotencyKey: string;
    reason?: string;
  }) => request<ReviewApprovalResult>(`/projects/${id}/chapters/${chapterIndex}/review-decisions`, {
    method: 'POST',
    body: JSON.stringify(body),
  }),

  /** NCS-071: HIGH 问题裁决——误报/创作例外/暂挂，理由强制，绑定当前稿件身份 */
  createDisposition: (id: string, body: {
    issueId: string;
    action: 'FALSE_POSITIVE' | 'EXCEPTION' | 'DEFER';
    reason: string;
    deferUntilChapter?: number;
    identity: ReviewIdentity;
  }) => request<{ id: string; issueId: string; action: string; reason: string }>(
    `/projects/${id}/dispositions`, { method: 'POST', body: JSON.stringify(body) }),

  /** NCS-071: 按稿件身份列出台账中的既有裁决 */
  listDispositions: (id: string, chapterIndex: number, chapterVersion: number, contentHash: string) =>
    request<Array<{ id: string; issueId: string; action: string; reason: string; deferUntilChapter: number | null; createdAt: string }>>(
      `/projects/${id}/dispositions?chapterIndex=${chapterIndex}&chapterVersion=${chapterVersion}&contentHash=${encodeURIComponent(contentHash)}`),

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

  /** p33-02: 统一工作区状态——按钮可用性的单一来源 */
  getWorkspace: (id: string) =>
    request<WorkspaceStatus>(`/projects/${id}/workspace`),

  // --- p35-01: 作者意见闭环 UI API ---
  getDirectives: (id: string, pendingOnly?: boolean) =>
    request<AuthorDirectiveView[]>(`/projects/${id}/directives${pendingOnly ? '?pendingOnly=true' : ''}`),
  createDirective: (id: string, body: { kind: string; chapterIndex?: number; content: string }) =>
    request<AuthorDirectiveView>(`/projects/${id}/directives`, { method: 'POST', body: JSON.stringify(body) }),
  // --- p35-03: 篇幅变更 UI API ---
  getPlanTargetPreview: (id: string, newTarget: number) =>
    request<PlanTargetPreview>(`/projects/${id}/plan/target-chapters`,
      { method: 'POST', body: JSON.stringify({ targetChapters: newTarget, confirm: false }) }),
  applyPlanTarget: (id: string, newTarget: number, credential: {
    previewId: string;
    sourcePlanHash: string;
    decisions?: PromiseDecisionInput[];
    requestKey?: string;
  }) =>
    request<PlanTargetPreview>(`/projects/${id}/plan/target-chapters`,
      { method: 'POST', body: JSON.stringify({
        targetChapters: newTarget, confirm: true,
        previewId: credential.previewId,
        sourcePlanHash: credential.sourcePlanHash,
        decisions: credential.decisions ?? [],
        requestKey: credential.requestKey ?? crypto.randomUUID(),
      }) }),

  // --- p35-02: 候选线索层 UI API ---
  getForeshadowCandidates: (id: string) =>
    request<ForeshadowCandidateView[]>(`/projects/${id}/foreshadow-candidates`),
  promoteForeshadowCandidate: (id: string, candidateId: string) =>
    request<{ promoted: boolean }>(`/projects/${id}/foreshadow-candidates/${candidateId}/promote`, { method: 'POST' }),
  rejectForeshadowCandidate: (id: string, candidateId: string) =>
    request<{ rejected: boolean }>(`/projects/${id}/foreshadow-candidates/${candidateId}/reject`, { method: 'POST' }),

  confirmDirectiveEvidence: (id: string, directiveId: string, evidenceId: string,
    identity: ReviewIdentity, directiveRevision: number) =>
    request<DirectiveEvidenceView>(`/projects/${id}/directives/${directiveId}/evidence/${evidenceId}/confirm`, {
      method: 'POST', body: JSON.stringify({ identity, directiveRevision }),
    }),

  confirmDirective: (id: string, directiveId: string) =>
    request<AuthorDirectiveView>(`/projects/${id}/directives/${directiveId}/confirm`, { method: 'POST' }),
  // --- p35-05: 成书审查 UI API ---
  getBookReview: (id: string) =>
    request<BookReviewData>(`/projects/${id}/book-review`),
  getBookExportUrl: (id: string) => `${BASE}/projects/${id}/book-export`,

  dismissDirective: (id: string, directiveId: string) =>
    request<AuthorDirectiveView>(`/projects/${id}/directives/${directiveId}/dismiss`, { method: 'POST' }),

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

  // --- p43-01: 模型配置 UI API ---
  getLlmModels: () =>
    request<LlmModelOverview>('/llm/models'),

  updateLlmModel: (scope: string, body: LlmModelUpdateRequest) =>
    request<LlmModelEntry>(`/llm/models/${scope}`, {
      method: 'PUT',
      body: JSON.stringify(body),
    }),

  deleteLlmModel: (scope: string) =>
    request<LlmModelEntry>(`/llm/models/${scope}`, { method: 'DELETE' }),
};
