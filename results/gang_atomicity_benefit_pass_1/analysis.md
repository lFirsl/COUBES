# Gang_Atomicity_Benefit_Test — Pass 1 Analysis

**Date:** 2026-05-06

## Purpose

Demonstrate a scenario where gang scheduling's atomicity **prevents** fragmentation-induced
deadlock that greedy (non-gang-aware) scheduling causes.

## Cluster Setup

3 nodes × 4 PEs @ 250 MIPS = 12 PE slots total.

## Workload

4 gangs × 3 pods × 2 PEs = 24 PEs needed.  
Only 2 gangs (12 PEs) fit simultaneously.  
Pod submission order is interleaved: A0, B0, C0, D0, A1, B1, C1, D1, A2, B2, C2, D2.

## Results

| | kube-scheduler | Volcano |
|---|---|---|
| Round 1 placements | 6 pods: A(2), B(2), C(1), D(1) | 6 pods: A(3), B(3) |
| Gangs complete in round 1 | **0** | **2** |
| Final outcome | **Deadlock → all 12 pods FAILED** | All 4 gangs complete in 2 rounds |
| TTC | ∞ (0 work done) | **242s** |

## What Happens with kube-scheduler

1. Scheduler sees 12 individual 2-PE pods (no gang awareness)
2. Places 6 pods (fills 12 PEs), spread across all 4 gangs due to interleaved order
3. Gang-A has 2/3 members placed, gang-B has 2/3, gang-C has 1/3, gang-D has 1/3
4. Broker holds all placed members in the gang waiting room (not submitted to CloudSim)
5. The held members block PE slots but don't execute → no completions → no capacity freed
6. Remaining 6 pods are unschedulable (cluster full)
7. Broker detects deadlock: held + pending < needed, nothing running → marks all FAILED

## What Happens with Volcano

1. Gang plugin evaluates each PodGroup atomically: "can all 3 members fit right now?"
2. Gang-A needs 6 PEs → yes (12 available). Places all 3 members.
3. Gang-B needs 6 PEs → yes (6 remaining). Places all 3 members.
4. Gang-C needs 6 PEs → no (0 remaining). Entire gang rejected (not partially placed).
5. Gang-D same → rejected.
6. Gangs A and B execute immediately (all members start at t=0).
7. At t=121 (after A and B finish), 12 PEs free → gangs C and D placed and execute.

## Key Insight

Gang atomicity prevents the "partial placement deadlock" where:
- Greedy placement spreads members across multiple gangs
- No gang has all members placed
- Held members consume capacity without producing work
- No work completes → no capacity freed → permanent deadlock

This is the scheduling equivalent of the "dining philosophers" problem: each gang holds
some resources (placed members) and waits for more, but the resources it needs are held
by other gangs in the same state.

## When This Matters

This scenario occurs when:
1. Multiple gangs compete for limited capacity in the same wave
2. Total gang demand exceeds cluster capacity (not all fit simultaneously)
3. The scheduler has no gang awareness and places pods greedily

In production Kubernetes, this manifests as gang pods stuck in Pending indefinitely,
with partial placements consuming node resources without making progress.
