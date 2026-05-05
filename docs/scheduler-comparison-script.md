# Scheduler Comparison Script

## Purpose

`compare_schedulers.sh` runs `Fragmentation_Test` against both the Kubernetes default
scheduler and the Volcano scheduler, then produces two CSV files comparing their metrics.
The kube-scheduler result is the baseline; all Volcano scores are expressed relative to it.

---

## Usage

```bash
compare_schedulers.sh [--no-compile] <TestClassName>
```

`<TestClassName>` can be short or fully qualified:

```bash
# Short name (auto-expanded to org.example.testSuite.*)
compare_schedulers.sh Fragmentation_Test

# Skip compilation on subsequent runs
compare_schedulers.sh --no-compile Fragmentation_Test

# Any other test
compare_schedulers.sh Undercrowding_Test
compare_schedulers.sh Scheduler_Latency_Test

# Fully qualified name also works
compare_schedulers.sh org.example.testSuite.Fragmentation_Test_Large
```

Both Docker schedulers must be available. The script delegates to `run_test.sh` for each
run, so all the usual infrastructure management (adapter startup, scheduler readiness,
hang detection, auto-recovery) applies automatically.

---

## Output Files

| File | Description |
|---|---|
| `scheduler_comparison.csv` | Machine-readable CSV with raw values and relative scores |
| `scheduler_comparison_pretty.csv` | Fixed-width aligned version of the same data, easy to read in a terminal or text editor |

Intermediate simulation logs are saved to:
- `/tmp/coubes-sim-kube-scheduler.log`
- `/tmp/coubes-sim-volcano.log`

---

## Metrics

| Metric | Category | Direction |
|---|---|---|
| `simulated_time_s` | Decision (SDS) | Lower is better |
| `energy_wh` | Decision (SDS) | Lower is better |
| `consolidation_ratio` | Decision (SDS) | Higher is better |
| `cloudlets_completed` | Decision (SDS) | Higher is better |
| `wall_clock_ms` | Performance (SPS) | Lower is better |
| `throughput_pods_per_s` | Performance (SPS) | Higher is better |

Categories follow the SDS/SPS framework defined in `SPS_SDS_Pareto_Framework.md`.

---

## Relative Score Interpretation

`volcano_relative_score` is always computed so that **> 1.0 means Volcano is better**:

- Lower-is-better metrics: `score = kube_value / volcano_value`
- Higher-is-better metrics: `score = volcano_value / kube_value`

A score of `1.0` means parity. `N/A` means the metric was not available in the log
(e.g. throughput is `N/A` when the broker reports no throughput data).

---

## How It Works

1. Runs `bash run_test.sh org.example.testSuite.Fragmentation_Test` (compiles on first run)
2. Copies `/tmp/coubes-sim.log` to `/tmp/coubes-sim-kube-scheduler.log`
3. Runs `bash run_test.sh --volcano --no-compile org.example.testSuite.Fragmentation_Test`
4. Copies log to `/tmp/coubes-sim-volcano.log`
5. Parses both logs with `grep -oP` regex against the `SimulationMetrics.printSummary()` output format
6. Computes relative scores and writes both CSV files

---

## Running Every Test

Run these one at a time. The first command compiles everything; the rest skip recompilation.

```bash
compare_schedulers.sh Affinity_Test
compare_schedulers.sh --no-compile Empty_Wave_Test
compare_schedulers.sh --no-compile Fragmentation_Test
compare_schedulers.sh --no-compile Fragmentation_Test_5Wave
compare_schedulers.sh --no-compile Fragmentation_Test_Large
compare_schedulers.sh --no-compile Gang_Constrained_Test
compare_schedulers.sh --no-compile Gang_Energy_Test
compare_schedulers.sh --no-compile Gang_Mixed_Test
compare_schedulers.sh --no-compile Gang_PartialFit_Test
compare_schedulers.sh --no-compile Gang_Scheduling_Test
compare_schedulers.sh --no-compile Heterogeneous_Node_Test
compare_schedulers.sh --no-compile Memory_Fragmentation_Test
compare_schedulers.sh --no-compile Mixed_Affinity_Test
compare_schedulers.sh --no-compile Mixed_Workload_Test
compare_schedulers.sh --no-compile MultiPE_Pod_Test
compare_schedulers.sh --no-compile Overload_Comparison_Test
compare_schedulers.sh --no-compile Oversized_Pod_Test
compare_schedulers.sh --no-compile Performance_vs_Efficiency_Test
compare_schedulers.sh --no-compile Queue_Priority_Test
compare_schedulers.sh --no-compile Queue_Starvation_Test
compare_schedulers.sh --no-compile Rapid_Completion_Test
compare_schedulers.sh --no-compile Scheduler_Latency_Test
compare_schedulers.sh --no-compile Scheduler_Scalability_Test
compare_schedulers.sh --no-compile Single_Pod_Test
compare_schedulers.sh --no-compile Undercrowding_Test
```

Each run overwrites `scheduler_comparison.csv` and `scheduler_comparison_pretty.csv`,
so save or rename the files between runs if you want to keep all results.

---

## Extending

**To run a different test:** change `TEST_CLASS` at the top of the script.

**To add a metric:** add a `parse_metric` call in `extract_metrics()` and a new row in
the CSV write block, following the existing pattern. Make sure the grep pattern matches
the exact string printed by `SimulationMetrics` or the broker.

**To add a third scheduler:** follow the same pattern as the kube/volcano runs — call
`run_scheduler`, extract metrics into prefixed variables, then add columns to the CSV.
