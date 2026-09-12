#!/usr/bin/env bash
# p35-01：意见面板接入三栏审查页——渲染内容静态校验。
set -euo pipefail
cd "$(dirname "$0")/.."

required=(
  "directives"
  "createDirective"
  "confirmDirective"
  "dismissDirective"
  "登记意见"
  "确认已落实"
  "撤销"
  "APPLIED_SYSTEM"
)
for fragment in "${required[@]}"; do
  if ! grep -qF "$fragment" frontend/src/components/InteractiveReview.tsx frontend/src/api/client.ts; then
    echo "FAIL: 意见面板缺少: $fragment" >&2
    exit 1
  fi
done
echo "PASS: directives panel wired"
