#!/usr/bin/env bash
# p34-05（UI 验收 A2 后半）：章节与视图通过 URL 恢复——hash 解析纯模块验证。
# 运行：bash scripts/verify-ui-chapter-route.sh
set -euo pipefail
cd "$(dirname "$0")/.."

node --experimental-strip-types --no-warnings - <<'EOF'
import assert from 'node:assert';
import { parseWorkspaceHash, buildWorkspaceHash as buildHash } from './frontend/src/lib/workspaceRoute.ts';

// 1. 项目主页：无章节
assert.deepEqual(parseWorkspaceHash('#/project/abc'), { projectId: 'abc', chapterIndex: null });

// 2. 带章节路由：恢复章节定位
assert.deepEqual(parseWorkspaceHash('#/project/abc/chapter/7'),
  { projectId: 'abc', chapterIndex: 7 });

// 3. 非法章号 → 忽略章节段
assert.deepEqual(parseWorkspaceHash('#/project/abc/chapter/xyz'),
  { projectId: 'abc', chapterIndex: null });

// 4. 非项目路由
assert.equal(parseWorkspaceHash('#/').projectId, null);
assert.equal(parseWorkspaceHash('').projectId, null);

// 5. buildWorkspaceHash 与解析互逆
const hash = buildHash('abc', 7);
assert.deepEqual(parseWorkspaceHash(hash), { projectId: 'abc', chapterIndex: 7 });

console.log('workspace route verified');
EOF
echo "PASS: workspace chapter route"
