---
inclusion: always
---

# compare_schedulers.sh — Scheduler Comparison Script

## Purpose

`compare_schedulers.sh` runs a test scenario against both kube-scheduler and Volcano,
then produces CSV files comparing their metrics. Kube-scheduler is the baseline; Volcano
scores are expressed relative to it (>1.0 = Volcano better).

---

## Usage

```bash
bash compare_schedulers.sh [--no-compile] <TestClassName>
```

Short names are auto-expanded to `org.example.testSuite.*`. Both Docker schedulers must
be available. Delegates to `run_test.sh` for each run (infrastructure management,
hang detection, auto-recovery all apply).

---

## Output

Timestamped files in `results/`:
- `results/comparison_<Test>_<timestamp>.csv` — machine-readable
- `results/comparison_<Test>_<timestamp>_pretty.csv` — aligned for terminal viewing

Intermediate logs: `/tmp/coubes-sim-kube-scheduler.log`, `/tmp/coubes-sim-volcano.log`

---

## Metrics Collected

| Metric | Category | Direction |
|---|---|---|
| `simulated_time_s` | Decision (SDS) | Lower is better |
| `energy_wh` | Decision (SDS) | Lower is better |
| `consolidation_ratio` | Decision (SDS) | Higher is better |
| `cloudlets_completed` | Decision (SDS) | Higher is better |
| `hp_avg_turnaround_s` | Decision (SDS) | Lower is better |
| `batch_avg_turnaround_s` | Decision (SDS) | Lower is better |
| `wall_clock_ms` | Performance (SPS) | Lower is better |
| `effective_throughput_pods_per_s` | Performance (SPS) | Higher is better |
| `peak_throughput_pods_per_s` | Performance (SPS) | Higher is better |

- **Effective throughput**: total pods / total scheduling time across all rounds. Reflects real-world throughput including per-round overhead on small batches.
- **Peak throughput**: 1000 / average per-pod latency (each batch weighted equally). Represents scheduler capacity under saturation — "how many pods/sec with infinite supply?"

HP and batch turnaround are only present when the test uses multi-queue (classType 1/2).
They show `N/A` for tests that don't set classType.

---

## Relative Score

Always computed so >1.0 = Volcano better:
- Lower-is-better: `kube_value / volcano_value`
- Higher-is-better: `volcano_value / kube_value`

`N/A` when a metric is missing from the log.

---

## Extending

- **Add a metric:** add `parse_metric` call in `extract_metrics()` + new CSV row
- **Add a third scheduler:** duplicate the `run_scheduler` / `extract_metrics` pattern with new variable prefix + add CSV columns
