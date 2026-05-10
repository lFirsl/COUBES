# Gang_Atomicity_Benefit_Test — Pass 2 Analysis

**Date:** 2026-05-09

## Purpose

Demonstrate a scenario where gang scheduling's atomicity **prevents** a deadlock that
greedy (non-gang-aware) scheduling causes. This is the strongest differentiator between
Volcano and kube-scheduler: a qualitative difference in outcome (success vs failure),
not just a quantitative difference in metrics.

## Cluster Setup

3 VMs × 4 PEs @ 250 MIPS = 12 PE slots total.
PowerModel P(u) = 50 + 450u × (static=30%).

## Workload

4 gangs × 3 pods × 2 PEs = 24 PEs needed (only 12 fit simultaneously).
Each pod: 30000 MI = 120s execution time.

**Submission order is interleaved:** A0, B0, C0, D0, A1, B1, C1, D1, A2, B2, C2, D2.
This forces a non-gang-aware scheduler to see pods from all gangs mixed together.

## Expected Behaviour

**kube-scheduler (both profiles):** Sees 12 individual 2-PE pods. Places 6 (fills 12 PEs).
Due to interleaved order, the 6 placed pods are spread across all 4 gangs — no gang
has all 3 members placed. The broker holds placed gang members in the waiting room
(not submitted to CloudSim) until the full gang is ready. But held members block PE
slots without executing → no completions → no capacity freed → deadlock detected →
all pods FAILED.

**Volcano (gang plugin):** Evaluates each PodGroup atomically: "can all 3 members of
this gang fit right now?" Places complete gangs or rejects them entirely. Two gangs
fit (12 PEs), two are rejected. After the first two complete (t=121), the remaining
two are placed. All 12 pods succeed.

## Results

| Metric | Least Allocated | Most Allocated | Volcano |
|---|---|---|---|
| Simulated TTC (s) | 0.01 (deadlock) | 0.01 (deadlock) | 242 |
| Energy (Wh) | 0.00 | 0.00 | 99.17 |
| Consolidation | 0.0 | 0.0 | 2.0 |
| Effective Throughput (pods/s) | 1714 | 1714 | 14.42 |
| Peak Throughput (pods/s) | 1714 | 1714 | 11.15 |
| Wall-clock (ms) | 193 | 140 | 1462 |
| Cloudlets completed | 0/12 (all FAILED) | 0/12 (all FAILED) | 12/12 (all SUCCESS) |

## Analysis

### kube-scheduler: deadlock (both profiles)

Both Least and Most Allocated produce identical outcomes — total failure:

1. Scheduler places 6 pods (fills 12 PEs), spread across all 4 gangs:
   - Least Allocated: A(2), B(2), C(1), D(1)
   - Most Allocated: A(2), B(2), C(1), D(1) (same distribution, different node choices)
2. No gang has all 3 members placed.
3. Broker holds all 6 placed members in the gang waiting room.
4. Held members occupy PE slots but don't execute (not submitted to CloudSim).
5. Remaining 6 pods are unschedulable (cluster full with held members).
6. Nothing is running → no completions → no capacity will ever free up.
7. Broker detects deadlock condition: held + pending < expected, nothing running.
8. All 12 pods marked FAILED.

**Why Least = Most here:** The deadlock is caused by the lack of gang awareness, not
by the packing strategy. Both profiles place 6 pods (the maximum that fits), and both
spread them across gangs due to the interleaved submission order. The specific node
assignments differ but the gang-level outcome is identical.

### Volcano: all gangs complete in 2 rounds

1. Gang plugin evaluates PodGroups atomically:
   - Gang-A needs 6 PEs → 12 available → **placed** (all 3 members)
   - Gang-B needs 6 PEs → 6 remaining → **placed** (all 3 members)
   - Gang-C needs 6 PEs → 0 remaining → **rejected entirely** (not partially placed)
   - Gang-D → same → **rejected entirely**
2. Gangs A and B execute immediately (all members start at t=0).
3. At t=121 (after A and B finish), 12 PEs free.
4. Round 2: Gangs C and D placed and execute.
5. All 12 pods complete at t=242.

### The "Dining Philosophers" analogy

This scenario is the scheduling equivalent of the dining philosophers problem:
- Each gang holds some resources (partially placed members occupying PE slots)
- Each gang waits for more resources (remaining members need PEs)
- The resources each gang needs are held by other gangs in the same state
- No gang can make progress → permanent deadlock

Gang atomicity resolves this by ensuring a gang either gets ALL its resources or NONE.
No partial allocations means no circular dependencies.

### When this matters in practice

This deadlock occurs when:
1. Multiple gangs compete for limited capacity in the same scheduling round
2. Total gang demand exceeds cluster capacity (not all gangs fit simultaneously)
3. The scheduler has no gang awareness and places pods greedily
4. Pod submission order is interleaved (common in real systems with multiple job queues)

In production Kubernetes without Volcano, this manifests as gang pods stuck in Pending
indefinitely, with partial placements consuming node resources without making progress.

### Tradeoff surfaced

This test surfaces a **qualitative** tradeoff, not just quantitative:
- kube-scheduler is faster (193ms vs 1462ms wall-clock) and simpler, but **cannot solve
  this class of problem at all**.
- Volcano is slower and more complex, but provides a correctness guarantee (atomicity)
  that enables workloads that are impossible without it.

The tradeoff is not "slightly better vs slightly worse" — it's "works vs doesn't work."

### Consistency with Pass 1

Pass 1 results (kube-scheduler + Volcano only) are identical:
- kube-scheduler: deadlock, all 12 FAILED
- Volcano: all 12 succeed, TTC=242s
Pass 2 confirms Most Allocated also deadlocks (same root cause).
