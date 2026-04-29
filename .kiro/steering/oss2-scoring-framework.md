---
inclusion: always
---

# OSS2 — Revised Scoring Framework (SDS / SPS / Pareto)

**Supersedes:** The original OSS scoring framework (single weighted composite score with logistic normalisation for performance metrics).

**Canonical definition:** `docs/SPS_SDS_Pareto_Framework.md`

---

## What Changed (OSS → OSS2)

1. **Metrics split into two sub-scores** instead of one flat weighted average:
   - **Scheduler Decision Score (SDS)** — placement quality (energy, bin-packing, simulated TTC)
   - **Scheduler Performance Score (SPS)** — scheduling speed (throughput, latency)
2. **Pareto analysis on SDS vs SPS** replaces the single composite as the primary comparison tool. Dominated schedulers are eliminated; frontier schedulers represent genuine tradeoffs.
3. **OSS is now a tiebreaker**, not the headline metric: `OSS = (SDS + SPS) / 2`.
4. **Performance metrics use raw baseline ratios** (R = scheduler / baseline), not logistic transform. No k parameter. R = 1.0 is parity, unbounded above and below.
5. **TTC is decision-based** (simulated time, deterministic given placement). It was ambiguously categorised before.

---

## Metric Categories

### Decision-Based (feed into SDS)

Depend only on placement choices. Normalised to [0, 1] via min-max against scenario-specific theoretical bounds. Lower-is-better metrics inverted after normalisation.

- **Power Efficiency** — energy (Wh)
- **Bin-Packing Efficiency** — consolidation ratio (cloudlets / active VMs)
- **Simulated TTC** — simulated seconds to complete all cloudlets

### Performance-Based (feed into SPS)

Depend on scheduler logic + hardware. Expressed as ratio against a declared baseline scheduler on the same hardware. R > 1 = better, R = 1 = parity, R < 1 = worse. Lower-is-better metrics use inverted ratio (baseline / scheduler).

- **Scheduling Throughput** — pods / wall-clock second
- **Scheduling Latency** — ms per pod, submission → binding

---

## Formulas

```
SDS = mean(Norm_i)  for all decision-based metrics i
SPS = mean(R_j)     for all performance-based metrics j
OSS = (SDS + SPS) / 2
```

All weights are equal by default at every level. Custom weights are an optional override (see docs for details).

---

## Evaluation Procedure

1. Compute SDS and SPS for each scheduler.
2. Pareto frontier on (SDS, SPS) — eliminate dominated schedulers.
3. Optional: apply minimum thresholds (e.g. SPS ≥ 0.35) to filter frontier.
4. Rank remaining frontier schedulers by OSS.
5. Drill down into individual metrics to explain results.

When one scheduler dominates another on both axes → strict improvement, no tradeoff.
When a tradeoff exists → quantify as ΔSDS / ΔSPS ratio.

---

## Theoretical Bounds

Per-scenario, deterministic. Same process as original OSS — derive x_min and x_max from the scenario's fixed infrastructure. See existing bounds for Undercrowding and Fragmentation tests in the original OSS steering file (retained for reference).

---

## Implementation Status

**Not yet implemented in code.** When implemented:
- Lives in `metrics/` package
- Accepts metric values + bounds + baseline values
- Returns: per-metric normalised scores, SDS, SPS, OSS, Pareto frontier, tradeoff ratios
- Callable from `SimulationMetrics.printSummary()` or a dedicated scoring entry point
