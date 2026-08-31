#!/usr/bin/env bash
# p23-05: 一键业务发布门禁——聚合确定性验证与业务生命周期证据，产出单一 manifest。
# 用法: ./scripts/release-gate.sh [--skip soak|reliability|browser ...]
# 门禁：full tests / frontend build / replay baseline / reliability-e2e / soak / browser(存在时)。
# 产出: data/release/manifest.json（链接全部产物）；任一必选门禁失败 → 退出码非 0。
set -u
cd "$(dirname "$0")/.."

SKIP_SOAK=0; SKIP_REL=0; SKIP_BROWSER=0
while [ $# -gt 0 ]; do
  case "$1" in
    --skip) shift; for s in "$@"; do case "$s" in soak) SKIP_SOAK=1 ;; reliability) SKIP_REL=1 ;; browser) SKIP_BROWSER=1 ;; esac; done; break ;;
    *) echo "unknown arg: $1"; exit 1 ;;
  esac
done

RELEASE_DIR="data/release"
mkdir -p "$RELEASE_DIR"
declare -a GATE_NAMES=()
declare -a GATE_RESULTS=()
declare -a GATE_ARTIFACTS=()

record() { # $1=name $2=pass(0/1) $3=artifact
  GATE_NAMES+=("$1")
  GATE_RESULTS+=("$2")
  GATE_ARTIFACTS+=("$3")
  if [ "$2" = "0" ]; then echo "PASS  $1"; else echo "FAIL  $1"; fi
}
skip() { # $1=name $2=reason
  GATE_NAMES+=("$1")
  GATE_RESULTS+=("skip")
  GATE_ARTIFACTS+=("$2")
  echo "SKIP  $1 ($2)"
}

echo "=== Release Gate ==="

# ---- Gate 1: 全量测试 ----
if bash -c 'source scripts/load-env.sh && mvn test' > "$RELEASE_DIR/mvn-test.log" 2>&1; then
  record "mvn-test" 0 "$RELEASE_DIR/mvn-test.log"
else
  record "mvn-test" 1 "$RELEASE_DIR/mvn-test.log"
  tail -20 "$RELEASE_DIR/mvn-test.log"
fi

# ---- Gate 2: 前端构建 ----
if (cd frontend && npm run build) > "$RELEASE_DIR/frontend-build.log" 2>&1; then
  record "frontend-build" 0 "$RELEASE_DIR/frontend-build.log"
else
  record "frontend-build" 1 "$RELEASE_DIR/frontend-build.log"
fi

# ---- Gate 3: replay 基线（确定性，零成本）----
if ./scripts/e2e.sh > "$RELEASE_DIR/e2e-replay.log" 2>&1 \
   && python3 scripts/compare-baseline.py > "$RELEASE_DIR/compare-baseline.log" 2>&1; then
  cp e2e-report.json "$RELEASE_DIR/e2e-report.json" 2>/dev/null || true
  record "replay-baseline" 0 "$RELEASE_DIR/e2e-report.json"
else
  record "replay-baseline" 1 "$RELEASE_DIR/compare-baseline.log"
fi

# ---- Gate 4: 业务生命周期（reliability-e2e）----
if [ "$SKIP_REL" = "1" ]; then
  skip "reliability-e2e" "user-requested skip"
elif ./scripts/reliability-e2e.sh > "$RELEASE_DIR/reliability.log" 2>&1; then
  cp data/e2e/reliability/summary.json "$RELEASE_DIR/reliability-summary.json" 2>/dev/null || true
  record "reliability-e2e" 0 "$RELEASE_DIR/reliability-summary.json"
else
  record "reliability-e2e" 1 "$RELEASE_DIR/reliability.log"
fi

# ---- Gate 5: 400 章 soak ----
if [ "$SKIP_SOAK" = "1" ]; then
  skip "soak-400" "user-requested skip"
elif ./scripts/soak.sh --chapters 400 --seed 20260830 > "$RELEASE_DIR/soak.log" 2>&1; then
  cp data/e2e/soak/report.json "$RELEASE_DIR/soak-report.json" 2>/dev/null || true
  record "soak-400" 0 "$RELEASE_DIR/soak-report.json"
else
  record "soak-400" 1 "$RELEASE_DIR/soak.log"
fi

# ---- Gate 6: 浏览器套件（ego-browser 驱动）----
if [ "$SKIP_BROWSER" = "1" ]; then
  skip "browser-e2e" "user-requested skip"
elif [ -x scripts/browser-e2e.sh ]; then
  if ./scripts/browser-e2e.sh > "$RELEASE_DIR/browser-e2e.log" 2>&1; then
    cp data/e2e/browser/summary.json "$RELEASE_DIR/browser-summary.json" 2>/dev/null || true
    record "browser-e2e" 0 "$RELEASE_DIR/browser-summary.json"
  else
    record "browser-e2e" 1 "$RELEASE_DIR/browser-e2e.log"
  fi
else
  skip "browser-e2e" "browser-e2e.sh not present"
fi

# ---- manifest ----
python3 - "$RELEASE_DIR" "${GATE_NAMES[@]}" "${GATE_RESULTS[@]}" "${GATE_ARTIFACTS[@]}" <<PYEOF
import json, sys, datetime, os
release_dir = sys.argv[1]
rest = sys.argv[2:]
third = len(rest) // 3
names, results, arts = rest[:third], rest[third:2*third], rest[2*third:]
gates = [{"name": n, "result": ("pass" if r == "0" else ("skip" if r == "skip" else "fail")), "artifact": a}
         for n, r, a in zip(names, results, arts)]
mandatory_fail = any(g["result"] == "fail" for g in gates)
manifest = {
    "generatedAt": datetime.datetime.now().isoformat(),
    "kind": "release-gate-manifest",
    "verdict": "PASS" if not mandatory_fail else "FAIL",
    "gates": gates,
}
with open(os.path.join(release_dir, "manifest.json"), "w", encoding="utf-8") as f:
    json.dump(manifest, f, ensure_ascii=False, indent=2)
print(json.dumps(manifest, ensure_ascii=False, indent=2))
sys.exit(0 if not mandatory_fail else 1)
PYEOF
MANIFEST_EXIT=$?

echo ""
if [ $MANIFEST_EXIT -eq 0 ]; then
  echo "RELEASE GATE PASS — manifest: $RELEASE_DIR/manifest.json"
else
  echo "RELEASE GATE FAIL — manifest: $RELEASE_DIR/manifest.json"
fi
exit $MANIFEST_EXIT
