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

echo "e2e fixture directory forwarding verified"
