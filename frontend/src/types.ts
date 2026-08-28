/** TypeScript 类型定义，对应后端 domain 对象的 JSON 序列化 */

export type ProjectMode = 'SOLO' | 'INTERACTIVE';

export type NovelState =
  | 'CONCEIVING'
  | 'PLANNING'
  | 'WRITING'
  | 'VOLUME_DONE'
  | 'FINAL'
  | 'PAUSED_AT_CHECKPOINT';

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

export interface ProjectStatus {
  projectId: string;
  state: NovelState;
  mode: ProjectMode;
  currentChapter: number;
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
}

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
