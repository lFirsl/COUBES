#!/usr/bin/env bash
# run_all_tests.sh — Run all COUBES test suite tests and report results.
#
# Usage: run_all_tests.sh [OPTIONS]
#
# Options:
#   --test-mode    Use built-in scheduler (no Docker required)
#   --no-compile   Skip compilation (use existing binaries)
#   --stop-on-fail Stop after the first failing test
#   --help         Show this help message
#
# Passes all flags through to run_test.sh. Compilation is done once
# on the first test, then --no-compile is added for subsequent tests.

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
RUN_TEST="$SCRIPT_DIR/run_test.sh"

TESTS=(
    "org.example.testSuite.Fragmentation_Test"
    "org.example.testSuite.Fragmentation_Test_Large"
    "org.example.testSuite.Fragmentation_Test_5Wave"
    "org.example.testSuite.Performance_vs_Efficiency_Test"
    "org.example.testSuite.Undercrowding_Test"
    "org.example.testSuite.Scheduler_Scalability_Test"
    "org.example.testSuite.Scheduler_Latency_Test"
)

STOP_ON_FAIL=0
USER_FLAGS=()

show_help() {
    sed -n '2,/^$/{ s/^# \?//; p }' "$0"
    exit 0
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --stop-on-fail) STOP_ON_FAIL=1; shift ;;
        --help) show_help ;;
        *) USER_FLAGS+=("$1"); shift ;;
    esac
done

passed=0
failed=0
declare -a results=()

for i in "${!TESTS[@]}"; do
    test="${TESTS[$i]}"
    short="${test##*.}"

    # Compile on first test only; add --no-compile for the rest
    flags=("${USER_FLAGS[@]}")
    if [[ $i -gt 0 ]]; then
        # Add --no-compile if not already present
        if [[ ! " ${flags[*]} " =~ " --no-compile " ]]; then
            flags+=("--no-compile")
        fi
    fi

    echo ""
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    echo "  [$((i+1))/${#TESTS[@]}] $short"
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

    if bash "$RUN_TEST" "${flags[@]}" "$test" 2>&1 | tail -5; then
        results+=("✓ $short")
        ((passed++))
    else
        results+=("✗ $short")
        ((failed++))
        if [[ $STOP_ON_FAIL -eq 1 ]]; then
            echo ""
            echo "Stopping on first failure (--stop-on-fail)."
            break
        fi
    fi
done

echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "  RESULTS: $passed passed, $failed failed"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
for r in "${results[@]}"; do
    echo "  $r"
done
echo ""

[[ $failed -eq 0 ]] && exit 0 || exit 1
