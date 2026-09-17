#!/usr/bin/env bash
# p40-05: 全链路独立验收——不只统计状态，逐项验证功能语义。
# 用法: bash scripts/verify-collaboration-acceptance.sh [--with-browser] [--base-url URL]
# 需要: mvn, python3; --with-browser 需要 qa/collaboration 依赖与运行中的服务。
set -uo pipefail
cd "$(dirname "$0")/.."
source scripts/load-env.sh 2>/dev/null || true

WITH_BROWSER=0
BASE_URL="${BASE_URL:-}"
while [[ $# -gt 0 ]]; do
  case "$1" in
    --with-browser) WITH_BROWSER=1; shift ;;
    --base-url) BASE_URL="$2"; shift 2 ;;
    *) shift ;;
  esac
done

PASS=0; FAIL=0
record() { # name, exitcode
  if [ "$2" -eq 0 ]; then PASS=$((PASS+1)); echo "  ✅ $1"; else FAIL=$((FAIL+1)); echo "  ❌ $1"; fi
}
# 确保退出码不被后续命令覆盖
run_check() { # name, command...
  local name="$1"; shift
  "$@" > "/tmp/acceptance-check.log" 2>&1
  local code=$?
  record "$name" "$code"
}

echo "=== 1. 编译与全量测试 ==="
mvn -q compile > /tmp/acceptance-compile.log 2>&1
record "mvn compile" $?
mvn test > /tmp/acceptance-test.log 2>&1
TEST_EXIT=$?
if [ $TEST_EXIT -ne 0 ]; then
  record "mvn test (exit=$TEST_EXIT)" $TEST_EXIT
else
  # 额外验证总结行
  if grep -q "Failures: [1-9]" /tmp/acceptance-test.log || grep -q "Errors: [1-9]" /tmp/acceptance-test.log; then
    record "mvn test (failures/errors found)" 1
  else
    record "mvn test (0 failures/errors)" 0
  fi
fi

echo "=== 2. 语义专项测试（关键修复验证） ==="
# 审计修复验证测试——验证 P1/P2 修复的实际行为
AUDIT_TESTS="Audit2FixTest,AuditP14EndpointsTest,DispositionApprovalGateApiTest,CharacterImpactPreviewApiTest,PlanPreviewCommitServiceTest,TakeoverLateCommitTest"
mvn test -Dtest="$AUDIT_TESTS" > /tmp/acceptance-audit.log 2>&1
AUDIT_EXIT=$?
if [ $AUDIT_EXIT -eq 0 ] && ! grep -q "Failures: [1-9]" /tmp/acceptance-audit.log; then
  record "audit fix verification tests" 0
else
  record "audit fix verification tests" 1
fi

# 并发安全测试
CONCURRENCY_TESTS="VersionedApprovalRepositoryTest,ResolveReviewConcurrencyTest,ProposalRepositoryTest"
mvn test -Dtest="$CONCURRENCY_TESTS" > /tmp/acceptance-conc.log 2>&1
CONC_EXIT=$?
if [ $CONC_EXIT -eq 0 ] && ! grep -q "Failures: [1-9]" /tmp/acceptance-conc.log; then
  record "concurrency safety tests" 0
else
  record "concurrency safety tests" 1
fi

echo "=== 3. 评审协议脚本 ==="
python3 scripts/test_evaluate_collaboration_study.py > /tmp/acceptance-eval.log 2>&1
record "evaluate script honest (5 scenarios)" $?

echo "=== 4. Harness 一致性 ==="
python3 scripts/check-harness.py --check-git > /tmp/acceptance-harness.log 2>&1
record "check-harness --check-git" $?

echo "=== 5. 浏览器回归（可选 --with-browser） ==="
if [ "$WITH_BROWSER" -eq 1 ]; then
  if [ -z "$BASE_URL" ]; then
    echo "  ⚠️ --with-browser 需要 --base-url（运行中的服务）"; FAIL=$((FAIL+1))
  else
    QA_BASE_URL="$BASE_URL" QA_ARTIFACT_DIR=/tmp/writingagent-acceptance-qa \
      npm --prefix qa/collaboration test > /tmp/acceptance-browser.log 2>&1
    record "playwright full suite" $?
  fi
else
  echo "  ⏭ 跳过（未指定 --with-browser）"
fi

echo "=== 6. 真实实验状态（p40-06） ==="
STUDY=$(python3 scripts/evaluate-collaboration-study.py \
  --manifest docs/experiments/collaboration-study-manifest.json \
  --ratings docs/experiments/collaboration-study-ratings.csv 2>/dev/null | python3 -c "import json,sys; print(json.load(sys.stdin)['status'])" 2>/dev/null || echo NOT_RUN)
echo "  STUDY-01: $STUDY"
if [ "$STUDY" != "COMPLETE" ]; then
  FAIL=$((FAIL+1)); echo "  ❌ 真实盲评未完成（需人类参与者，Agent 不可代填）"
else
  PASS=$((PASS+1)); echo "  ✅ 真实盲评数据完整"
fi

echo ""
echo "=== 验收结果: PASS=$PASS FAIL=$FAIL ==="
if [ "$FAIL" -gt 0 ]; then
  echo "=== Acceptance: INCOMPLETE ==="
  exit 1
fi
echo "=== Acceptance: COMPLETE ==="
