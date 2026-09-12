/**
 * p34-05（UI 验收 A2 后半）：工作区 hash 路由解析——刷新/深链接恢复章节与视图定位。
 * 路由形态：
 *   #/project/{id}                 → 项目主页（无章节定位）
 *   #/project/{id}/chapter/{n}     → 主页 + 审查页签 + 章节定位
 */

export interface WorkspaceLocation {
  projectId: string | null;
  chapterIndex: number | null;
}

export function parseWorkspaceHash(hash: string): WorkspaceLocation {
  const clean = (hash ?? '').replace(/^#/, '');
  const parts = clean.split('/').filter(Boolean);
  // ['project', id] 或 ['project', id, 'chapter', n]
  if (parts[0] !== 'project' || !parts[1]) return { projectId: null, chapterIndex: null };
  const projectId = parts[1];
  let chapterIndex: number | null = null;
  if (parts[2] === 'chapter' && parts[3] !== undefined) {
    const n = Number(parts[3]);
    if (Number.isInteger(n) && n > 0) chapterIndex = n;
  }
  return { projectId, chapterIndex };
}

export function buildWorkspaceHash(projectId: string, chapterIndex: number | null): string {
  return chapterIndex == null
    ? `#/project/${projectId}`
    : `#/project/${projectId}/chapter/${chapterIndex}`;
}
