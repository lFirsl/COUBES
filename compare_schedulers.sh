#!/usr/bin/env bash
# compare_schedulers.sh — Run a test on kube-scheduler and Volcano,
# then output a CSV with raw values and Volcano scores relative to kube-scheduler baseline.
#
# Usage: bash compare_schedulers.sh [--no-compile] <TestClassName>
#
# <TestClassName> can be short (Fragmentation_Test) or fully qualified
#                 (org.example.testSuite.Fragmentation_Test)
#
# Output: scheduler_comparison.csv, scheduler_comparison_pretty.csv

set -euo pipefail

NO_COMPILE=""
TEST_SHORT=""

for arg in "$@"; do
    case "$arg" in
        --no-compile) NO_COMPILE="--no-compile" ;;
        --*) echo "Unknown flag: $arg" >&2; exit 1 ;;
        *) TEST_SHORT="$arg" ;;
    esac
done

if [[ -z "$TEST_SHORT" ]]; then
    echo "Usage: bash compare_schedulers.sh [--no-compile] <TestClassName>" >&2
    echo "Example: bash compare_schedulers.sh Fragmentation_Test" >&2
    exit 1
fi

# Expand short name to fully qualified if needed
if [[ "$TEST_SHORT" != *"."* ]]; then
    TEST_CLASS="org.example.testSuite.${TEST_SHORT}"
else
    TEST_CLASS="$TEST_SHORT"
fi

TIMESTAMP=$(date +"%Y%m%d_%H%M%S")
RUN_ID=$(head -c4 /dev/urandom | xxd -p)
mkdir -p results
OUTPUT_CSV="results/comparison_${TEST_SHORT}_${TIMESTAMP}.csv"
PRETTY_CSV="results/comparison_${TEST_SHORT}_${TIMESTAMP}_pretty.csv"

# ── parse metrics from JSON results file ──────────────────────────────────────

parse_metric() {
    local key="$1"
    local file="$2"
    grep "\"${key}\"" "$file" | grep -oP '(?<=": ")[^"]+' | head -1
}

extract_metrics() {
    local json="$1"
    SIM_TIME=$(parse_metric "simulated_time_s" "$json")
    WALL_MS=$(parse_metric "wall_clock_ms" "$json")
    ENERGY=$(parse_metric "energy_wh" "$json")
    CONSOLIDATION=$(parse_metric "consolidation_ratio" "$json")
    EFF_THROUGHPUT=$(parse_metric "effective_throughput_pods_per_s" "$json")
    PEAK_THROUGHPUT=$(parse_metric "peak_throughput_pods_per_s" "$json")
    CLOUDLETS_COMPLETED=$(parse_metric "cloudlets_completed" "$json")
    HP_AVG_TURNAROUND=$(parse_metric "high_priority_avg_turnaround_s" "$json")
    BATCH_AVG_TURNAROUND=$(parse_metric "batch_avg_turnaround_s" "$json")
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
    bash run_test.sh $flag $compile_flag --run-id="$RUN_ID" "$TEST_CLASS"

    echo "  Results saved with run ID: $RUN_ID"
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
extract_metrics "results/kube-scheduler_${TEST_SHORT}_${RUN_ID}.json"
K_SIM_TIME="$SIM_TIME"
K_WALL_MS="$WALL_MS"
K_ENERGY="$ENERGY"
K_CONSOLIDATION="$CONSOLIDATION"
K_EFF_THROUGHPUT="$EFF_THROUGHPUT"
K_PEAK_THROUGHPUT="$PEAK_THROUGHPUT"
K_CLOUDLETS="$CLOUDLETS_COMPLETED"
K_HP_TURNAROUND="$HP_AVG_TURNAROUND"
K_BATCH_TURNAROUND="$BATCH_AVG_TURNAROUND"

run_scheduler "volcano" "--volcano"
extract_metrics "results/volcano_${TEST_SHORT}_${RUN_ID}.json"
V_SIM_TIME="$SIM_TIME"
V_WALL_MS="$WALL_MS"
V_ENERGY="$ENERGY"
V_CONSOLIDATION="$CONSOLIDATION"
V_EFF_THROUGHPUT="$EFF_THROUGHPUT"
V_PEAK_THROUGHPUT="$PEAK_THROUGHPUT"
V_CLOUDLETS="$CLOUDLETS_COMPLETED"
V_HP_TURNAROUND="$HP_AVG_TURNAROUND"
V_BATCH_TURNAROUND="$BATCH_AVG_TURNAROUND"

# ── write CSV ─────────────────────────────────────────────────────────────────

{
    echo "metric,type,kube_scheduler,volcano,volcano_relative_score,note"

    echo "simulated_time_s,decision,${K_SIM_TIME},${V_SIM_TIME},$(relative "$K_SIM_TIME" "$V_SIM_TIME" 0),lower_is_better"
    echo "energy_wh,decision,${K_ENERGY},${V_ENERGY},$(relative "$K_ENERGY" "$V_ENERGY" 0),lower_is_better"
    echo "consolidation_ratio,decision,${K_CONSOLIDATION},${V_CONSOLIDATION},$(relative "$K_CONSOLIDATION" "$V_CONSOLIDATION" 1),higher_is_better"
    echo "cloudlets_completed,decision,${K_CLOUDLETS},${V_CLOUDLETS},$(relative "$K_CLOUDLETS" "$V_CLOUDLETS" 1),higher_is_better"
    echo "hp_avg_turnaround_s,decision,${K_HP_TURNAROUND:-N/A},${V_HP_TURNAROUND:-N/A},$(relative "$K_HP_TURNAROUND" "$V_HP_TURNAROUND" 0),lower_is_better"
    echo "batch_avg_turnaround_s,decision,${K_BATCH_TURNAROUND:-N/A},${V_BATCH_TURNAROUND:-N/A},$(relative "$K_BATCH_TURNAROUND" "$V_BATCH_TURNAROUND" 0),lower_is_better"
    echo "wall_clock_ms,performance,${K_WALL_MS},${V_WALL_MS},$(relative "$K_WALL_MS" "$V_WALL_MS" 0),lower_is_better"
    echo "effective_throughput_pods_per_s,performance,${K_EFF_THROUGHPUT},${V_EFF_THROUGHPUT},$(relative "$K_EFF_THROUGHPUT" "$V_EFF_THROUGHPUT" 1),higher_is_better"
    echo "peak_throughput_pods_per_s,performance,${K_PEAK_THROUGHPUT},${V_PEAK_THROUGHPUT},$(relative "$K_PEAK_THROUGHPUT" "$V_PEAK_THROUGHPUT" 1),higher_is_better"

} > "$OUTPUT_CSV"

echo ""
echo "════════════════════════════════════════"
echo "  Results written to $OUTPUT_CSV"
echo "════════════════════════════════════════"
cat "$OUTPUT_CSV"

# ── pretty-printed aligned table ──────────────────────────────────────────────


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
