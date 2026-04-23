#!/usr/bin/env bash
# Run all COUBES test scenarios sequentially, logging results to a timestamped file.
# Usage: ./run_all_tests.sh [--test-mode]
#   --test-mode: passed through to run_test.sh (uses built-in round-robin scheduler)

set -euo pipefail
cd "$(dirname "$0")"

TESTS=(
  Fragmentation_Test
  Fragmentation_Test_Large
  Fragmentation_Test_5Wave
  Performance_vs_Efficiency_Test
  Undercrowding_Test
  Scheduler_Latency_Test
  Scheduler_Scalability_Test
)

LOGFILE="test-results-$(date +%Y%m%d-%H%M%S).log"
PASS=0
FAIL=0
FAILED_TESTS=()

echo "Running ${#TESTS[@]} tests — logging to $LOGFILE"

for test in "${TESTS[@]}"; do
  echo ""
  echo "===== $test =====" | tee -a "$LOGFILE"
  if ./run_test.sh "$@" "org.example.testSuite.$test" 2>&1 | tee -a "$LOGFILE"; then
    echo "===== $test: PASS =====" | tee -a "$LOGFILE"
    ((PASS++))
  else
    echo "===== $test: FAIL =====" | tee -a "$LOGFILE"
    ((FAIL++))
    FAILED_TESTS+=("$test")
  fi
done

echo "" | tee -a "$LOGFILE"
echo "========== SUMMARY ==========" | tee -a "$LOGFILE"
echo "Passed: $PASS / ${#TESTS[@]}" | tee -a "$LOGFILE"
if [ "$FAIL" -gt 0 ]; then
  echo "Failed: ${FAILED_TESTS[*]}" | tee -a "$LOGFILE"
fi
echo "Log: $LOGFILE"
