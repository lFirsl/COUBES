# Scalability_Test — Pass 1 Analysis

**Date:** 2026-05-10

## Purpose

Validate that COUBES can handle large-scale scenarios (thousands of pods) without
framework-level failures. Identify where the wall-clock bottleneck lies.

## Cluster Setup

50 VMs × 4 PEs @ 250 MIPS (200 PE slots total), PowerModel(500W, 30% static).

## Workload

10 waves × 500 pods × 1 PE × 10,000 MI (40s execution each).
Waves arrive every 30s (t=0, 30, 60, ..., 270).
Total: 5000 pods, all individual (no gangs, no queues).

## Results

| Metric | Least Allocated | Volcano |
|---|---|---|
| Simulated TTC (s) | 1048 | 1048 |
| Energy (Wh) | 6850.69 | 6850.69 |
| Effective Throughput (pods/s) | 73.71 | 1237.62 |
| Peak Throughput (pods/s) | 61.49 | 763.00 |
| Wall-clock (s) | 701 | 42 |
| Completed | 5000/5000 | 5000/5000 |

## Analysis

### All 5000 pods completed successfully (both schedulers)

COUBES handled the full workload without any framework-level failures for both
kube-scheduler and Volcano. The middleware, in-memory store, and both scheduler
binaries operated correctly at this scale.

### Decision-based results are identical

Both schedulers produce the same TTC (1048s) and energy (6850.69 Wh). With no gangs,
no queues, and homogeneous nodes, the placement strategy is irrelevant — all that
matters is filling 200 slots per round, which both do identically.

### Wall-clock: Volcano is 16.7× faster

This is the surprising result. kube-scheduler took 701s; Volcano took only 42s.

The cause is how each scheduler handles unschedulable pods:
- **kube-scheduler** individually evaluates each unschedulable pod, determines it cannot
  fit, and sends a status PATCH back to the middleware. With 300+ unschedulable pods
  per round, this takes ~20-30s per round.
- **Volcano** silently skips pods it cannot place (the `backfill` action simply moves on).
  The adapter's 5-second stall timeout fires once per round, then returns the partial
  result. Total overhead per overloaded round: ~5s regardless of how many pods are pending.

### Throughput reflects the wall-clock difference

Effective throughput (pods scheduled per wall-clock second across all rounds):
- kube-scheduler: 73.7 pods/s (dominated by rejection overhead)
- Volcano: 1237.6 pods/s (5s stall per round, 200 bindings per round)

### Key finding

The scalability bottleneck is NOT in COUBES — it is in the scheduler's own handling
of overloaded rounds. Interestingly, Volcano's "silent skip" approach to unschedulable
pods makes it dramatically faster at scale than kube-scheduler's per-pod rejection
reporting. This is a genuine performance characteristic of the schedulers that COUBES
faithfully surfaces.

### Implications for the paper

1. COUBES handles 50 nodes and 5000 pods on a single machine with both schedulers.
2. Wall-clock time scales with the scheduler's own processing speed, not COUBES overhead.
3. The performance difference between schedulers at scale (16.7×) is a real finding
   that would be invisible in smaller scenarios.
