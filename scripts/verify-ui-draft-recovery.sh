#!/usr/bin/env bash
# p34-03（UI 验收 A2）：正文未保存草稿刷新可恢复——draftStore 纯模块行为验证。
# 运行：bash scripts/verify-ui-draft-recovery.sh
set -euo pipefail
cd "$(dirname "$0")/.."

node --experimental-strip-types --no-warnings - <<'EOF'
import assert from 'node:assert';
import { saveDraft, loadDraft, clearDraft, draftKey } from './frontend/src/lib/draftStore.ts';

const mem = new Map();
const storage = {
  getItem: (k) => (mem.has(k) ? mem.get(k) : null),
  setItem: (k, v) => mem.set(k, v),
  removeItem: (k) => mem.delete(k),
};

// 1. 保存后可读回（模拟刷新：同一个 storage 新调用 loadDraft）
saveDraft(storage, 'p1', 7, 3, '第七章', '作者未保存的草稿内容');
const restored = loadDraft(storage, 'p1', 7, 3);
assert.ok(restored, '刷新后应能恢复草稿');
assert.equal(restored.content, '作者未保存的草稿内容');
assert.equal(restored.baseVersion, 3);
assert.equal(restored.title, '第七章');

// 2. 基准版本不符（作者或系统已出新稿）→ 草稿过期不可恢复
assert.equal(loadDraft(storage, 'p1', 7, 4), null, '基准版本不符的草稿视为过期');

// 3. 保存成功 → 清除草稿
clearDraft(storage, 'p1', 7, 3);
assert.equal(loadDraft(storage, 'p1', 7, 3), null, '清除后不可恢复');

// 4. 键按项目+章节隔离
saveDraft(storage, 'p1', 2, 1, '第二章草稿', 'x');
assert.ok(loadDraft(storage, 'p2', 2, 1) === null, '不同项目不串草稿');
assert.ok(draftKey('p1', 2) !== draftKey('p2', 2));

console.log('draft recovery store verified');
EOF
echo "PASS: draft recovery store"
