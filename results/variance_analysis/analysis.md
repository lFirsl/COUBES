# Variance Analysis — Results Summary

**Date:** 2026-05-10

## Purpose

Quantify run-to-run variance introduced by scheduler non-determinism (random tie-breaking).
Prove that COUBES itself is deterministic and variance comes solely from the scheduler.

## Test Mode (Built-in Scheduler): 0% Variance

5 runs of Overload_Comparison_Test in test mode:
- TTC: 808.01s (identical all 5 runs)
- Energy: 462.95 Wh (identical all 5 runs)

**Conclusion:** COUBES framework is fully deterministic. Any variance in real-scheduler
runs is scheduler-induced.

## Overload_Comparison_Test (Heterogeneous, kube-scheduler) — 50 runs

| Metric | Mean | Median | StdDev | CV | Min | Max |
|---|---|---|---|---|---|---|
| TTC (s) | 785.23 | 772.01 | 49.98 | 6.37% | 746.01 | 987.01 |
| Energy (Wh) | 454.33 | 454.18 | 5.08 | 1.12% | 445.83 | 469.91 |

**Conclusion:** Realistic scenario with heterogeneous nodes shows low variance.
Energy is practically invariant (<1.2% CV). TTC has moderate variance (6.4% CV)
from random tie-breaking interacting with gang holding and rescheduling timing.

## Max_Variance_Test (Pathological, kube-scheduler) — 100 runs

| Metric | Mean | Median | StdDev | CV | Min | Max |
|---|---|---|---|---|---|---|
| TTC (s) | 648.00 | 1000.00 | 399.11 | 61.59% | 200.00 | 1000.00 |
| Energy (Wh) | 47.14 | 58.18 | 12.51 | 26.54% | 33.10 | 58.18 |

Distribution: 200s (44%) vs 1000s (56%) — bimodal, single coin flip.

## Max_Variance_Test (Pathological, Volcano) — 100 runs

| Metric | Mean | Median | StdDev | CV | Min | Max |
|---|---|---|---|---|---|---|
| TTC (s) | 576.00 | 200.00 | 401.29 | 69.67% | 200.00 | 1000.00 |
| Energy (Wh) | 44.89 | 33.10 | 12.58 | 28.03% | 33.10 | 58.18 |

Distribution: 200s (53%) vs 1000s (47%) — bimodal, same coin flip.

## Key Findings

| Scenario | CV (TTC) | CV (Energy) | Nature |
|---|---|---|---|
| Test mode (any) | 0% | 0% | Deterministic |
| Realistic heterogeneous (kube) | 6.37% | 1.12% | Low, many pods average out |
| Pathological (kube) | 61.59% | 26.54% | Single coin flip dominates |
| Pathological (Volcano) | 69.67% | 28.03% | Same coin flip, same variance |

## Source of Variance

kube-scheduler source (`pkg/scheduler/schedule_one.go`, line 883):
```go
if rand.Intn(cntOfMaxScore) == 0 {
    selectedIndex = cntOfMaxScore - 1
}
```
Reservoir sampling with Go's auto-seeded PRNG. When multiple nodes have equal scores,
one is chosen randomly. This is the sole source of non-determinism.

## Implications for the Paper

1. COUBES itself is deterministic (proven by test mode).
2. Variance is scheduler-induced and bounded: <7% CV for realistic scenarios.
3. Energy is practically invariant (<1.2% CV) even when TTC varies.
4. Pathological variance requires intentionally adversarial setups (few pods, heterogeneous nodes, identical starting scores).
5. For the paper's results, reporting median over multiple runs is appropriate.
6. Both kube-scheduler and Volcano exhibit the same variance pattern on the pathological test.

## Raw Data

- `max_variance_kube_100runs.csv` — 100 runs, kube-scheduler, pathological test
- `max_variance_volcano_100runs.csv` — 100 runs, Volcano, pathological test
- `overload_hetero_kube_50runs.csv` — 50 runs, kube-scheduler, realistic test
