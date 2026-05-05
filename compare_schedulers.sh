#!/usr/bin/env bash
# compare_schedulers.sh — Run Fragmentation_Test on kube-scheduler and Volcano,
# then output a CSV with raw values and Volcano scores relative to kube-scheduler baseline.
#
# Usage: bash compare_schedulers.sh [--no-compile]
#
# Output: scheduler_comparison.csv

set -euo pipefail

TEST_CLASS="org.example.testSuite.Fragmentation_Test"
OUTPUT_CSV="scheduler_comparison.csv"
SIM_LOG="/tmp/coubes-sim.log"
NO_COMPILE=""

for arg in "$@"; do
    case "$arg" in
        --no-compile) NO_COMPILE="--no-compile" ;;
        *) echo "Unknown argument: $arg" >&2; exit 1 ;;
    esac
done

# ── parse metrics from sim log ────────────────────────────────────────────────

parse_metric() {
    local pattern="$1"
    local log="$2"
    grep -oP "(?<=${pattern})[0-9]+(\.[0-9]+)?" "$log" | head -1
}

extract_metrics() {
    local log="$1"
    SIM_TIME=$(parse_metric "Simulated Time Elapsed: " "$log")
    WALL_MS=$(parse_metric "Wall-clock Time Elapsed: " "$log")
    ENERGY=$(parse_metric "Energy consumption: " "$log")
    CONSOLIDATION=$(parse_metric "Time-weighted avg consolidation: " "$log")
    THROUGHPUT=$(grep "Throughput:" "$log" | grep -oP "[0-9]+(\.[0-9]+)?" | head -1)
    CLOUDLETS_COMPLETED=$(grep -oP "(?<=WARNING: Expected 20 cloudlets to complete but only received )[0-9]+" "$log" || echo "20")
}

# ── run one scheduler ─────────────────────────────────────────────────────────

run_scheduler() {
    local label="$1"
    local flag="$2"   # "" for kube, "--volcano" for volcano

    echo ""
    echo "════════════════════════════════════════"
    echo "  Running $label..."
    echo "════════════════════════════════════════"

    # First run compiles; subsequent runs skip if --no-compile passed
    local compile_flag="$NO_COMPILE"
    if [[ "$label" == "kube-scheduler" ]]; then
        compile_flag=""   # always compile on first run
    fi

    # shellcheck disable=SC2086
    bash run_test.sh $flag $compile_flag "$TEST_CLASS"

    cp "$SIM_LOG" "/tmp/coubes-sim-${label}.log"
    echo "  Log saved to /tmp/coubes-sim-${label}.log"
}

# ── relative score (volcano / baseline) ──────────────────────────────────────
# For lower-is-better metrics (energy, sim_time, wall_ms): ratio = baseline / volcano  (>1 = volcano better)
# For higher-is-better metrics (consolidation, throughput, cloudlets): ratio = volcano / baseline (>1 = volcano better)

relative() {
    local baseline="$1"
    local value="$2"
    local higher_is_better="$3"
    if [[ -z "$baseline" || -z "$value" || "$baseline" == "0" ]]; then
        echo "N/A"
        return
    fi
    if [[ "$higher_is_better" == "1" ]]; then
        awk "BEGIN { printf \"%.4f\", $value / $baseline }"
    else
        awk "BEGIN { printf \"%.4f\", $baseline / $value }"
    fi
}

# ── main ──────────────────────────────────────────────────────────────────────

run_scheduler "kube-scheduler" ""
extract_metrics "/tmp/coubes-sim-kube-scheduler.log"
K_SIM_TIME="$SIM_TIME"
K_WALL_MS="$WALL_MS"
K_ENERGY="$ENERGY"
K_CONSOLIDATION="$CONSOLIDATION"
K_THROUGHPUT="$THROUGHPUT"
K_CLOUDLETS="$CLOUDLETS_COMPLETED"

run_scheduler "volcano" "--volcano"
extract_metrics "/tmp/coubes-sim-volcano.log"
V_SIM_TIME="$SIM_TIME"
V_WALL_MS="$WALL_MS"
V_ENERGY="$ENERGY"
V_CONSOLIDATION="$CONSOLIDATION"
V_THROUGHPUT="$THROUGHPUT"
V_CLOUDLETS="$CLOUDLETS_COMPLETED"

# ── write CSV ─────────────────────────────────────────────────────────────────

{
    echo "metric,type,kube_scheduler,volcano,volcano_relative_score,note"

    echo "simulated_time_s,decision,${K_SIM_TIME},${V_SIM_TIME},$(relative "$K_SIM_TIME" "$V_SIM_TIME" 0),lower_is_better"
    echo "energy_wh,decision,${K_ENERGY},${V_ENERGY},$(relative "$K_ENERGY" "$V_ENERGY" 0),lower_is_better"
    echo "consolidation_ratio,decision,${K_CONSOLIDATION},${V_CONSOLIDATION},$(relative "$K_CONSOLIDATION" "$V_CONSOLIDATION" 1),higher_is_better"
    echo "cloudlets_completed,decision,${K_CLOUDLETS},${V_CLOUDLETS},$(relative "$K_CLOUDLETS" "$V_CLOUDLETS" 1),higher_is_better"
    echo "wall_clock_ms,performance,${K_WALL_MS},${V_WALL_MS},$(relative "$K_WALL_MS" "$V_WALL_MS" 0),lower_is_better"
    echo "throughput_pods_per_s,performance,${K_THROUGHPUT},${V_THROUGHPUT},$(relative "$K_THROUGHPUT" "$V_THROUGHPUT" 1),higher_is_better"

} > "$OUTPUT_CSV"

echo ""
echo "════════════════════════════════════════"
echo "  Results written to $OUTPUT_CSV"
echo "════════════════════════════════════════"
cat "$OUTPUT_CSV"

# ── pretty-printed aligned table ──────────────────────────────────────────────

PRETTY_CSV="scheduler_comparison_pretty.csv"
{
    printf "%-30s %-12s %-18s %-18s %-24s %-20s\n" \
        "metric" "type" "kube_scheduler" "volcano" "volcano_relative_score" "note"
    printf "%-30s %-12s %-18s %-18s %-24s %-20s\n" \
        "------------------------------" "------------" "------------------" "------------------" "------------------------" "--------------------"
    while IFS=',' read -r metric type kube volcano score note; do
        [[ "$metric" == "metric" ]] && continue  # skip header
        printf "%-30s %-12s %-18s %-18s %-24s %-20s\n" \
            "$metric" "$type" "$kube" "$volcano" "$score" "$note"
    done < "$OUTPUT_CSV"
} > "$PRETTY_CSV"

echo ""
echo "════════════════════════════════════════"
echo "  Pretty table written to $PRETTY_CSV"
echo "════════════════════════════════════════"
cat "$PRETTY_CSV"
