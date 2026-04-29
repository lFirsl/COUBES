# Overall Scheduler Score (OSS) — Formal Definition

## 1. Objective

COUBES evaluates container orchestration schedulers across an extensible family of metrics. The **Overall Scheduler Score (OSS)** is a composite metric that summarises a scheduler's aggregate performance into a single scalar value, enabling direct comparison between schedulers.

The OSS is not intended to replace individual metric scores. It serves as a **summary statistic** — analogous to a weighted GPA — that answers: *"Given this test suite, which scheduler performed better overall?"* Individual per-metric scores are always reported alongside the OSS to preserve visibility into trade-offs. A scheduler that excels at energy efficiency but underperforms on latency will have both facts visible in the per-metric breakdown, even if its OSS is competitive.

---

## 2. Metrics

COUBES currently defines five metrics, divided into two categories based on what they depend on.

### 2.1 Decision-Based Metrics

These depend only on the scheduler's placement decisions, not on the hardware executing the simulation. Given the same scenario and the same placement choices, the metric value is deterministic regardless of the machine running the benchmark.

- **Power Efficiency** — total energy consumed (Wh), derived from CloudSim's power model.
- **Bin-Packing Efficiency** — time-weighted consolidation ratio (active cloudlets / active VMs). Higher values indicate tighter packing.

### 2.2 Performance-Based Metrics

These depend on both the scheduler's logic and the hardware on which the scheduler and adapter execute. The same scheduler may produce different throughput or latency values on different machines.

- **Throughput** — pods scheduled per second.
- **Time-to-Completion (TTC)** — simulated seconds to complete all cloudlets.
- **Scheduling Latency** — per-pod time from submission to binding (ms).

Additional metrics can be incorporated into the framework provided they have well-defined, reproducible measurement procedures and are classified into one of the two categories above.

---

## 3. Normalisation

Raw metric values are not directly comparable: energy is measured in Wh, consolidation is a ratio, latency is in milliseconds. Normalisation maps each metric to a common [0, 1] scale where higher is better.

### 3.1 Decision-Based Metrics: Min-Max Normalisation

Each decision-based metric is normalised using **scenario-specific theoretical bounds**:

```
Norm(x) = (x - x_min) / (x_max - x_min)
```

where **x_min** and **x_max** are the metric values corresponding to the best and worst possible scheduling outcomes for the given scenario, derived analytically from the scenario's physical setup (host count, PE count, MIPS, power model, cloudlet lengths, VM count).

These bounds are:
- **Deterministic** — they depend only on the scenario definition, not on any scheduler's output.
- **Stable** — adding a new scheduler to the evaluation does not change the bounds.
- **Scenario-specific** — different scenarios have different bounds, reflecting their different infrastructure configurations.

For **lower-is-better** metrics (e.g. energy, where less is better), the normalised score is inverted:

```
NormScore(x) = 1 - Norm(x)
```

so that a score of 1 always represents the best possible outcome.

### 3.2 Performance-Based Metrics: Baseline-Relative Logistic Normalisation

Because performance metrics are hardware-dependent, raw values cannot be compared across machines. Instead, each metric is first expressed as a **ratio against a baseline scheduler** run on the same hardware:

```
R = P_scheduler / P_baseline
```

For **higher-is-better** metrics (e.g. throughput), R > 1 means the scheduler outperforms the baseline. For **lower-is-better** metrics (e.g. latency, TTC), the ratio is inverted so that improvement always corresponds to R > 1:

```
R = P_baseline / P_scheduler
```

The ratio R is unbounded (a scheduler could be 10× faster), which would distort a weighted average. It is therefore mapped to [0, 1] using a **logistic transform**:

```
NormPerf(R) = 1 / (1 + e^(-k * (R - 1)))
```

This transform has the following properties:
- **R = 1** (parity with baseline) → score = **0.5**
- **R > 1** (better than baseline) → score approaches **1**
- **R < 1** (worse than baseline) → score approaches **0**
- The function is **bounded**, preventing extreme outliers from dominating the composite score.

The parameter **k** controls the sensitivity of the transform — how steeply the score changes around R = 1. A higher k rewards small improvements more sharply; a lower k produces a gentler curve. The appropriate value of k is subject to calibration and should be reported alongside results.

---

## 4. Per-Scenario OSS

For a given test scenario, the OSS is the **weighted average** of all normalised metric scores:

```
OSS_scenario = Σ(w_i * NormMetric_i) / Σ(w_i)
```

where w_i is the weight assigned to metric i. Weights are **configurable per scenario** to reflect the scenario's evaluation priorities. For example:
- An **undercrowding test** (designed to measure idle energy waste) may assign high weight to energy and bin-packing.
- A **fragmentation test** (designed to measure scheduling under constrained capacity) may reduce the weight of bin-packing, since consolidation in that scenario creates fragmentation that delays long cloudlets — a known and expected trade-off.

Equal weights are the default unless a scenario's design justifies a different distribution.

---

## 5. Suite-Level Aggregation

A test suite consists of multiple scenarios. COUBES aggregates results at the suite level in two complementary ways.

### 5.1 Per-Metric Suite Averages

For each metric, the normalised score is averaged across all scenarios in the suite:

```
MetricAvg_i = (1/S) * Σ_j NormMetric_i,j
```

where S is the number of scenarios and NormMetric_i,j is the normalised score of metric i in scenario j. This produces a per-metric profile of the scheduler across the full suite, enabling trade-off analysis: *"Scheduler A scores 0.85 on energy across all tests but 0.45 on latency."*

### 5.2 Suite OSS

The suite-level OSS is the **mean of per-scenario OSS values**:

```
SuiteOSS = (1/S) * Σ_j OSS_j
```

This is the single headline number for comparing schedulers across a suite.

### 5.3 Relationship Between Suite OSS and Per-Metric Averages

The suite OSS is the average of per-scenario weighted sums. The per-metric suite averages are unweighted averages of per-metric scores. Because different scenarios may use different metric weights, **the suite OSS cannot in general be reconstructed from the per-metric suite averages**. The two are independent and complementary:

- **Suite OSS** answers: *"Which scheduler performed better overall?"*
- **Per-metric suite averages** answer: *"Where does each scheduler excel or fall short?"*

Both should be reported together.

---

## 6. Defining Theoretical Bounds

Every test scenario must define x_min and x_max for each decision-based metric. The procedure:

1. **Identify the fixed infrastructure**: host count, PEs per host, MIPS per PE, power model parameters, VM count, cloudlet count, cloudlet length, cloudlet PEs.
2. **Derive the best-case scheduling outcome**: the placement that optimises the metric (e.g. ideal bin-packing for energy, even spread for TTC).
3. **Derive the worst-case scheduling outcome**: the placement that produces the worst metric value.
4. **Compute the metric value** for each extreme using the scenario's physical model.

### Example: Undercrowding Test

**Setup:** 10 VMs, power model P(u) = 50 + 450u, cloudlets that produce u = 1/4 per VM if evenly spread.

**TTC:** T_min = 160s, T_max = 320s (upper placeholder).

**Energy:**
- Worst case (even spread): all 10 VMs at u = 1/4 for 160s.
  E_worst = 10 × P(1/4) × 160/3600 ≈ 73 Wh.
- Best case (ideal consolidation): 2 VMs at u = 1, 1 at u = 1/2, 7 idle.
  E_best = (2 × P(1) + P(1/2)) × 160/3600 ≈ 56 Wh.

**Bin-Packing:** B_min = 1 (1 cloudlet per VM), B_max = 10/3 ≈ 3.33 (all cloudlets on 3 VMs).

### Example: Fragmentation Test

**Setup:** 5 VMs with 5 CPU cores each, power model P(u) = 50 + 450u. Two waves of cloudlets with mixed sizes that create fragmentation under consolidation.

**TTC:** T_min = 1650s (even initial distribution), T_max = 1850s (delay caused by fragmented capacity).

**Energy:**
- Highest-energy case (even placement / LeastAllocated): all 5 VMs remain active throughout.
  E_LA ≈ 5×P(3/5)×50/3600 + 5×P(1)×110/3600 + 5×P(2/5)×1490/3600 = 575 Wh.
- Lowest-energy case (consolidated / MostAllocated): only 3 VMs remain active after t=160s.
  E_MA ≈ 3×P(1)×50/3600 + (3×P(1)+2×P(4/5))×110/3600 + (2×P(4/5)+P(2/5))×1490/3600 + P(2/5)×200/3600 = 539 Wh.

**Bin-Packing:** B_min = 1.42 (even spread), B_max = 2.135 (consolidation onto 3 VMs).

Note: in this scenario, higher bin-packing correlates with worse TTC because consolidation creates fragmentation. The bin-packing metric still scores higher consolidation as better, but its weight is reduced for this scenario to reflect that consolidation is not the evaluation priority. Weights are the mechanism for expressing scenario priorities.

---

## 7. Interpretation Guidelines

- **OSS is ordinal within a test suite.** Scheduler A scoring 0.75 vs Scheduler B scoring 0.60 means A performed better overall on this suite. The magnitude of the difference depends on the weights and bounds chosen.
- **OSS is not comparable across suites** with different scenario compositions or weight configurations.
- **Per-metric scores reveal trade-offs.** Two schedulers with similar OSS values may have very different per-metric profiles. Always inspect the breakdown.
- **The baseline scheduler for performance metrics must be consistent** across all schedulers being compared. Changing the baseline changes all performance-based scores.
- **Weights encode evaluation priorities**, not metric importance in the abstract. A weight of 0 excludes a metric; equal weights express no preference.
