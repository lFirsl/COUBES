# Fragmentation_Test_30Static — Pass 3 Analysis

**Date:** 2026-05-09

## Purpose

Rerun of the Fragmentation Test with a consistent 30% static power fraction (matching
the Overload and Gang tests). Previous passes used 10% static, which made the power
model inconsistent across the test suite.

## Cluster Setup

5 VMs × 5 PEs @ 250 MIPS, PowerModel P(u) = 150 + 350u (i.e., 500W max, 30% static = 150W idle).
- Wave 1 (t=0): 15 cloudlets × 1 PE × 40000 MI (160s execution)
- Wave 2 (t=50): 5 cloudlets × 2 PE × 400000 MI (1600s execution)

## Expected Behaviour

Same as pass 2 — the static fraction only affects energy magnitude, not placement decisions.

**Least Allocated:** TTC=1651s (optimal), highest energy (all 5 VMs active, higher idle draw).
**Most Allocated / Volcano:** TTC=1762s (fragmentation penalty), lowest energy (fewer active VMs).

## Results

| Metric | Least Allocated | Most Allocated | Volcano |
|---|---|---|---|
| Simulated TTC (s) | 1651 | 1762 | 1762 |
| Energy (Wh) | 701.27 | 576.99 | 576.99 |
| Consolidation | 1.262 | 1.853 | 1.853 |
| Effective Throughput (pods/s) | 5000 | 3000 | 10.63 |
| Peak Throughput (pods/s) | 5000 | 1957 | 2.59 |
| Cloudlets completed | 20/20 | 20/20 | 20/20 |

## Analysis

### Decision-based metrics: identical placement to pass 2

TTC and consolidation are unchanged from pass 2 — the power model does not affect
scheduling decisions. This confirms the test is deterministic and placement-independent
of the energy model.

### Energy values differ from pass 2 (as expected)

With 30% static (150W idle) vs 10% static (50W idle):
- Least Allocated: 701.27 Wh (was 574.36 with 10% static) — higher idle draw across 5 VMs
- Most Allocated/Volcano: 576.99 Wh (was 532.56 with 10% static) — higher idle draw on active VMs

The energy gap between spreading and packing is now **larger** (701 vs 577 = 17.7% savings)
compared to pass 2 (574 vs 533 = 7.3% savings). This is because higher idle power
penalises unnecessary active VMs more heavily — the consolidation benefit is amplified.

### Tradeoff (updated with 30% static)

- Bin-packing saves **17.7%** energy (701→577 Wh) at the cost of 6.7% longer TTC (1651→1762s)
- The energy savings from consolidation are more pronounced with higher idle power,
  making the packing-vs-TTC tradeoff more visible.

### Performance metrics: unchanged from pass 2

Scheduler overhead is independent of the power model. kube-scheduler remains ~300x
faster than Volcano in wall-clock throughput.

### Consistency

- TTC, consolidation, and placement decisions: identical to pass 2 ✓
- Energy: correctly scaled by the new power model ✓
- All three schedulers produce expected results ✓
