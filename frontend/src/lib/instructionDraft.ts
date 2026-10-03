/**
 * NCS-176: 创作要求的本地暂存——「给 Agent 的创作要求」输入后即持久化，
 * 页面刷新/重开不丢失；随「开始/继续协作」成功提交或作者清空后清除。
 * 纯函数模块（storage 注入，便于 Node 直接验证）。
 */

export interface KvStorage {
  getItem(key: string): string | null;
  setItem(key: string, value: string): void;
  removeItem(key: string): void;
}

export function instructionDraftKey(projectId: string): string {
  return `novel-instruction:${projectId}`;
}

/** 读取暂存的创作要求；无内容或解析失败返回 null。 */
export function loadInstructionDraft(storage: KvStorage, projectId: string): string | null {
  try {
    const raw = storage.getItem(instructionDraftKey(projectId));
    if (!raw) return null;
    const parsed = JSON.parse(raw) as { text?: unknown; savedAt?: unknown };
    return typeof parsed.text === 'string' && parsed.text.trim() !== '' ? parsed.text : null;
  } catch {
    return null;
  }
}

/** 暂存创作要求；返回是否成功持久化（false=未暂存，UI 需提示）。 */
export function saveInstructionDraft(storage: KvStorage, projectId: string, text: string): boolean {
  try {
    if (text.trim() === '') {
      storage.removeItem(instructionDraftKey(projectId));
      return true;
    }
    storage.setItem(instructionDraftKey(projectId),
      JSON.stringify({ text, savedAt: new Date().toISOString() }));
    return true;
  } catch {
    return false;
  }
}

/** 清除暂存——随「开始/继续协作」成功提交后调用。 */
export function clearInstructionDraft(storage: KvStorage, projectId: string): void {
  try {
    storage.removeItem(instructionDraftKey(projectId));
  } catch {
    // 忽略清除失败
  }
}

/** localStorage 适配器（浏览器环境）。 */
export const localStorageAdapter: KvStorage = {
  getItem: (k) => window.localStorage.getItem(k),
  setItem: (k, v) => window.localStorage.setItem(k, v),
  removeItem: (k) => window.localStorage.removeItem(k),
};
