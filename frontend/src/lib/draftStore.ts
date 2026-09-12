/**
 * p34-03（UI 验收 A2）：正文未保存草稿的本地暂存与恢复。
 * 纯函数模块（storage 注入，便于 Node 直接验证）；UI 层传 localStorage。
 *
 * 键：novel-draft:{projectId}:{chapterIndex}
 * 值：{ baseVersion, title, content, savedAt }
 * 恢复规则：草稿 baseVersion 必须等于当前稿版本——不符即过期（作者或系统已出新稿），
 * 恢复会覆盖新内容，因此过期草稿直接不可见（避免旧草稿覆盖新稿）。
 */

export interface ChapterDraft {
  baseVersion: number;
  title: string;
  content: string;
  savedAt: string;
}

export interface DraftStorage {
  getItem(key: string): string | null;
  setItem(key: string, value: string): void;
  removeItem(key: string): void;
}

export function draftKey(projectId: string, chapterIndex: number): string {
  return `novel-draft:${projectId}:${chapterIndex}`;
}

export function saveDraft(storage: DraftStorage, projectId: string, chapterIndex: number,
                          baseVersion: number, title: string, content: string): void {
  const draft: ChapterDraft = {
    baseVersion, title, content, savedAt: new Date().toISOString(),
  };
  try {
    storage.setItem(draftKey(projectId, chapterIndex), JSON.stringify(draft));
  } catch {
    // 存储失败（隐私模式/配额）不阻塞编辑
  }
}

/** 恢复草稿；不存在或基准版本与当前稿不符（过期）时返回 null。 */
export function loadDraft(storage: DraftStorage, projectId: string,
                          chapterIndex: number, currentVersion: number): ChapterDraft | null {
  try {
    const raw = storage.getItem(draftKey(projectId, chapterIndex));
    if (!raw) return null;
    const draft = JSON.parse(raw) as ChapterDraft;
    if (typeof draft.baseVersion !== 'number' || draft.baseVersion !== currentVersion) return null;
    return draft;
  } catch {
    return null;
  }
}

export function clearDraft(storage: DraftStorage, projectId: string, chapterIndex: number): void {
  try {
    storage.removeItem(draftKey(projectId, chapterIndex));
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
