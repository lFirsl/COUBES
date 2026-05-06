# Overload_Comparison_Test_Homogeneous — Pass 1 Analysis

**Run IDs:** `3way-homo` (MostAllocated), `3way-homo-least` (LeastAllocated), `3way-homo-volc` (Volcano)  
**Date:** 2026-05-06

## Cluster Setup

5 identical nodes: 5 PEs @ 250 MIPS, PowerModelLinear(500W, 30% static).  
Total: 25 PE slots. Nodes draw 0W when idle (CloudSim `PowerModelLinear` returns 0 at 0% util).

## 3-Way Comparison

| Metric | MostAllocated | LeastAllocated | Volcano |
|---|---|---|---|
| **TTC** | **973s** | 975s | 1094s |
| **Energy** | **545 Wh** | 548 Wh | 570 Wh |
| **Consolidation** | **1.97** | 1.95 | 1.74 |
| **HP avg turnaround** | 371s | 371s | **328s** |
| **Batch avg turnaround** | **638s** | 639s | 667s |

## Key Finding: Gang Atomicity Cost on Homogeneous Nodes

With identical nodes, MostAllocated ≈ LeastAllocated (placement doesn't matter when all
nodes are the same speed). The only meaningful difference is Volcano vs kube-scheduler.

**Volcano's TTC penalty: +121s (12.4%)**  
**Volcano's energy penalty: +25 Wh (4.6%)**

These penalties are purely from gang scheduling atomicity — no node-speed confound.

## Why Volcano Is Slower

The `batch-job` gang (4 pods × 2 PEs, length=60000) cannot be placed until t=853.

**Timeline:**
- t=730: Pods 67-69 placed on nodes 0, 2, 4 (2 PEs each)
- t=730→853: Individual pods complete one-by-one, but never 4×2-PE slots simultaneously
  - Volcano places individual pods into freed slots as they appear
  - Each freed slot gets consumed by a non-gang pod before the next slot opens
- t=853: Pods 63, 64 complete → finally 4×2-PE slots available at once
- t=853: batch-job gang placed on nodes 0, 1, 2, 4

**Kube-scheduler** places batch-job members incrementally (one per round as slots open),
completing the gang by ~t=730. The broker holds them until all 4 are placed, then submits.
Gang finishes at ~t=970.

## Why Volcano Spreads the Gang (Despite Bin-Pack Config)

Volcano IS configured for bin-packing (`mostrequested.weight: 1`). At t=853:

- Nodes 0, 2, 4: 2/5 PEs used (40% util) → scored highest (bin-pack preference)
- Nodes 1, 3: 0/5 PEs used → scored lowest

Volcano correctly places 3 members on the busy nodes first. But each busy node only has
3 free PEs — room for one 2-PE gang member, not two. The 4th member must go to node 1.

**The spread is forced by capacity, not by scheduling policy.** Atomic placement must
take whatever's available at one instant, and that instant rarely has all capacity
concentrated on one node.

## Energy Breakdown

During Volcano's tail (t=890→1093, ~200s), 4 nodes run batch-job gang members:
- Each at ~40% utilization (2/5 PEs): `500W × (0.30 + 0.70×0.40) = 290W`
- 4 nodes × 290W × 200s / 3600 ≈ 64 Wh

The actual gap is only 25 Wh because Volcano was more efficient during the earlier phase
(proportion plugin held batch pods back, keeping fewer nodes active simultaneously).

## Volcano's Advantage: HP Priority

Despite worse TTC and energy, Volcano delivers 12% better HP turnaround (328s vs 371s).
The proportion plugin ensures high-priority pods get scheduled first, even when the
cluster is overloaded. Kube-scheduler treats all pods equally.

## Conclusion

On homogeneous nodes, gang scheduling's atomicity requirement costs ~12% TTC and ~5%
energy. The tradeoff is queue fairness (HP priority). The fundamental issue: Volcano
fills freed slots with individual pods, preventing the gang from accumulating enough
simultaneous capacity. Kube-scheduler's incremental placement avoids this by "reserving"
slots as they appear.
