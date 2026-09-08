#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

TMP_DIR=$(mktemp -d)
trap 'rm -rf "$TMP_DIR"' EXIT
mkdir -p "$TMP_DIR/bin"

cat > "$TMP_DIR/bin/mvn" <<'EOF'
#!/usr/bin/env bash
printf '%s\n' "$@" > "$E2E_MVN_ARGS_FILE"
exit 1
EOF
chmod +x "$TMP_DIR/bin/mvn"

run_and_capture() {
  local fixture_dir="$1"
  local capture_file="$2"

  set +e
  PATH="$TMP_DIR/bin:$PATH" E2E_MVN_ARGS_FILE="$capture_file" \
    E2E_FIXTURE_DIR="$fixture_dir" ./scripts/e2e.sh --mode replay --port 61999 >/dev/null 2>&1
  local status=$?
  set -e

  if [ "$status" -eq 0 ]; then
    echo "expected e2e.sh to fail after fake Maven exits" >&2
    exit 1
  fi
}

custom_dir="$TMP_DIR/custom-fixtures"
custom_capture="$TMP_DIR/custom-args"
run_and_capture "$custom_dir" "$custom_capture"
grep -F -- "--llm.replay.dir=$custom_dir" "$custom_capture" >/dev/null

default_capture="$TMP_DIR/default-args"
run_and_capture "data/e2e-fixtures/llm" "$default_capture"
grep -F -- "--llm.replay.dir=data/e2e-fixtures/llm" "$default_capture" >/dev/null

# p23-01: 未显式指定时，默认必须解析到已存在的版本化语料
unset E2E_FIXTURE_DIR
auto_capture="$TMP_DIR/auto-args"
set +e
PATH="$TMP_DIR/bin:$PATH" E2E_MVN_ARGS_FILE="$auto_capture" \
  ./scripts/e2e.sh --mode replay --port 61999 >/dev/null 2>&1
set -e
AUTO_DIR=$(grep -oE -- "--llm.replay.dir=[^ ]+" "$auto_capture" | head -1 | cut -d= -f2)
if [ -z "$AUTO_DIR" ] || [ ! -d "$AUTO_DIR" ]; then
  echo "default fixture resolution did not yield an existing versioned corpus (got: $AUTO_DIR)" >&2
  exit 1
fi
if ! ls "$AUTO_DIR"/*.json >/dev/null 2>&1; then
  echo "resolved default corpus $AUTO_DIR contains no fixture files" >&2
  exit 1
fi
echo "auto-resolved default corpus: $AUTO_DIR"

# p32-10: 自动选择必须与 preset 匹配（mtime 排序曾被文件复制扰动选中错误语料，
# 导致默认 mystery 流程命中 wuxia 语料、WORLDBUILDING 指纹 miss、构思 FAILED）
case "$AUTO_DIR" in
  *mystery*) ;;
  *) echo "default preset is mystery but auto-resolved corpus is $AUTO_DIR" >&2; exit 1 ;;
esac

wuxia_capture="$TMP_DIR/wuxia-args"
set +e
PATH="$TMP_DIR/bin:$PATH" E2E_MVN_ARGS_FILE="$wuxia_capture" \
  ./scripts/e2e.sh --mode replay --preset wuxia --port 61999 >/dev/null 2>&1
set -e
WUXIA_DIR=$(grep -oE -- "--llm.replay.dir=[^ ]+" "$wuxia_capture" | head -1 | cut -d= -f2)
case "$WUXIA_DIR" in
  *wuxia*) ;;
  *) echo "preset wuxia must resolve to a wuxia corpus (got: $WUXIA_DIR)" >&2; exit 1 ;;
esac

# 无匹配语料的 preset：必须明确报错指引而不是静默用错语料
missing_out="$TMP_DIR/missing-out"
set +e
PATH="$TMP_DIR/bin:$PATH" E2E_MVN_ARGS_FILE="$TMP_DIR/missing-args" \
  ./scripts/e2e.sh --mode replay --preset romance --port 61999 >"$missing_out" 2>&1
missing_status=$?
set -e
if [ "$missing_status" -eq 0 ]; then
  echo "preset without corpus must fail" >&2
  exit 1
fi
if ! grep -q "E2E_FIXTURE_DIR" "$missing_out"; then
  echo "missing-corpus failure must mention E2E_FIXTURE_DIR guidance" >&2
  exit 1
fi

echo "e2e fixture directory forwarding verified"
