#!/usr/bin/env bash
# run_test.sh — Build, test, and run a COUBES simulation.
# Auto-recovers once from a scheduler hang. Always exits non-zero on failure.
#
# Usage: run_test.sh [OPTIONS] <fully.qualified.TestClass>
#
# Options:
#   --test-mode        Use built-in round-robin scheduler (no Docker/kube-scheduler required)
#   --no-compile       Skip Go build, Go tests, and Java compilation (use existing binaries)
#   --no-filter        Show full simulation output instead of filtered summary
#   --scheduler=NAME   Scheduler profile to use (default: default-scheduler, e.g. my-scheduler)
#   --volcano          Use the Volcano scheduler (sets --scheduler=volcano and uses volcano-scheduler/)
#   --keep-infra       Don't start/stop adapter or scheduler (used by run_all_tests.sh)
#   --help             Show this help message
#
# Examples:
#   bash run_test.sh --test-mode org.example.testSuite.Fragmentation_Test_Large
#   bash run_test.sh --no-compile org.example.testSuite.Scheduler_Scalability_Test
#   bash run_test.sh --test-mode --no-compile --no-filter org.example.testSuite.Fragmentation_Test
#   bash run_test.sh --volcano org.example.testSuite.Fragmentation_Test

set -euo pipefail

ADAPTER_BIN="k8s-cloudsim-adapter/adapter-linux"
# Log paths are set after parsing args (see below) to include test name + timestamp.
# These are defaults that get overwritten.
ADAPTER_LOG="debug/adapter.log"
SIM_LOG="debug/sim.log"
SCHEDULER_LOG="debug/scheduler.log"
mkdir -p debug
SCHEDULER_DIR="second-scheduler"
SCHEDULER_CONTAINER="my-scheduler"
ADAPTER_URL="http://localhost:8080"
HANG_TIMEOUT=45   # seconds of no log output before declaring a hang
                  # Overridden to 60s for Volcano (must exceed 30s scheduling round timeout)
RECOVERY_DONE=0
TEST_MODE=0
NO_COMPILE=0
NO_FILTER=0
KEEP_INFRA=0
ADAPTER_FLAGS="--scheduler=default-scheduler"

# Output filter: shows only the important lines from simulation output
OUTPUT_FILTER='(SUCCESS|PASS:|FAIL|ERROR|WARNING|Simulation Metrics|Simulated Time|Wall-clock|Energy|Number of|consolidation|Throughput|Scheduling Latency|Scheduling rounds|SCALABILITY|Phase|Latency ratio|finished!|Exception|round=|Rescheduling|scheduled on|pending|OUTPUT|Cloudlet ID|── Round)'

die() { echo "ERROR: $*" >&2; exit 1; }

show_help() {
    sed -n '2,/^$/{ s/^# \?//; p }' "$0"
    exit 0
}

# ── prerequisite checks ─────────────────────────────────────────────────────

check_prereqs() {
    local missing=0
    for cmd in mvn curl; do
        if ! "$cmd" --version >/dev/null 2>&1; then
            echo "MISSING: $cmd is not available" >&2
            missing=1
        fi
    done
    if ! go version >/dev/null 2>&1; then
        echo "MISSING: go is not available" >&2
        missing=1
    fi
    if [[ $TEST_MODE -eq 0 ]]; then
        if ! docker --version >/dev/null 2>&1; then
            echo "MISSING: docker is not available (required in full mode; use --test-mode to skip)" >&2
            missing=1
        fi
    fi
    if [[ $missing -eq 1 ]]; then die "Install missing prerequisites before running."; fi
}

# ── helpers ──────────────────────────────────────────────────────────────────

adapter_running() { pgrep -f "adapter-linux" >/dev/null 2>&1; }

adapter_healthy() { curl -s -X DELETE "$ADAPTER_URL/reset" 2>/dev/null | grep -q "Reset complete"; }

scheduler_running() { docker ps --filter "name=$SCHEDULER_CONTAINER" --filter "status=running" -q 2>/dev/null | grep -q .; }

scheduler_ready() {
    docker logs "$SCHEDULER_CONTAINER" 2>&1 | grep -q "Caches populated"
}

wait_for_scheduler() {
    local deadline=$((SECONDS + 35))
    while [[ $SECONDS -lt $deadline ]]; do
        sleep 2
        scheduler_ready && echo "  Scheduler ready." && return 0
    done
    return 1
}

kill_stale_processes() {
    echo "→ Killing stale processes..."
    pkill -9 -f "exec:java.*org.example" 2>/dev/null || true
    # Only kill adapter if it's unhealthy (preserves scheduler watch connections)
    if adapter_running && ! adapter_healthy; then
        pkill -9 -x adapter-linux 2>/dev/null || true
        sleep 1
    fi
}

start_adapter() {
    if adapter_running && adapter_healthy; then
        echo "  Adapter already running."
        return 0
    fi
    # Kill any unhealthy adapter
    pkill -9 -x adapter-linux 2>/dev/null || true
    sleep 1
    echo "→ Starting adapter..."
    setsid "$ADAPTER_BIN" $ADAPTER_FLAGS </dev/null >"$ADAPTER_LOG" 2>&1 &
    local deadline=$((SECONDS + 10))
    while [[ $SECONDS -lt $deadline ]]; do
        sleep 1
        adapter_healthy && echo "  Adapter up." && return 0
    done
    die "Adapter failed to start. Log: $(tail -5 $ADAPTER_LOG)"
}

start_scheduler() {
    echo "→ Starting scheduler..."
    (cd "$SCHEDULER_DIR" && docker compose down 2>/dev/null; docker compose up -d 2>&1) | tail -2
    wait_for_scheduler || die "Scheduler failed to become ready. Logs: $(docker logs "$SCHEDULER_CONTAINER" --since 40s 2>&1 | tail -5)"
}

restart_scheduler_and_reset() {
    echo "→ Restarting scheduler and resetting adapter..."
    # Kill adapter so scheduler gets fresh watch connections
    pkill -9 -x adapter-linux 2>/dev/null || true
    sleep 1
    setsid "$ADAPTER_BIN" $ADAPTER_FLAGS </dev/null >"$ADAPTER_LOG" 2>&1 &
    sleep 2
    (cd "$SCHEDULER_DIR" && docker compose down && docker compose up -d 2>&1) | tail -2
    wait_for_scheduler || die "Scheduler not ready after restart. Logs: $(docker logs "$SCHEDULER_CONTAINER" --since 40s 2>&1 | tail -5)"
    adapter_healthy || die "Adapter not responding after scheduler restart."
    echo "  Recovery complete."
}

# ── build ─────────────────────────────────────────────────────────────────────

build_all() {
    if [[ $NO_COMPILE -eq 1 ]]; then
        echo "→ Skipping compilation (--no-compile)."
        return 0
    fi

    echo "→ Building Go adapter..."
    (cd k8s-cloudsim-adapter && go build -o adapter-linux .) || die "Go adapter build failed."

    echo "→ Running Go tests..."
    (cd k8s-cloudsim-adapter && go test ./scheduler/ -count=1) || die "Go tests failed."

    echo "→ Compiling Java..."
    mvn -q compile || die "Java compilation failed."

    echo "  Build complete."
}

# ── ensure infrastructure ─────────────────────────────────────────────────────

ensure_infra() {
    if [[ $KEEP_INFRA -eq 1 ]]; then
        # Assume infra is already running (managed by caller, e.g. run_all_tests.sh)
        adapter_healthy || die "Adapter not healthy (--keep-infra but adapter not responding)."
        echo "→ Adapter reset: $(curl -s -X DELETE $ADAPTER_URL/reset)"
        return 0
    fi

    start_adapter

    # Scheduler (skip in test mode)
    if [[ $TEST_MODE -eq 0 ]]; then
        if scheduler_running && scheduler_ready && adapter_healthy; then
            echo "  Scheduler already running and ready."
        else
            start_scheduler
        fi
    else
        echo "→ Test mode: skipping scheduler."
    fi

    # Final reset to clear any stale state
    adapter_healthy || die "Adapter not healthy before test."
    echo "→ Adapter reset: $(curl -s -X DELETE $ADAPTER_URL/reset)"
}

# ── run simulation with hang detection ───────────────────────────────────────

run_sim() {
    local test_class="$1"
    echo "→ Running $test_class..."
    rm -f "$SIM_LOG"
    setsid mvn -q exec:java -Dexec.mainClass="$test_class" </dev/null >"$SIM_LOG" 2>&1 &
    local sim_pid=$!

    local last_size=0
    local stall_seconds=0

    while kill -0 "$sim_pid" 2>/dev/null; do
        sleep 5
        local cur_size
        cur_size=$(wc -c < "$SIM_LOG" 2>/dev/null || echo 0)
        if [[ "$cur_size" -gt "$last_size" ]]; then
            last_size=$cur_size
            stall_seconds=0
        else
            stall_seconds=$((stall_seconds + 5))
        fi

        if [[ $stall_seconds -ge $HANG_TIMEOUT ]]; then
            echo "  HANG detected (no output for ${HANG_TIMEOUT}s)."
            kill -9 "$sim_pid" 2>/dev/null || true

            echo "  Adapter log tail:"
            tail -3 "$ADAPTER_LOG" 2>/dev/null || echo "  (no adapter log)"
            if [[ $TEST_MODE -eq 0 ]]; then
                echo "  Scheduler recent:"
                docker logs "$SCHEDULER_CONTAINER" --since 30s 2>&1 | grep -v "^E" | tail -3
            fi

            if [[ $RECOVERY_DONE -eq 0 ]]; then
                RECOVERY_DONE=1
                return 2  # signal: retry after recovery
            else
                echo "FAIL: Hung again after recovery." >&2
                echo "--- Last sim log ---" >&2
                tail -20 "$SIM_LOG" >&2
                return 1
            fi
        fi
    done

    wait "$sim_pid"
    return $?
}

show_results() {
    # Capture scheduler logs
    if [[ $TEST_MODE -eq 0 ]] && scheduler_running; then
        docker logs "$SCHEDULER_CONTAINER" >"$SCHEDULER_LOG" 2>&1 || true
    fi

    if [[ $NO_FILTER -eq 1 ]]; then
        cat "$SIM_LOG"
    else
        grep -E "$OUTPUT_FILTER" "$SIM_LOG" || true
    fi

    echo ""
    echo "Logs written to: $SIM_LOG, $ADAPTER_LOG$([ $TEST_MODE -eq 0 ] && echo ", $SCHEDULER_LOG")"
}

# ── main ──────────────────────────────────────────────────────────────────────

[[ $# -lt 1 ]] && show_help

while [[ $# -gt 0 && "$1" == --* ]]; do
    case "$1" in
        --test-mode)
            TEST_MODE=1
            ADAPTER_FLAGS="--test-mode"
            shift
            ;;
        --no-compile)
            NO_COMPILE=1
            shift
            ;;
        --no-filter)
            NO_FILTER=1
            shift
            ;;
        --keep-infra)
            KEEP_INFRA=1
            shift
            ;;
        --scheduler=*)
            ADAPTER_FLAGS="--scheduler=${1#--scheduler=}"
            shift
            ;;
        --volcano)
            SCHEDULER_DIR="volcano-scheduler"
            SCHEDULER_CONTAINER="volcano-scheduler"
            ADAPTER_FLAGS="--scheduler=volcano"
            HANG_TIMEOUT=60  # Volcano's scheduling round timeout is 30s; need margin for rescheduling
            shift
            ;;
        --help)
            show_help
            ;;
        *)
            die "Unknown flag: $1. Use --help for usage."
            ;;
    esac
done

[[ $# -lt 1 ]] && die "Missing test class. Use --help for usage."
TEST_CLASS="$1"

# Set log paths: debug/<ShortTestName>_<timestamp>_{sim,adapter,scheduler}.log
# Also symlink debug/{sim,adapter,scheduler}.log → latest run for convenience.
LOG_SHORT="${TEST_CLASS##*.}"
LOG_TS="$(date +%Y%m%d_%H%M%S)"
ADAPTER_LOG="debug/${LOG_SHORT}_${LOG_TS}_adapter.log"
SIM_LOG="debug/${LOG_SHORT}_${LOG_TS}_sim.log"
SCHEDULER_LOG="debug/${LOG_SHORT}_${LOG_TS}_scheduler.log"
ln -sf "${LOG_SHORT}_${LOG_TS}_adapter.log" debug/adapter.log
ln -sf "${LOG_SHORT}_${LOG_TS}_sim.log" debug/sim.log
ln -sf "${LOG_SHORT}_${LOG_TS}_scheduler.log" debug/scheduler.log

cleanup() {
    if [[ $KEEP_INFRA -eq 0 ]]; then
        echo "→ Cleaning up adapter..."
        pkill -9 -x adapter-linux 2>/dev/null || true
    fi
}
trap cleanup EXIT

check_prereqs
if [[ $KEEP_INFRA -eq 0 ]]; then
    kill_stale_processes
fi
build_all
ensure_infra

# First attempt
run_sim "$TEST_CLASS" && RC=0 || RC=$?

if [[ $RC -eq 0 ]]; then
    echo "✓ Test passed."
    show_results
    exit 0
elif [[ $RC -eq 2 ]]; then
    # Hung — recover and retry once
    restart_scheduler_and_reset
    curl -s -X DELETE "$ADAPTER_URL/reset" >/dev/null
    echo "→ Retrying $TEST_CLASS..."
    run_sim "$TEST_CLASS" && RC=0 || RC=$?
    if [[ $RC -eq 0 ]]; then
        echo "✓ Test passed (after recovery)."
        show_results
        exit 0
    else
        echo "FAIL: Test failed after recovery." >&2
        tail -30 "$SIM_LOG" >&2
        exit 1
    fi
else
    echo "FAIL: Test exited with error." >&2
    tail -30 "$SIM_LOG" >&2
    exit 1
fi
