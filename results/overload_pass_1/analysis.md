# Overload_Comparison_Test — Pass 1 Analysis

**Run ID:** `8b52ab08`  
**Date:** 2026-05-06

## Summary

| Metric | kube-scheduler | Volcano |
|---|---|---|
| Simulated TTC | 772s | 868s |
| Energy | 453 Wh | 451 Wh |
| HP avg turnaround | 356s | 253s |
| Batch avg turnaround | 394s | 501s |

Volcano has ~12% higher TTC despite using gang scheduling (all-or-nothing placement).
The root cause is the `batch-job` gang (4 pods × 2 PEs, length=60000).

## Cluster Setup

| Node | MIPS | PEs |
|---|---|---|
| 0 | 200 | 4 |
| 1 | 200 | 4 |
| 2 | 250 | 4 |
| 3 | 250 | 4 |
| 4 | 400 | 8 |

## Gang Placement Comparison

### batch-job (4 pods × 2 PEs, length=60000)

**kube-scheduler:**
- Places members incrementally as slots open: t=383, t=459, t=459, t=485
- All 4 placed on node 4 (400 MIPS) — the fastest node
- Broker holds until gang complete at t=485, submits all to CloudSim
- Execution time: 60000/400 = 150s → finishes at t=637

**Volcano:**
- Waits for 4 simultaneous 2-PE slots (gang plugin enforces atomicity)
- Can't place until t=567 (needs 4 slots free at once)
- Places on nodes 0, 2, 4, 4 — spreads across heterogeneous nodes
- Slowest member on node 0 (200 MIPS): 60000/200 = 300s → finishes at t=867
- **This is the TTC bottleneck** (868.01 ≈ 867 + 1s scheduling offset)

### compute-A (3 pods × 2 PEs, length=30000)

Both schedulers place at similar times (~t=353-383). No significant difference.

### compute-B (3 pods × 2 PEs, length=30000)

**kube-scheduler:** Placed at t=637-651, finishes at ~t=771 (node 2, 250 MIPS: 30000/250=120s)  
**Volcano:** Placed at t=386, finishes at ~t=536 — much earlier due to earlier atomic placement.

## Root Cause

Two compounding factors cause Volcano's higher TTC:

1. **Later placement of batch-job:** Volcano's gang plugin requires all 4 members to be
   schedulable simultaneously. It waits until t=567 for 4 free 2-PE slots. Kube-scheduler
   places members greedily as individual slots open (t=383→485), and the broker holds them
   until all are placed. Net delay: 82s later start.

2. **Placement on a slow node:** When Volcano finally places all 4 atomically, it spreads
   them across available nodes including node 0 (200 MIPS — the slowest). Kube-scheduler's
   MostAllocated scoring packs all 4 onto node 4 (400 MIPS — the fastest, with 8 PEs).
   The gang's completion time is gated by its slowest member: 300s on node 0 vs 150s on
   node 4.

## The Irony

Volcano's atomic gang scheduling is the *correct* behaviour for gang semantics — all
members truly start at the same simulated time. But it backfires here because:

- Waiting for simultaneous slots delays the start
- The available slots at that moment include slow nodes
- The gang's TTC is determined by its slowest member

Kube-scheduler "accidentally" achieves a better outcome: its greedy, non-gang-aware
placement happens to pack all members onto the fastest node (MostAllocated prefers the
already-busy node 4 which has the most capacity).

## Implication

For heterogeneous clusters, gang scheduling benefits from **node-speed-aware placement**
— preferring to wait slightly longer for slots on fast nodes rather than immediately
placing on any available node. Volcano's `nodeorder` plugin with `mostrequested.weight=1`
does bin-pack, but it doesn't account for the gang-completion-time-is-gated-by-slowest-member
constraint.
