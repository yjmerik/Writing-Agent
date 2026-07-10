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
  createdAt: string;
  updatedAt: string;
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
}
