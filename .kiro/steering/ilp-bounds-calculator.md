---
inclusion: always
---

# ILP Bounds Calculator — Steering Guide

## What It Is

The `BoundsCalculator` (in `metrics/BoundsCalculator.java`) uses Google OR-Tools CP-SAT
solver to compute provably optimal theoretical min/max bounds for TTC, energy, and
consolidation. These bounds feed into the SDS normalisation (in `metrics/SDS.java`),
replacing hand-calculated bounds with programmatic, auditable ones.

**Dependency:** `com.google.ortools:ortools-java:9.12.4544` (Maven Central).

---

## How It Works (Summary)

1. The solver decides **which VM each cloudlet goes to** (the assignment).
2. A greedy simulator computes **when each cloudlet starts** given the assignment.
3. Energy and consolidation are computed analytically from the placement timeline.
4. Five placement strategies are explored: SPREAD, PACK_FAST, PACK_EFFICIENT, MIN_ENERGY, MAX_ENERGY.
5. The min/max across all five strategies gives the theoretical bounds.

---

## Key Design Decisions

### Wave-Sequential Scheduling

The solver processes waves one at a time. Wave 1 is assigned by CP-SAT without
knowledge of future waves. Later waves are placed greedily on remaining capacity.
This mirrors real scheduler behaviour — a scheduler doesn't know what workloads
are coming next. Without this, the solver has perfect foresight and produces
unrealistically tight bounds (e.g., it would never create fragmentation).

### Execution Time Formula

`execTime = cloudletLength / vm.mipsPerPe`

PEs do NOT speed up execution in CloudSim. A 2-PE cloudlet with length 40000 on a
250 MIPS VM takes `40000/250 = 160s`, same as a 1-PE cloudlet. PEs affect resource
consumption (how many PE slots are occupied), not speed. This is because CloudSim's
`getCloudletTotalLength() = length * pes` — the total work scales with PEs, and
progress per tick is `capacity * pes`, so PEs cancel out.

### Simultaneous vs Sequential Fit

When total wave PE demand fits in the cluster, the solver enforces simultaneous fit
(all cloudlets in the wave must fit concurrently on their assigned VMs). When demand
exceeds cluster capacity, cloudlets are allowed to queue sequentially on their
assigned VM. This conditional constraint prevents the solver from artificially
queuing cloudlets on one VM when other VMs are free.

### Five Placement Strategies

- **SPREAD**: Minimise max PE-demand per VM (LeastAllocated behaviour)
- **PACK_FAST**: Minimise VMs used, prefer fastest VMs (highest MIPS)
- **PACK_EFFICIENT**: Minimise VMs used, prefer most power-efficient VMs (lowest watts)
- **MIN_ENERGY**: Directly minimise energy proxy via CP-SAT (provably optimal for wave 1)
- **MAX_ENERGY**: Directly maximise energy proxy via CP-SAT (provably optimal for wave 1)

The min/max of each metric is taken across all five strategies. The energy strategies
use a linearised proxy: per-cloudlet-VM energy cost `P(pes/totalPes) × duration` plus
per-VM idle power cost. The exact energy is computed analytically post-solve.

### Infeasible Cloudlets

Cloudlets that can't fit on any VM (PEs or RAM exceed the largest VM) are filtered
out before solving. They're reported in the details output but don't affect bounds.
This handles the Oversized_Pod_Test scenario gracefully.

---

## Constraints Modelled

| Constraint | How |
|---|---|
| PE capacity per VM | `vmPesUsed ≤ freePes` (simultaneous) or individual fit (sequential) |
| RAM capacity per VM | Same pattern as PEs |
| Hard affinity | `addEquality(assign[a], assign[b])` for same-group cloudlets |
| Hard anti-affinity | `addAllDifferent(assign[...])` for same-group cloudlets |
| Individual cloudlet fit | Forbid assignment to VMs where cloudlet PEs > VM PEs |
| Wave arrival times | Cloudlets can't start before their wave's arrival time |

Soft affinity is not modelled (none of the current tests use it).

---

## Files

| File | Purpose |
|---|---|
| `metrics/BoundsCalculator.java` | CP-SAT solver + greedy simulator + energy/consolidation computation |
| `metrics/SDS.java` | Min-max normalisation of actual metrics against bounds |

---

## Integration Pattern

In each test scenario, after creating VMs and cloudlets but before `CloudSim.startSimulation()`:

```java
List<VmSpec> vmSpecs = ...;  // from VM list
List<Wave> waves = ...;      // from cloudlet lists + arrival times
PowerModelSpec power = ...;  // or null if VMs have per-VM power models
TheoreticalBounds bounds = BoundsCalculator.compute(vmSpecs, waves, power);
```

After simulation:

```java
SDS.Result sds = SDS.compute(bounds, lastClock, actualEnergy, actualConsolidation);
```

### Homogeneous VMs (shared power model)

Use the 4-arg `VmSpec` constructor + pass a `PowerModelSpec`:
```java
new VmSpec(id, pes, mips, ram)  // power model applied from PowerModelSpec
BoundsCalculator.compute(vms, waves, new PowerModelSpec(500, 0.1));
```

### Heterogeneous VMs (per-VM power model)

Use the 6-arg `VmSpec` constructor + pass `null` for PowerModelSpec:
```java
new VmSpec(id, pes, mips, ram, maxWatts, staticFraction)
BoundsCalculator.compute(vms, waves, null);
```

---

## Validated Against

11 test scenarios verified with hand calculations and actual simulation runs:

| Test | Key feature validated |
|---|---|
| Single_Pod | Degenerate case (1 VM, 1 cloudlet) |
| Undercrowding | Homogeneous, single wave, TTC min=max |
| Fragmentation | Multi-wave, PE fragmentation, wave-sequential |
| Performance_vs_Efficiency | Heterogeneous VMs, per-VM power models |
| MultiPE_Pod | Multi-PE cloudlets, sequential queuing |
| Heterogeneous_Node | Mixed VM PE counts, fit constraints |
| Empty_Wave | Wave 2 after wave 1 completes |
| Rapid_Completion | Heavy rescheduling (20 cloudlets, 8 PE slots) |
| Memory_Fragmentation | RAM-based fragmentation |
| Affinity | Hard affinity + anti-affinity constraints |
| Oversized_Pod | Infeasible cloudlet filtering |

---

## Known Limitations

1. **Energy bounds are provably optimal for wave 1** via direct CP-SAT optimisation.
   Later waves use greedy assignment, so the overall energy bounds are optimal for
   wave-1 placement but heuristic for later waves.

2. **Consolidation uses continuous integration** while CloudSim samples at 1-second
   intervals. Small discrepancies (< 1%) are expected and handled by SDS's clamp.

3. **Affinity enforced via CP-SAT for all waves.** Later waves without affinity
   constraints use greedy assignment for better handling of fragmented capacity.

4. **Soft affinity not supported.** Only hard affinity/anti-affinity is modelled.

5. **Solver timeout is 10 seconds per wave.** For very large scenarios (hundreds of
   cloudlets), the solver may return FEASIBLE (not OPTIMAL). The bounds would still
   be valid but not provably tight.
