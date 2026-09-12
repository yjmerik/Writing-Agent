#!/usr/bin/env bash
# p35-02：候选线索管理界面——渲染内容静态校验。
set -euo pipefail
cd "$(dirname "$0")/.."
required=(
  "getForeshadowCandidates"
  "promoteForeshadowCandidate"
  "rejectForeshadowCandidate"
  "候选线索"
  "提升为伏笔"
  "拒绝"
)
for fragment in "${required[@]}"; do
  if ! grep -qF "$fragment" frontend/src/components/InteractiveReview.tsx frontend/src/api/client.ts; then
    echo "FAIL: 候选面板缺少: $fragment" >&2
    exit 1
  fi
done
echo "PASS: candidates panel wired"
