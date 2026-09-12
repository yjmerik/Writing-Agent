#!/usr/bin/env bash
# p35-03：篇幅调整影响预览 UI——渲染内容静态校验。
set -euo pipefail
cd "$(dirname "$0")/.."
required=(
  "getPlanTargetPreview"
  "applyPlanTarget"
  "目标章数"
  "影响预览"
  "失去承载位置"
  "确认生效"
)
for fragment in "${required[@]}"; do
  if ! grep -qF "$fragment" frontend/src/components/OutlineView.tsx frontend/src/api/client.ts; then
    echo "FAIL: 篇幅调整 UI 缺少: $fragment" >&2
    exit 1
  fi
done
echo "PASS: target-chapters UI wired"
