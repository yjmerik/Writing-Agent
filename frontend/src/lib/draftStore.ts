/**
 * p36-09（UI 验收 A2 加强）：标题+正文草稿的本地暂存、冲突保留与配额失败上报。
 * 纯函数模块（storage 注入，便于 Node 直接验证）；UI 层传 localStorage。
 *
 * 键：
 *   novel-draft:{projectId}:{chapterIndex}           —— 基于当前基准的活跃草稿
 *   novel-draft-conflict:{projectId}:{chapterIndex}  —— 基准已变化的冲突副本（作者显式处理前不清除）
 *
 * 草稿结构：{ baseVersion, baseHash?, title, content, savedAt }
 *   baseHash 为开始编辑时基准内容指纹（与后端 contentHash 同构；p34-03 旧格式无此字段）。
 *
 * loadDraft 返回四种结果：
 *   missing     —— 无草稿
 *   matching    —— 基准版本与指纹均与当前稿一致（旧格式无指纹→legacy=true，UI 提示核对，不自动覆盖）
 *   conflict    —— 基准版本或指纹已变化：草稿转入冲突副本槽位保留（不覆盖既有副本），供并排比较
 *   unavailable —— 存储读取失败（配额/隐私模式）
 *
 * saveDraft 返回是否成功持久化——失败即"未暂存"，UI 必须提示并启用离开保护。
 * 冲突副本只随作者显式"放弃"清除；新草稿写入永不触碰冲突槽位。
 */

export interface StoredDraft {
  baseVersion: number;
  baseHash?: string;
  title: string;
  content: string;
  savedAt: string;
  /** p34-03 旧格式（无基准指纹）：仅可提示核对恢复，不自动覆盖。 */
  legacy?: boolean;
}

export type DraftLoadResult =
  | { kind: 'missing' }
  | { kind: 'matching'; draft: StoredDraft }
  | { kind: 'conflict'; draft: StoredDraft; conflictCopy: StoredDraft | null }
  | { kind: 'unavailable'; error: string };

export interface DraftStorage {
  getItem(key: string): string | null;
  setItem(key: string, value: string): void;
  removeItem(key: string): void;
}

export function draftKey(projectId: string, chapterIndex: number): string {
  return `novel-draft:${projectId}:${chapterIndex}`;
}

export function conflictDraftKey(projectId: string, chapterIndex: number): string {
  return `novel-draft-conflict:${projectId}:${chapterIndex}`;
}

/**
 * 与后端 ChapterApproval.contentHash 同构：SHA-256(8字节大端标题长度前缀 ‖ 标题 ‖ 8字节正文长度前缀 ‖ 正文)。
 * 依赖 crypto.subtle（localhost 安全上下文可用）。
 */
export async function contentHashHex(title: string, content: string): Promise<string> {
  const encoder = new TextEncoder();
  const titleBytes = encoder.encode(title);
  const contentBytes = encoder.encode(content);
  const buffer = new Uint8Array(16 + titleBytes.length + contentBytes.length);
  const view = new DataView(buffer.buffer);
  view.setUint32(0, Math.floor(titleBytes.length / 2 ** 32));
  view.setUint32(4, titleBytes.length >>> 0);
  buffer.set(titleBytes, 8);
  view.setUint32(8 + titleBytes.length, Math.floor(contentBytes.length / 2 ** 32));
  view.setUint32(12 + titleBytes.length, contentBytes.length >>> 0);
  buffer.set(contentBytes, 16 + titleBytes.length);
  const digest = await crypto.subtle.digest('SHA-256', buffer);
  return Array.from(new Uint8Array(digest))
    .map(b => b.toString(16).padStart(2, '0'))
    .join('');
}

/** 暂存活跃草稿；返回是否成功持久化（false=未暂存，UI 需提示并启用离开保护）。 */
export function saveDraft(storage: DraftStorage, projectId: string, chapterIndex: number,
                          baseVersion: number, baseHash: string | undefined,
                          title: string, content: string): boolean {
  const draft: StoredDraft = {
    baseVersion, title, content, savedAt: new Date().toISOString(),
  };
  if (baseHash !== undefined) draft.baseHash = baseHash;
  try {
    storage.setItem(draftKey(projectId, chapterIndex), JSON.stringify(draft));
    return true;
  } catch {
    // 存储失败（隐私模式/配额）：内容仍在编辑器中，但未暂存
    return false;
  }
}

function readDraft(storage: DraftStorage, key: string): StoredDraft | null {
  const raw = storage.getItem(key);
  if (!raw) return null;
  try {
    const parsed = JSON.parse(raw) as StoredDraft;
    if (typeof parsed.baseVersion !== 'number'
      || typeof parsed.title !== 'string'
      || typeof parsed.content !== 'string') return null;
    if (!('baseHash' in parsed)) parsed.legacy = true;
    return parsed;
  } catch {
    return null;
  }
}

/**
 * 读取草稿并归类。基准已变化时把过期草稿转入冲突副本槽位（既有副本优先保留，
 * 新检测到的冲突不覆盖未处理副本），UI 据此展示并排比较/复制/放弃。
 * currentHash 传入当前稿指纹用于同版本内容变化检测；缺省时仅按版本号判断。
 */
export function loadDraft(storage: DraftStorage, projectId: string,
                          chapterIndex: number, currentVersion: number,
                          currentHash?: string): DraftLoadResult {
  try {
    const draft = readDraft(storage, draftKey(projectId, chapterIndex));
    if (!draft) return { kind: 'missing' };
    const baseMatches = draft.baseVersion === currentVersion
      && (!draft.baseHash || !currentHash || draft.baseHash === currentHash);
    if (baseMatches) return { kind: 'matching', draft };
    // 基准已变化：保留冲突副本（先到先得，不覆盖未处理副本）
    const conflictKey = conflictDraftKey(projectId, chapterIndex);
    const existingCopy = readDraft(storage, conflictKey);
    if (!existingCopy) {
      try {
        storage.setItem(conflictKey, JSON.stringify(draft));
        return { kind: 'conflict', draft, conflictCopy: draft };
      } catch {
        return { kind: 'conflict', draft, conflictCopy: null };
      }
    }
    return { kind: 'conflict', draft, conflictCopy: existingCopy };
  } catch (e) {
    return { kind: 'unavailable', error: (e as Error).message || '本地存储不可用' };
  }
}

/** 读取未处理的冲突副本（编辑器冲突面板用）。 */
export function loadConflictDraft(storage: DraftStorage, projectId: string,
                                  chapterIndex: number): StoredDraft | null {
  try {
    return readDraft(storage, conflictDraftKey(projectId, chapterIndex));
  } catch {
    return null;
  }
}

/** 清除活跃草稿——仅在正式保存成功后调用。 */
export function clearDraft(storage: DraftStorage, projectId: string, chapterIndex: number): void {
  try {
    storage.removeItem(draftKey(projectId, chapterIndex));
  } catch {
    // 忽略清除失败
  }
}

/** 作者显式放弃冲突副本时调用；新草稿写入永不触碰冲突槽位。 */
export function clearConflictDraft(storage: DraftStorage, projectId: string, chapterIndex: number): void {
  try {
    storage.removeItem(conflictDraftKey(projectId, chapterIndex));
  } catch {
    // 忽略清除失败
  }
}

/** localStorage 适配器（浏览器环境）。 */
export const localStorageAdapter: DraftStorage = {
  getItem: (k) => window.localStorage.getItem(k),
  setItem: (k, v) => window.localStorage.setItem(k, v),
  removeItem: (k) => window.localStorage.removeItem(k),
};
