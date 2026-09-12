#!/usr/bin/env bash
# p34-06（UI 验收 A6）：窄屏审查抽屉保留问题明细与关键操作——渲染内容静态校验。
# 运行：bash scripts/verify-ui-mobile-review.sh
set -euo pipefail
cd "$(dirname "$0")/.."

# 抽屉 JSX 必须包含：完整问题列表渲染、放行动作、指向完整面板的入口说明
required=(
  "qualityReport.issues.length > 0"
  "item.severity"
  "resolveManualReview"
  "已处理"
  "已在桌面版查看完整问题明细与裁决"
)
for fragment in "${required[@]}"; do
  if ! grep -qF "$fragment" frontend/src/components/InteractiveReview.tsx; then
    echo "FAIL: 抽屉缺少关键内容: $fragment" >&2
    exit 1
  fi
done
echo "PASS: mobile review drawer keeps issue detail and actions"
