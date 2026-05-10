# Fragmentation_Test — Pass 1 Analysis

**Run ID:** `3f8f82ce`  
**Date:** 2026-05-06

## Cluster Setup

5 hosts × 5 PEs @ 250 MIPS, PowerModelLinear(500W, 10% static).  
5 VMs (one per host). `disableDeallocation=true`.

## Workload

- Wave 1 (t=0): 10 cloudlets × 1 PE, length=40000 (160s)
- Wave 2 (t=50): 10 cloudlets × 2 PEs, length=400000 (1600s)

## Results

| Metric | kube-scheduler (LeastAllocated) | Volcano (MostAllocated) | Relative |
|---|---|---|---|
| **TTC** | 1651s | 1762s | 0.94 (kube 6% faster) |
| **Energy** | 574 Wh | 533 Wh | **1.08 (Volcano 8% less)** |
| **Consolidation** | 1.26 | **1.85** | **1.47 (Volcano 47% better)** |

## Interpretation

This is the classic fragmentation tradeoff:

- **kube-scheduler (LeastAllocated)** spreads wave 1 across all 5 nodes (2 cloudlets each).
  When wave 2 arrives (2-PE pods), all nodes have 3 free PEs → all wave 2 pods fit immediately.
  Lower TTC but higher energy (all 5 nodes active the entire time).

- **Volcano (MostAllocated/bin-packing)** packs wave 1 onto fewer nodes. When wave 2 arrives,
  packed nodes have fewer free PEs → some 2-PE pods can't fit → must wait for wave 1 to
  complete. Higher TTC but lower energy (fewer nodes active) and much better consolidation.

## Key Observation

This test demonstrates the fundamental **TTC vs energy tradeoff** between spreading and
packing strategies. Volcano's bin-packing saves 8% energy at the cost of 6% longer TTC.
The consolidation ratio (1.85 vs 1.26) shows Volcano uses 47% fewer node-seconds per
cloudlet-second of work.
