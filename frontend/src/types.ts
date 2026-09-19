/** TypeScript 类型定义，对应后端 domain 对象的 JSON 序列化 */

export type ProjectMode = 'SOLO' | 'INTERACTIVE';

export type NovelState =
  | 'CONCEIVING'
  | 'PLANNING'
  | 'WRITING'
  | 'VOLUME_DONE'
  | 'FINAL'
  | 'NEEDS_REVISION'
  | 'PAUSED_AT_CHECKPOINT';

export type ProjectAction = 'ADVANCE' | 'WRITE_CHAPTER' | 'WRITE_ALL' | 'FINAL_AUDIT';

export interface Project {
  id: string;
  title: string;
  concept: string;
  tags: string;
  mode: ProjectMode;
  state: NovelState;
  createdAt: string;
  updatedAt: string;
  currentChapterIndex: number;
}

export interface ProjectListItem extends Project {
  targetChapters: number;
  progressPercent: number;
  pauseStage: NovelState | null;
  pauseReason: string | null;
}

export interface ProjectStatus {
  projectId: string;
  state: NovelState;
  mode: ProjectMode;
  currentChapter: number;
  targetChapters: number;
  progressPercent: number;
  pauseStage: NovelState | null;
  pauseReason: string | null;
  totalWords: number;
  allowedActions: ProjectAction[];
  blockedReasons: Partial<Record<ProjectAction, string>>;
}

export interface Character {
  id: string;
  projectId: string;
  name: string;
  role: string;
  personality: string | null;
  ability: string | null;
  motivation: string | null;
  appearance: string | null;
  relationships: string | null;
  growthArc: string | null;
  version: number;
  updatedAt: string;
}

export interface KnowledgeEntry {
  id: string;
  projectId: string;
  type: string;
  name: string;
  content: string;
  version: number;
  updatedAt: string;
}

export interface ChapterSynopsis {
  id: string;
  projectId: string;
  chapterIndex: number;
  title: string;
  synopsis: string;
  writingNotes: string | null;
}

export interface Volume {
  id: string;
  projectId: string;
  volumeIndex: number;
  title: string;
  summary: string;
  synopses: ChapterSynopsis[];
}

export interface Outline {
  projectId: string;
  premise: string;
  volumes: Volume[];
}

export interface Chapter {
  id: string;
  projectId: string;
  chapterIndex: number;
  title: string;
  content: string;
  wordCount: number;
  version: number;
  status?: string;
  createdAt: string;
  updatedAt: string;
  /** p32-01: 正文已保存，但派生数据（摘要/索引/收割）刷新未完成 */
  derivedRefreshPending?: boolean;
}

export interface ChapterVersion {
  id: string;
  projectId: string;
  chapterIndex: number;
  version: number;
  title: string;
  content: string;
  source: string;
  createdAt: string;
}

/** p21-04: 章节元数据分页响应（chapters 不含正文） */
export interface ChapterPage {
  chapters: Chapter[];
  total: number;
  page: number;
  size: number;
}

/** p21-08: SSE 连接状态 */
export type SseState = 'connected' | 'reconnecting' | 'fallback';

export interface Foreshadowing {
  id: string;
  projectId: string;
  description: string;
  resolved: boolean;
  mustResolve: boolean;
  plantedAtChapter: number;
}

/** p11-I3: 章节质量报告 */
export interface ChapterQualityReport {
  chapterIndex: number;
  chapterTitle: string;
  wordCount: number;
  grade: string;
  review: {
    overallScore: number;
    engagement: number;
    coherence: number;
    character: number;
    hook: number;
    summary: string;
    issues: string;
    improvement: string;
  } | null;
  issues: Array<{
    type: string;
    severity: string;
    description: string;
    resolved: boolean;
  }>;
  clicheCount: number;
  topCliches: string[];
  needsManualReview?: boolean;
  qualityRisk?: boolean;
  /** p34-02: 报告绑定版本与有效性——旧稿报告不得冒充当前审查 */
  chapterVersion?: number;
  reportForVersion?: number | null;
  reportCurrent?: boolean;
  staleIssueCount?: number;
  reviewStale?: boolean;
}

export interface Summary {
  id: string;
  projectId: string;
  fromChapter: number;
  toChapter: number;
  content: string;
  updatedAt: string;
}

export interface CreateProjectRequest {
  title: string;
  concept: string;
  tags: string;
  mode: ProjectMode;
  preset?: string;
  targetChapters?: number;
}

export type CheckpointType = 'AFTER_INITIAL_SETUP' | 'AFTER_VOLUME_OUTLINE' | 'AFTER_CHAPTER' | 'ON_CONTINUITY_FIX';
export type CheckpointStatus = 'PENDING' | 'APPROVED' | 'MODIFIED' | 'REJECTED';

/** p18-02: 章后审核 checkpoint 的质量 payload（JSON 字符串，AFTER_CHAPTER 类型携带） */
export interface CheckpointPayload {
  chapterIndex?: number;
  title?: string;
  wordCount?: number;
  preview?: string;
  score?: number;
  unresolvedHigh?: number;
  worldSummary?: string;
  characterSummary?: string;
  premiseSummary?: string;
  links?: Array<{
    label: string;
    target: 'world' | 'outline';
  }>;
}

export interface Checkpoint {
  id: string;
  projectId: string;
  type: CheckpointType;
  description: string;
  status: CheckpointStatus;
  createdAt: string;
  payload?: string | null;
}

export type ReviewPolicy = 'MANUAL' | 'CHAPTER' | 'VOLUME';
export interface CollaborationTask {
  id: string;
  status: 'RUNNING' | 'PAUSED' | 'COMPLETED' | 'FAILED' | 'CANCELLED';
  instruction: string | null;
  errorMessage: string | null;
  stopRequested: boolean;
}
export interface CollaborationStatus {
  projectId: string;
  state: NovelState;
  busy: boolean;
  task: CollaborationTask | null;
  stopRequested: boolean;
  pendingReviews: number;
  canContinue: boolean;
  blockedReason: string | null;
  reviewPolicy: ReviewPolicy;
  currentChapter: number;
  targetChapters: number;
  totalWords: number;
}

/** p43-01: 模型配置页面 */
export type LlmProtocolType = 'OPENAI_CHAT_COMPLETIONS' | 'OPENAI_RESPONSES' | 'ANTHROPIC_MESSAGES';
export type LlmConfigSource = 'PAGE' | 'YML' | 'FALLBACK';

export interface LlmModelEntry {
  scope: string;
  label: string;
  source: LlmConfigSource;
  protocol: LlmProtocolType;
  baseUrl: string;
  model: string;
  apiKeyMasked: string;
  hasApiKey: boolean;
  temperature: number;
  maxTokens: number;
  contextWindow: number;
  streaming: boolean;
  requestTimeoutSeconds: number;
}

export interface LlmModelOverview {
  entries: LlmModelEntry[];
  protocols: { value: LlmProtocolType; label: string }[];
  addableScopes: { scope: string; label: string }[];
}

export interface LlmModelUpdateRequest {
  protocol?: string;
  baseUrl?: string;
  apiKey?: string;
  model?: string;
  temperature?: number;
  maxTokens?: number;
  contextWindow?: number;
  streaming?: boolean;
  requestTimeoutSeconds?: number;
}

// NCS-003/004: 创作约定（AuthorIntent）——作者与 Agent 的版本化创作契约
export interface AuthorIntent {
  projectId: string;
  revision: number;
  premise: string;
  genreExpectation: string;
  themes: string;
  pov: string;
  languageSamples: string;
  lengthRange: string;
  autonomy: string;
  unwantedPatterns: string;
  updatedAt: string;
}

export interface AuthorIntentHistory {
  projectId: string;
  revisions: AuthorIntent[];
}

// NCS-005/006: WorkspaceView 统一读取模型
export interface WorkspaceViewCurrentChapter {
  index: number;
  title: string;
  version: number;
  status: string;
}

export interface WorkspaceView {
  identity: {
    projectId: string;
    title: string;
    state: string;
    mode: string;
    currentChapterIndex: number;
    revisionOfView: string;
  };
  contentStatus: {
    currentChapter?: WorkspaceViewCurrentChapter;
    totalChapters: number;
    doneChapters: number;
    pendingCandidates: number;
  };
  run?: {
    taskId: string;
    type: string;
    status: string;
    stopRequested: boolean;
    chaptersWritten: number;
    currentChapter: number;
    errorMessage: string | null;
  };
  review: {
    activeBarriers: number;
    pendingCheckpoints: number;
    unknownChecks: number;
    staleIssues: number;
  };
  availableActions: { action: string; allowed: boolean; reason: string }[];
}

// NCS-010/011: 人物时点读取
export interface CharacterAsOfFact {
  factId: string;
  type: string;
  assertion: string;
  predicate: string | null;
  objectValue: string | null;
  polarity: string | null;
  validFromChapter: number;
  validToChapter: number | null;
}

export interface CharacterAsOfLearned {
  factId: string;
  quote: string;
  learnedAtChapter: number;
  sourceChapter: number;
  sourceVersion: number;
  evidenceStale: boolean;
  beliefStatus?: string;
}

export interface CharacterAsOfRelationship {
  relationship: string;
  source: string;
  target: string;
  validFromChapter: number;
  validToChapter: number;
  direction: string;
}

export interface CharacterAsOf {
  character: { id: string; name: string; role: string; status: string };
  asOfChapter: number;
  facts: CharacterAsOfFact[];
  knows: CharacterAsOfLearned[];
  believes: CharacterAsOfLearned[];
  relationships: CharacterAsOfRelationship[];
}

// NCS-012: 人物关键决策
export interface CharacterDecisionItem {
  id: string;
  character: string;
  chapterIndex: number;
  chapterVersion: number;
  triggerEvent: string;
  knewThen: string;
  options: string[];
  chosenAction: string;
  cost: string;
  conflictWithCommitment: string;
  evidenceQuote: string;
}

export interface CharacterDecisionExtractResult {
  chapterIndex: number;
  chapterVersion: number;
  extracted: number;
  status: string;
  decisions: CharacterDecisionItem[];
}

// NCS-013..017: 统一修订请求与修订工作区
export interface RevisionRequestItem {
  id: string;
  origin: string;
  intent: string;
  targetAnchors: string;
  baseVersions: string;
  protectAnchors: string;
  status: string;
  scopeDecision: string | null;
  acceptance: string | null;
  cancelReason: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface ImpactAnalysisResult {
  requestId: string;
  revisedChapterIndex: number;
  revisedToVersion: number;
  coverageNote: string;
  items: {
    chapterIndex: number;
    classification: string;
    reasons: string[];
    factIds: string[];
    subjects: string[];
  }[];
}

export interface RepairGenerationResult {
  requestId: string;
  generated: number;
  previewIds: string[];
  chapters: number[];
}

export interface GroupAcceptResult {
  commandId: string;
  applied: boolean;
  replayed: boolean;
  chapters: number[];
  message: string;
}

// NCS-018: 场景合同
export interface SceneContractResult {
  chapterIndex: number;
  generated?: boolean;
  contract: Record<string, string> | null;
}

// NCS-019: 计划偏离
export interface PlanDeviationItem {
  chapterIndex: number;
  chapterVersion: number;
  deviation: string;
  decision: string | null;
  decidedAt: string | null;
}

// NCS-020: 卷末回读
export interface VolumeReadbackItem {
  volumeIndex: number;
  report: string;
  status: string;
  acknowledgedAt: string | null;
}
