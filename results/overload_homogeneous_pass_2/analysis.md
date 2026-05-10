# Overload_Comparison_Test_Homogeneous — Pass 2 Analysis

**Date:** 2026-05-09

## Purpose

Evaluate scheduler behaviour under sustained overload with competing priority queues
and gang workloads on a homogeneous cluster. Tests whether queue-based fairness and
gang atomicity produce measurably different outcomes from greedy scheduling.

## Cluster Setup

5 VMs × 5 PEs @ 250 MIPS, PowerModel P(u) = 50 + 450u × (static=30%).
Total capacity: 25 PE slots.

## Workload (71 pods across 3 waves)

**Wave 1 (t=0) — 37 pods:**
- 20 high-priority individual (2 PEs, 30000 MI = 120s)
- 10 batch individual (2 PEs, 50000 MI = 200s)
- Gang "compute-A": 3 pods × 2 PEs, high-priority (40000 MI = 160s)
- Gang "batch-job": 4 pods × 2 PEs, batch (60000 MI = 240s)

**Wave 2 (t=50) — 18 pods:**
- 15 high-priority individual (2 PEs, 20000 MI = 80s)
- Gang "compute-B": 3 pods × 2 PEs, high-priority (30000 MI = 120s)

**Wave 3 (t=100) — 15 pods (1 pod is 1 PE):**
- 5 high-priority individual (1 PE, 10000 MI = 40s)
- 10 batch individual (2 PEs, 40000 MI = 160s)

Total PE demand: 136 PEs across all waves (25 available simultaneously).
Requires multiple rescheduling rounds.

## Expected Behaviour

**Least Allocated:** Spreads pods evenly. No queue awareness — all pods treated equally
regardless of classType. Gangs are held by the broker until all members are placed, but
kube-scheduler places them greedily (no atomicity guarantee from the scheduler itself).

**Most Allocated:** Packs pods tightly. Same lack of queue/gang awareness. With
homogeneous nodes, packing vs spreading should produce minimal difference since all
nodes have identical capacity and speed.

**Volcano:** Proportion plugin allocates resources proportionally between HP (weight=3)
and batch (weight=1) queues. Gang plugin holds gang members until all can be placed
atomically. This may delay individual pod starts but ensures gangs complete without
partial-placement issues.

## Results

| Metric | Least Allocated | Most Allocated | Volcano |
|---|---|---|---|
| Simulated TTC (s) | 973 | 973 | 1094 |
| Energy (Wh) | 545.92 | 546.98 | 571.79 |
| Consolidation | 1.974 | 1.954 | 1.732 |
| Effective Throughput (pods/s) | 111.95 | 114.46 | 23.96 |
| Peak Throughput (pods/s) | 85.17 | 84.83 | 12.52 |
| Wall-clock (ms) | 6996 | 6855 | 44673 |
| Cloudlets completed | 70/71 | 70/71 | 70/71 |

## Analysis

### kube-scheduler: Least ≈ Most on homogeneous nodes

Both kube-scheduler configurations produce nearly identical results:
- TTC: 973s (identical)
- Energy: 545.92 vs 546.98 Wh (0.2% difference)
- Consolidation: 1.974 vs 1.954 (negligible)

**Why:** With homogeneous nodes, the spreading-vs-packing distinction is meaningless.
All nodes have the same capacity and speed, so it doesn't matter which node a pod
lands on — only the total cluster utilisation matters. The slight energy difference
comes from minor ordering effects in which pods land on which nodes within a round.

This confirms that the Least/Most Allocated distinction only produces meaningful
differences when nodes are heterogeneous or when capacity is fragmented.

### Volcano: longer TTC due to gang holding

Volcano takes 12.4% longer (1094s vs 973s). The cause is gang scheduling overhead:
- Gang "batch-job" (4 pods × 2 PEs = 8 PEs) must wait until 8 PEs are simultaneously
  free. kube-scheduler would place members individually as capacity becomes available.
- The proportion plugin may also delay batch pods in favour of HP pods, further
  extending the batch gang's wait time.
- Each scheduling round where Volcano holds gang members without placing them adds
  a 5-second stall timeout before the adapter returns partial results.

### Volcano: higher energy from longer runtime

Volcano's energy (571.79 Wh) is 4.7% higher than kube-scheduler (~546 Wh). This is
a direct consequence of the longer TTC: nodes remain active for 121 additional seconds,
consuming idle + active power throughout.

### Volcano: lower consolidation

Consolidation (1.732 vs ~1.97) is lower for Volcano because gang holding means fewer
cloudlets are actively executing at any given time — held gang members occupy scheduler
state but don't count as active cloudlets on VMs.

### Tradeoffs surfaced

1. **Gang atomicity costs TTC** in overload scenarios where gangs compete with individual
   pods for limited capacity. The atomic placement guarantee delays gang starts.
2. **Queue fairness is invisible in TTC** here because both queues eventually complete
   all work — the proportion plugin affects ordering, not total completion.
3. **Homogeneous nodes neutralise packing strategy** — the Least/Most distinction
   only matters with heterogeneous infrastructure.

### Consistency with Pass 1

Pass 1 had only Least Allocated and Volcano. Results are consistent:
- Pass 1 Least: TTC=975s, Energy=547.54 (vs Pass 2: 973s, 545.92) — minor variance
  from scheduling order differences.
- Pass 1 Volcano: TTC=1094s, Energy=570.02 (vs Pass 2: 1094.01s, 571.79) — consistent.
