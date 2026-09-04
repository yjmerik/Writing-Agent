#!/usr/bin/env bash
set -u

failed=0

for forbidden in '人種' '人收' '章节阅读' '全自动生成，无需干预'; do
  while IFS=: read -r file line source; do
    if printf '%s\n' "$source" | grep -Eq '^[[:space:]]*(//|/\*|\*)'; then
      continue
    fi
    printf '%s:%s:%s\n' "$file" "$line" "$source"
    failed=1
  done < <(rg -n --no-heading --fixed-strings "$forbidden" frontend/src || true)
done

if (( failed )); then
  printf '用户文案检查失败：发现禁用术语。\n' >&2
  exit 1
fi

printf '用户文案检查通过。\n'
