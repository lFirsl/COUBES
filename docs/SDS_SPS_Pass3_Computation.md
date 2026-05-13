# SDS / SPS Computation — Pass 3 Results

**Baseline:** Least Allocated (for all tests)

## Formula Recap

For each metric, R = scheduler / baseline (higher-is-better metrics) or baseline / scheduler (lower-is-better metrics). R > 1 means the evaluated scheduler outperforms the baseline.

- **SDS** = mean of decision-based metric ratios (TTC, Energy, Consolidation)
- **SPS** = mean of performance-based metric ratios (wall-clock time)

---

## Fragmentation Test

| Metric | Least Alloc. (baseline) | Most Alloc. | Volcano |
|---|---|---|---|
| TTC (s) ↓ | 1651 | 1762 | 1762 |
| Energy (Wh) ↓ | 701.27 | 576.99 | 576.99 |
| Consolidation ↑ | 1.26 | 1.85 | 1.85 |

**Most Allocated SDS:**
- TTC: R = 1651/1762 = 0.937 (worse)
- Energy: R = 701.27/576.99 = 1.215 (better)
- Consolidation: R = 1.85/1.26 = 1.468 (better)
- **SDS = (0.937 + 1.215 + 1.468) / 3 = 1.207**

**Volcano SDS:**
- TTC: R = 1651/1762 = 0.937
- Energy: R = 701.27/576.99 = 1.215
- Consolidation: R = 1.85/1.26 = 1.468
- **SDS = 1.207** (identical to Most Allocated)

**SPS (wall-clock):**
- Most Allocated: R = 403/378 = 1.066 (slightly faster) → **SPS = 1.066**
- Volcano: R = 403/2286 = 0.176 (much slower) → **SPS = 0.176**

### Summary — Fragmentation

| Scheduler | SDS | SPS |
|---|---|---|
| Least Allocated (baseline) | 1.000 | 1.000 |
| Most Allocated | 1.207 | 1.066 |
| Volcano | 1.207 | 0.176 |

**Interpretation:** Most Allocated and Volcano make identical placement decisions (SDS=1.207), both outperforming the baseline on energy/consolidation at a small TTC cost. But Most Allocated is 6x faster in wall-clock (SPS=1.066 vs 0.176). On the Pareto frontier, Most Allocated dominates Volcano (same SDS, better SPS).

---

## Sustained Overload Test

| Metric | Least Alloc. (baseline) | Most Alloc. | Volcano |
|---|---|---|---|
| TTC (s) ↓ | 973 | 973 | 1094 |
| Energy (Wh) ↓ | 545.92 | 546.98 | 571.79 |
| Consolidation ↑ | 1.97 | 1.95 | 1.73 |
| HP turnaround (s) ↓ | 370.9 | 370.8 | 328.3 |
| Batch turnaround (s) ↓ | 638.7 | 638.5 | 667.4 |

**Most Allocated SDS:**
- TTC: R = 973/973 = 1.000
- Energy: R = 545.92/546.98 = 0.998
- Consolidation: R = 1.95/1.97 = 0.990
- HP turnaround: R = 370.9/370.8 = 1.000
- Batch turnaround: R = 638.7/638.5 = 1.000
- **SDS = (1.000 + 0.998 + 0.990 + 1.000 + 1.000) / 5 = 0.998**

**Volcano SDS:**
- TTC: R = 973/1094 = 0.889
- Energy: R = 545.92/571.79 = 0.955
- Consolidation: R = 1.73/1.97 = 0.878
- HP turnaround: R = 370.9/328.3 = 1.130 (better!)
- Batch turnaround: R = 638.7/667.4 = 0.957
- **SDS = (0.889 + 0.955 + 0.878 + 1.130 + 0.957) / 5 = 0.962**

**SPS (wall-clock):**
- Most Allocated: R = 6996/6855 = 1.021 → **SPS = 1.021**
- Volcano: R = 6996/44673 = 0.157 → **SPS = 0.157**

### Summary — Sustained Overload

| Scheduler | SDS | SPS |
|---|---|---|
| Least Allocated (baseline) | 1.000 | 1.000 |
| Most Allocated | 0.998 | 1.021 |
| Volcano | 0.962 | 0.157 |

**Interpretation:** Most Allocated ≈ Least Allocated (SDS≈1.0) — confirms the null distinction on homogeneous nodes. Volcano's SDS is slightly below 1.0 overall, but this masks an important detail: HP turnaround ratio is 1.130 (11.5% better). If HP turnaround is weighted more heavily (as it would be in an HP-focused evaluation), Volcano's SDS improves. The aggregate SDS hides the priority differentiation tradeoff.

---

## Gang Atomicity Test

This test is special: kube-scheduler deadlocks (0 work completed). Computing ratios against a deadlocked baseline is meaningless. Instead, this test demonstrates a **binary capability difference** — Volcano can solve this problem, kube-scheduler cannot.

| Scheduler | Outcome | SDS | SPS |
|---|---|---|---|
| Least Allocated (baseline) | Deadlock (0/12) | — | — |
| Most Allocated | Deadlock (0/12) | — | — |
| Volcano | Success (12/12, TTC=242s) | ∞ (undefined) | — |

**Interpretation:** SDS/SPS ratios are undefined when the baseline produces no useful output. This test is better reported as a qualitative pass/fail rather than a numeric score. This is a limitation of the ratio-based scoring approach — it cannot express "works vs doesn't work."

---

## Cross-Test Pareto Summary

Excluding the Gang Atomicity Test (qualitative, not ratio-scorable):

| Scheduler | Frag. SDS | Frag. SPS | Overload SDS | Overload SPS |
|---|---|---|---|---|
| Least Allocated | 1.000 | 1.000 | 1.000 | 1.000 |
| Most Allocated | 1.207 | 1.066 | 0.998 | 1.021 |
| Volcano | 1.207 | 0.176 | 0.962 | 0.157 |

**Pareto analysis:**
- **Fragmentation:** Most Allocated dominates Volcano (same SDS, better SPS). Both dominate Least Allocated on SDS.
- **Overload:** Most Allocated ≈ Least Allocated (no meaningful difference). Both dominate Volcano on SPS; Volcano is slightly worse on SDS too.

**Key insight for the paper:** The SDS/SPS framework works well for quantitative tradeoffs but breaks down for qualitative differences (Gang Atomicity). The paper should acknowledge this: ratio-based scoring captures continuous tradeoffs but cannot express binary capability gaps. The Gang Atomicity result is better presented as a separate qualitative finding alongside the numeric scores.

---

## Recommendations for the Paper

1. Show SDS/SPS tables for Fragmentation and Sustained Overload tests.
2. For Gang Atomicity, present as qualitative (pass/fail) rather than forcing a numeric score.
3. Note that aggregate SDS can mask important sub-metric tradeoffs (HP vs batch turnaround).
4. The Pareto plot would show Most Allocated dominating Volcano in both quantitative tests — but this ignores Volcano's unique capability (gang atomicity). Discuss this as a limitation of purely numeric comparison.
