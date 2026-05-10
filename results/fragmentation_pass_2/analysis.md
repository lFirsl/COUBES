# Fragmentation_Test — Pass 2 Analysis

**Date:** 2026-05-09

## Purpose

Validate that COUBES correctly surfaces the packing-vs-TTC tradeoff: bin-packing
schedulers save energy but risk fragmentation that delays multi-PE workloads.

## Cluster Setup

5 VMs × 5 PEs @ 250 MIPS, PowerModel P(u) = 50 + 450u.
- Wave 1 (t=0): 15 cloudlets × 1 PE × 40000 MI (160s execution)
- Wave 2 (t=50): 5 cloudlets × 2 PE × 400000 MI (1600s execution)

## Expected Behaviour

**Least Allocated:** Spreads wave 1 evenly (3 per VM). Each VM retains 2 free PEs.
When wave 2 arrives, all 5 two-PE cloudlets fit immediately. TTC = 50 + 1601 = 1651s.
All 5 VMs remain active → highest energy.

**Most Allocated:** Packs wave 1 onto 3 VMs (5+5+5). Two VMs are empty. Wave 2's
two-PE cloudlets can only fit on the 2 empty VMs (4 PEs each → 2 cloudlets per VM = 4 total).
The 5th cloudlet must wait for a wave-1 cloudlet to finish (t=161), then starts.
TTC = 161 + 1601 = 1762s. Fewer active VMs → lowest energy.

**Volcano:** Configured with `mostrequested` weighting (bin-packing). Expected to behave
identically to Most Allocated for this scenario since there are no gangs or queues.

## Results

| Metric | Least Allocated | Most Allocated | Volcano |
|---|---|---|---|
| Simulated TTC (s) | 1651 | 1762 | 1762 |
| Energy (Wh) | 574.36 | 532.56 | 532.56 |
| Consolidation | 1.262 | 1.853 | 1.853 |
| Effective Throughput (pods/s) | 3333 | 2121 | 10.79 |
| Peak Throughput (pods/s) | 3750 | 826 | 2.59 |
| Wall-clock (ms) | 403 | 378 | 2286 |
| Cloudlets completed | 20/20 | 20/20 | 20/20 |

## Analysis

### Decision-based metrics match expectations exactly

- **Least Allocated** achieves TTC=1651s (optimal) as predicted. Even spread prevents
  fragmentation. Energy is highest (574.36 Wh) because all 5 VMs remain active.
- **Most Allocated** achieves TTC=1762s (111s penalty from fragmentation) as predicted.
  Energy is lowest (532.56 Wh) due to consolidation onto fewer VMs.
- **Volcano** produces identical decision-based results to Most Allocated. This confirms
  that Volcano's `nodeorder` plugin with `mostrequested.weight=1` implements the same
  bin-packing logic as kube-scheduler's MostAllocated profile. Without gang or queue
  features engaged, Volcano is functionally equivalent.

### Performance metrics reveal scheduler overhead

- kube-scheduler (both profiles) completes in ~400ms wall-clock. The scheduling decisions
  are near-instantaneous for 20 pods.
- Volcano takes 2286ms (5.7x slower) due to its heavier protocol: queue creation,
  PodGroup lifecycle, enqueue→allocate→backfill action pipeline, and 30s round timeout
  for the unschedulable 5th wave-2 cloudlet.

### Tradeoff surfaced

The fundamental tradeoff is **TTC vs Energy**: spreading avoids fragmentation (better TTC)
but wastes energy on idle VMs. Packing saves energy but creates fragmented capacity that
delays multi-PE workloads. COUBES makes this tradeoff quantifiable:
- Packing saves 7.3% energy (574→532 Wh)
- Packing costs 6.7% TTC (1651→1762s)

### Consistency with Pass 1

Pass 1 (kube-scheduler + Volcano only) produced identical decision-based results.
Pass 2 adds Most Allocated and confirms it matches Volcano's placement decisions exactly.
