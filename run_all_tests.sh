#!/usr/bin/env bash
# run_all_tests.sh — Run all COUBES test suite tests and report results.
#
# Usage: run_all_tests.sh [OPTIONS]
#
# Options:
#   --test-mode    Use built-in scheduler (no Docker required)
#   --volcano      Use Volcano scheduler
#   --no-compile   Skip compilation (use existing binaries)
#   --stop-on-fail Stop after the first failing test
#   --timeout=N    Per-test timeout in seconds (default: 45)
#   --help         Show this help message
#
# Passes all flags through to run_test.sh. Compilation is done once
# on the first test, then --no-compile is added for subsequent tests.

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
RUN_TEST="$SCRIPT_DIR/run_test.sh"

TESTS=(
    "org.example.testSuite.Single_Pod_Test"
    "org.example.testSuite.Fragmentation_Test"
    "org.example.testSuite.Fragmentation_Test_Large"
    "org.example.testSuite.Fragmentation_Test_5Wave"
    "org.example.testSuite.Performance_vs_Efficiency_Test"
    "org.example.testSuite.Undercrowding_Test"
    "org.example.testSuite.Scheduler_Scalability_Test"
    "org.example.testSuite.Scheduler_Latency_Test"
    "org.example.testSuite.MultiPE_Pod_Test"
    "org.example.testSuite.Heterogeneous_Node_Test"
    "org.example.testSuite.Queue_Priority_Test"
)

STOP_ON_FAIL=0
PER_TEST_TIMEOUT=45
USER_FLAGS=()

show_help() {
    sed -n '2,/^$/{ s/^# \?//; p }' "$0"
    exit 0
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --stop-on-fail) STOP_ON_FAIL=1; shift ;;
        --timeout=*) PER_TEST_TIMEOUT="${1#--timeout=}"; shift ;;
        --help) show_help ;;
        *) USER_FLAGS+=("$1"); shift ;;
    esac
done

passed=0
failed=0
declare -a results=()
total_start=$SECONDS

for i in "${!TESTS[@]}"; do
    test="${TESTS[$i]}"
    short="${test##*.}"

    # Compile on first test only; add --no-compile for the rest
    flags=("${USER_FLAGS[@]}")
    if [[ $i -gt 0 ]]; then
        if [[ ! " ${flags[*]} " =~ " --no-compile " ]]; then
            flags+=("--no-compile")
        fi
    fi

    printf "\n[%d/%d] %-35s " "$((i+1))" "${#TESTS[@]}" "$short"

    test_start=$SECONDS
    output=$(timeout "$PER_TEST_TIMEOUT" bash "$RUN_TEST" "${flags[@]}" "$test" 2>&1)
    rc=$?
    elapsed=$((SECONDS - test_start))

    if [[ $rc -eq 0 ]]; then
        printf "✓ %2ds\n" "$elapsed"
        results+=("✓ $(printf '%-35s %2ds' "$short" "$elapsed")")
        ((passed++))
    elif [[ $rc -eq 124 ]]; then
        printf "✗ TIMEOUT (%ds)\n" "$PER_TEST_TIMEOUT"
        results+=("✗ $(printf '%-35s TIMEOUT' "$short")")
        ((failed++))
    else
        printf "✗ FAILED %2ds\n" "$elapsed"
        # Show last few lines of output for diagnosis
        echo "$output" | grep -E "ERROR|FAIL|Exception|HANG" | tail -3 | sed 's/^/    /'
        results+=("✗ $(printf '%-35s FAILED' "$short")")
        ((failed++))
    fi

    if [[ $failed -gt 0 && $STOP_ON_FAIL -eq 1 ]]; then
        echo ""
        echo "Stopping on first failure (--stop-on-fail)."
        break
    fi
done

total_elapsed=$((SECONDS - total_start))

echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
printf "  RESULTS: %d passed, %d failed (%ds total)\n" "$passed" "$failed" "$total_elapsed"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
for r in "${results[@]}"; do
    echo "  $r"
done
echo ""

[[ $failed -eq 0 ]] && exit 0 || exit 1
