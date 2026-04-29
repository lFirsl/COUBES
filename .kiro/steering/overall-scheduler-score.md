---
inclusion: always
---

# Overall Scheduler Score (OSS) — Scoring Framework

## Purpose

COUBES evaluates schedulers across multiple metrics and produces a single composite score — the **Overall Scheduler Score (OSS)** — to enable fair, reproducible comparison. The framework distinguishes two categories of metrics based on what they depend on.

---

## Metric Categories

### Decision-Based Metrics

These depend only on the scheduler's placement choices, not on hardware speed. Examples:
- **Power Efficiency** (energy consumption in Wh)
- **Bin-Packing Efficiency** (consolidation ratio: cloudlets / active VMs)

### Performance-Based Metrics

These depend on both scheduling logic and the underlying hardware. Examples:
- **Throughput** (pods scheduled per second)
- **Time-to-Completion (TTC)** (simulated seconds to finish all cloudlets)
- **Scheduling Latency** (ms per pod, submission → binding)

---

## Normalisation

### Decision-Based: Min-Max Normalisation

Each decision-based metric is normalised to [0, 1] using **scenario-specific theoretical bounds**:

```
Norm(x) = (x - x_min) / (x_max - x_min)
```

- **x_min** and **x_max** are derived from the test scenario's physical setup (host count, PE count, power model, cloudlet lengths, etc.)
- They represent the best and worst possible scheduling outcomes given the fixed infrastructure
- Lower-is-better metrics (e.g. energy) are inverted after normalisation: `Norm_inverted = 1 - Norm(x)`
- Bounds are deterministic and do not change when new schedulers are added

### Performance-Based: Baseline-Relative Logistic Normalisation

Performance metrics are first expressed as a ratio against a baseline scheduler run on the same hardware:

```
R = P_scheduler / P_baseline
```

For **lower-is-better** performance metrics (latency, TTC), invert the ratio so that better performance still yields R > 1:

```
R = P_baseline / P_scheduler
```

The ratio is then mapped to [0, 1] using a logistic transform:

```
NormPerf(R) = 1 / (1 + e^(-k * (R - 1)))
```

Properties:
- R = 1 (parity with baseline) → score = 0.5
- R > 1 (better than baseline) → score → 1
- R < 1 (worse than baseline) → score → 0
- Bounded, so extreme improvements don't dominate the weighted sum
- **k** controls sensitivity (steepness around R = 1). Value TBD — needs calibration.

---

## Overall Scheduler Score

The OSS is a weighted average of all normalised metrics:

```
OSS = Σ(w_i * NormMetric_i) / Σ(w_i)
```

Weights are configurable per evaluation. Equal weights are the default unless a specific evaluation prioritises certain metrics.

---

## Defining Theoretical Bounds for New Tests

Every test scenario must define x_min and x_max for each decision-based metric. The process:

1. Identify the fixed infrastructure: host count, PEs per host, MIPS, power model, VM count, cloudlet count/length/PEs
2. Reason about the **best possible** scheduling outcome (e.g. ideal bin-packing, minimum active VMs)
3. Reason about the **worst possible** scheduling outcome (e.g. even spread, maximum active VMs)
4. Derive the metric value for each extreme from the physical model

### Example: Undercrowding Test

**Setup:** 10 VMs, power model P(u) = 50 + 450u, cloudlets that produce u = 1/4 per VM if evenly spread.

**TTC:** T_min = 160s, T_max = 320s (upper placeholder).

**Energy:**
- Worst case (even spread): all 10 VMs at u = 1/4 for 160s → E_worst = 10 × P(1/4) × 160/3600 ≈ 73 Wh
- Best case (ideal consolidation): 2 VMs at u = 1, 1 at u = 1/2, 7 off → E_best = (2×P(1) + P(1/2)) × 160/3600 ≈ 56 Wh

**Bin-Packing:** B_min = 1 (1 cloudlet per VM), B_max = 10/3 ≈ 3.33 (all cloudlets on 3 VMs).

### Example: Fragmentation Test

**Setup:** 5 VMs with 5 CPU cores each, power model P(u) = 50 + 450u. Two waves of cloudlets with mixed sizes that create fragmentation under consolidation.

**TTC:** T_min = 1650s (even initial distribution), T_max = 1850s (delay caused by fragmented capacity).

**Energy:**
- Highest-energy case (even placement / LeastAllocated): all 5 VMs remain active throughout.
  E_LA ≈ 5×P(3/5)×50/3600 + 5×P(1)×110/3600 + 5×P(2/5)×1490/3600 = 575 Wh
- Lowest-energy case (consolidated / MostAllocated): only 3 VMs remain active after t=160s.
  E_MA ≈ 3×P(1)×50/3600 + (3×P(1)+2×P(4/5))×110/3600 + (2×P(4/5)+P(2/5))×1490/3600 + P(2/5)×200/3600 = 539 Wh

**Bin-Packing:** B_min = 1.42 (even spread), B_max = 2.135 (consolidation onto 3 VMs).

**Important caveat:** In this scenario, higher bin-packing correlates with worse TTC because consolidation creates fragmentation that delays long cloudlets. The bin-packing metric still scores higher consolidation as better (no inversion), but its **weight should be reduced** for this scenario since consolidation is not the goal being tested. Weights are the mechanism for expressing scenario priorities — not metric inversion.

---

## Implementation Status

The OSS is currently defined in the COUBES paper. It is **not yet implemented** in the codebase. When implemented, it should:
- Live in the `metrics/` package
- Accept a list of metric values + bounds + weights
- Return the normalised per-metric scores and the composite OSS
- Be callable from `SimulationMetrics.printSummary()` or a dedicated scoring entry point
