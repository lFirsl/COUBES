#!/usr/bin/env bash
# run_all_tests.sh — Run all COUBES test suite tests with shared infrastructure.
#
# Usage: run_all_tests.sh [OPTIONS]
#
# Options:
#   --test-mode    Use built-in scheduler (no Docker required)
#   --volcano      Use Volcano scheduler
#   --no-compile   Skip compilation (use existing binaries)
#   --stop-on-fail Stop after the first failing test
#   --timeout=N    Per-test timeout in seconds (default: 45, auto-raised to 90 for Volcano)
#   --help         Show this help message
#
# Infrastructure (adapter + scheduler) is started once and reused across tests.
# If a test fails, infrastructure is restarted before the next test.

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
RUN_TEST="$SCRIPT_DIR/run_test.sh"
ADAPTER_BIN="$SCRIPT_DIR/k8s-cloudsim-adapter/adapter-linux"
ADAPTER_URL="http://localhost:8080"

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
TIMEOUT_SET=0
TEST_MODE=0
NO_COMPILE=0
SCHEDULER_DIR="second-scheduler"
SCHEDULER_CONTAINER="my-scheduler"
ADAPTER_FLAGS="--scheduler=default-scheduler"
USER_FLAGS=()

show_help() {
    sed -n '2,/^$/{ s/^# \?//; p }' "$0"
    exit 0
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --stop-on-fail) STOP_ON_FAIL=1; shift ;;
        --timeout=*) PER_TEST_TIMEOUT="${1#--timeout=}"; TIMEOUT_SET=1; shift ;;
        --test-mode) TEST_MODE=1; ADAPTER_FLAGS="--test-mode"; USER_FLAGS+=("$1"); shift ;;
        --volcano)
            SCHEDULER_DIR="volcano-scheduler"
            SCHEDULER_CONTAINER="volcano-scheduler"
            ADAPTER_FLAGS="--scheduler=volcano"
            if [[ $TIMEOUT_SET -eq 0 ]]; then PER_TEST_TIMEOUT=90; fi
            USER_FLAGS+=("$1"); shift ;;
        --no-compile) NO_COMPILE=1; USER_FLAGS+=("$1"); shift ;;
        --help) show_help ;;
        *) USER_FLAGS+=("$1"); shift ;;
    esac
done

# ── Infrastructure management ────────────────────────────────────────────────

adapter_healthy() { curl -s -X DELETE "$ADAPTER_URL/reset" 2>/dev/null | grep -q "Reset complete"; }
scheduler_running() { docker ps --filter "name=$SCHEDULER_CONTAINER" --filter "status=running" -q 2>/dev/null | grep -q .; }
scheduler_ready() { docker logs "$SCHEDULER_CONTAINER" 2>&1 | grep -q "Caches populated"; }

start_infra() {
    # Kill any existing adapter
    pkill -9 -x adapter-linux 2>/dev/null || true
    sleep 1

    echo "→ Starting adapter..."
    mkdir -p "$SCRIPT_DIR/debug"
    setsid "$ADAPTER_BIN" $ADAPTER_FLAGS </dev/null >"$SCRIPT_DIR/debug/adapter_all_tests.log" 2>&1 &
    local deadline=$((SECONDS + 10))
    while [[ $SECONDS -lt $deadline ]]; do
        sleep 1
        adapter_healthy && break
    done
    adapter_healthy || { echo "ERROR: Adapter failed to start"; return 1; }
    echo "  Adapter up."

    if [[ $TEST_MODE -eq 0 ]]; then
        echo "→ Starting scheduler..."
        (cd "$SCRIPT_DIR/$SCHEDULER_DIR" && docker compose down 2>/dev/null; docker compose up -d 2>&1) | tail -2
        local sched_deadline=$((SECONDS + 35))
        while [[ $SECONDS -lt $sched_deadline ]]; do
            sleep 2
            scheduler_ready && break
        done
        scheduler_ready || { echo "ERROR: Scheduler failed to start"; return 1; }
        echo "  Scheduler ready."
    fi
}

restart_infra() {
    echo "→ Restarting infrastructure after failure..."
    start_infra
}

stop_infra() {
    pkill -9 -x adapter-linux 2>/dev/null || true
    wait 2>/dev/null || true
    if [[ $TEST_MODE -eq 0 ]]; then
        (cd "$SCRIPT_DIR/$SCHEDULER_DIR" && docker compose down 2>/dev/null) || true
    fi
}

infra_ok() {
    adapter_healthy || return 1
    if [[ $TEST_MODE -eq 0 ]]; then
        scheduler_running || return 1
    fi
    return 0
}

# ── Build ────────────────────────────────────────────────────────────────────

if [[ $NO_COMPILE -eq 0 ]]; then
    echo "→ Building..."
    (cd "$SCRIPT_DIR/k8s-cloudsim-adapter" && go build -o adapter-linux .) || { echo "ERROR: Go build failed"; exit 1; }
    (cd "$SCRIPT_DIR/k8s-cloudsim-adapter" && go test ./... -count=1) || { echo "ERROR: Go tests failed"; exit 1; }
    (cd "$SCRIPT_DIR" && mvn -q compile) || { echo "ERROR: Java compile failed"; exit 1; }
    echo "  Build complete."
fi

# ── Run tests ────────────────────────────────────────────────────────────────

trap stop_infra EXIT
start_infra || exit 1

passed=0
failed=0
declare -a results=()
total_start=$SECONDS

for i in "${!TESTS[@]}"; do
    test="${TESTS[$i]}"
    short="${test##*.}"

    printf "[%d/%d] %-35s " "$((i+1))" "${#TESTS[@]}" "$short"

    # Check infra health before each test; restart if broken
    if ! infra_ok; then
        restart_infra || { echo "ERROR: Cannot recover infrastructure"; exit 1; }
    fi

    test_start=$SECONDS
    output=$(timeout "$PER_TEST_TIMEOUT" bash "$RUN_TEST" --keep-infra --no-compile "${USER_FLAGS[@]}" "$test" 2>&1)
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
        restart_infra || true
    else
        printf "✗ FAILED %2ds\n" "$elapsed"
        echo "$output" | grep -E "ERROR|FAIL|Exception|HANG" | tail -3 | sed 's/^/    /'
        results+=("✗ $(printf '%-35s FAILED' "$short")")
        ((failed++))
        restart_infra || true
    fi

    if [[ $failed -gt 0 && $STOP_ON_FAIL -eq 1 ]]; then
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
