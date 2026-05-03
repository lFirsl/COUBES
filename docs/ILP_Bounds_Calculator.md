# ILP Bounds Calculator

## TL;DR

The `BoundsCalculator` answers: **"What are the best and worst possible outcomes for this scenario, given any valid scheduler?"** It uses a constraint solver (Google OR-Tools CP-SAT) to find placements that produce the theoretical minimum and maximum for TTC, energy, and consolidation. These bounds are used to normalise actual scheduler results into a 0–1 score (the SDS).

**How it works in 4 steps:**
1. You give it the VMs and cloudlets for a scenario.
2. It tries 3 placement strategies: spread (LeastAllocated-like), pack-fast (MostAllocated onto fastest VMs), pack-efficient (MostAllocated onto most power-efficient VMs).
3. For each strategy, it simulates when each cloudlet starts and finishes, then computes energy and consolidation.
4. The min/max across all 3 strategies gives the theoretical bounds.

**Key constraint:** The solver doesn't see future waves — it places wave 1 without knowing wave 2 is coming. This is what makes fragmentation possible in the bounds (a real scheduler also can't see the future).

---

## Detailed Explanation

### 1. The Problem

To compute the SDS (Scheduler Decision Score), we need to normalise each metric to [0, 1]:

```
score = (actual - worst) / (best - worst)
```

This requires knowing the **best** and **worst** theoretically achievable values for TTC, energy, and consolidation in each scenario. Previously these were calculated by hand, which was error-prone and didn't scale. The `BoundsCalculator` automates this using constraint programming.

### 2. What the Solver Decides

The solver decides **one thing**: which VM each cloudlet is assigned to. It does NOT decide when cloudlets start — that's computed by a greedy simulator after the assignment is made. This separation reflects how real schedulers work: the scheduler picks a node, and the workload starts as soon as capacity is available.

### 3. Wave-Sequential Scheduling

Real schedulers don't have foresight. When placing wave 1, they don't know wave 2 is coming. The solver mirrors this:

1. **Wave 1**: CP-SAT solver picks the VM assignment using one of three objectives (spread, pack-fast, pack-efficient).
2. **Wave 2+**: Placed greedily one cloudlet at a time on whatever VM has the earliest available capacity.

This is critical for the Fragmentation Test: if the solver could see wave 2 when placing wave 1, it would always leave room for wave 2's 2-PE cloudlets, and fragmentation would never occur. By hiding future waves, the solver can produce a wave-1 packing that fragments capacity — matching what a real MostAllocated scheduler does.

### 4. Execution Time Model

In CloudSim, execution time is:

```
execTime = cloudletLength / vm.mipsPerPe
```

PEs do **not** speed up execution. A 2-PE cloudlet takes the same time as a 1-PE cloudlet with the same length — it just occupies more PE slots while running. This is because CloudSim's internal model computes `totalWork = length × pes` and `progressPerTick = capacity × pes`, so PEs cancel out.

For heterogeneous VMs, execution time depends on which VM the cloudlet lands on (different MIPS values).

### 5. Five Placement Strategies

The solver explores five strategies to find the extremes:

| Strategy | Objective | What it finds |
|---|---|---|
| **SPREAD** | Minimise max PE-demand per VM | Best TTC (even distribution, no queuing) |
| **PACK_FAST** | Minimise VMs used, prefer fastest | Worst TTC with fastest execution per cloudlet |
| **PACK_EFFICIENT** | Minimise VMs used, prefer lowest power | Best energy via heuristic (fewest watts consumed) |
| **MIN_ENERGY** | Minimise energy proxy directly | Provably optimal minimum energy for wave 1 |
| **MAX_ENERGY** | Maximise energy proxy directly | Provably optimal maximum energy for wave 1 |

The min/max of each metric (TTC, energy, consolidation) is taken across all five strategies.

The energy strategies use a linearised proxy as the CP-SAT objective: for each (cloudlet, VM) pair, the energy cost is `P(cloudlet.pes / vm.pes) × (cloudlet.length / vm.mipsPerPe)`, plus a per-VM idle power cost. The solver optimises this proxy, and the exact energy is computed analytically from the resulting placement. This gives provably optimal energy bounds for wave-1 placement.

### 6. Constraints

The solver enforces these constraints when assigning cloudlets to VMs:

**PE capacity**: Each cloudlet must physically fit on its assigned VM (cloudlet PEs ≤ VM PEs). When total wave demand fits in the cluster, all cloudlets must fit concurrently (simultaneous fit). When demand exceeds capacity, cloudlets are allowed to queue sequentially.

**RAM capacity**: Same pattern as PEs. This enables RAM-based fragmentation scenarios where PEs are available but RAM is not.

**Hard affinity**: Cloudlets in the same affinity group must be assigned to the same VM (`addEquality` on assignment variables).

**Hard anti-affinity**: Cloudlets in the same anti-affinity group must be on different VMs (`addAllDifferent` on assignment variables).

**Individual fit**: Cloudlets that can't fit on any VM (PEs or RAM exceed every VM's capacity) are filtered out before solving and reported as infeasible.

### 7. Greedy Simulation

After the solver picks the assignment, a greedy simulator computes start times:

```
for each cloudlet (in arrival order):
    find the earliest time it can start on its assigned VM
    (i.e., when enough PEs and RAM are free)
    record start time, add to VM's running task list
```

This handles sequential queuing: if a VM has 4 PEs and 6 single-PE cloudlets are assigned to it, the first 4 start at t=0, the next 2 start at t=200 (when the first batch finishes).

### 8. Energy Computation

Energy is computed analytically from the placement timeline using each VM's power model:

```
for each VM:
    build a timeline of PE utilisation changes (cloudlet starts/ends)
    for each time interval:
        utilisation = activePEs / totalPEs
        energy += P(utilisation) × interval_duration
```

Where `P(u) = maxWatts × (staticFraction + (1 - staticFraction) × u)` is the linear power model.

VMs with no cloudlets assigned consume zero energy (they're "off").

### 9. Consolidation Computation

Consolidation matches CloudSim's `PowerDatacenterCustom` formula:

```
consolidation = activeCloudlets / activeVMs
```

This is computed as a time-weighted average over the entire simulation timeline. VMs with zero running cloudlets are considered "off" and don't count as active.

### 10. SDS Normalisation

The `SDS.java` class takes the bounds and actual metrics and produces normalised scores:

- **TTC**: lower is better → `score = 1 - (actual - min) / (max - min)`
- **Energy**: lower is better → same inversion
- **Consolidation**: higher is better → `score = (actual - min) / (max - min)`
- **SDS** = mean of all three scores

When min = max for a metric (no variance possible), the score is 1.0 (perfect — no scheduler can do better or worse). Values outside [0, 1] due to CloudSim's discrete sampling are clamped.

---

## Usage

### In a test scenario

```java
// After creating VMs and cloudlets, before simulation:
List<VmSpec> vmSpecs = List.of(
    new VmSpec(0, 4, 250, 512),   // 4 PEs, 250 MIPS, 512 MB RAM
    new VmSpec(1, 4, 250, 512));
List<CloudletSpec> wave1 = List.of(
    new CloudletSpec(0, 40000, 1, 64, null, null),  // 1 PE, 64 MB RAM
    new CloudletSpec(1, 40000, 1, 64, null, null));
PowerModelSpec power = new PowerModelSpec(500, 0.1);  // 50W idle, 500W max

TheoreticalBounds bounds = BoundsCalculator.compute(vmSpecs,
    List.of(new Wave(wave1, 0.0)), power);

// After simulation:
SDS.Result sds = SDS.compute(bounds, lastClock, actualEnergy, actualConsolidation);
```

### Heterogeneous VMs (per-VM power model)

```java
List<VmSpec> vms = List.of(
    new VmSpec(0, 4, 250, 512, 500, 0.1),   // fast, power-hungry
    new VmSpec(1, 2, 200, 512, 150, 0.3));   // slow, power-efficient
TheoreticalBounds bounds = BoundsCalculator.compute(vms, waves, null);
```

### With affinity/anti-affinity

```java
new CloudletSpec(0, 40000, 1, 0, null, "spread")       // anti-affinity group
new CloudletSpec(3, 40000, 1, 0, "colocate", null)      // affinity group
```

### Reading the output

```
TheoreticalBounds { TTC=[1650.0, 1760.0], Energy=[533.33, 574.58] Wh, Consolidation=[1.26, 1.85] }
```

The `details()` method shows the full placement for each strategy:

```
Worst-case (packed) placement (TTC=1760):
  VM 0 (5PE, 250MIPS, 500W): 6 tasks
  cl-0 (1PE, 40000MI, dur=160): t=[0, 160]
  cl-104 (2PE, 400000MI, dur=1600): t=[160, 1760]
  ...
```

---

## Known Limitations

1. **Energy bounds are provably optimal for wave 1** via direct CP-SAT optimisation. Later waves use greedy assignment, so overall energy bounds are optimal for wave-1 placement but heuristic for later waves.

2. **CloudSim discrete sampling.** CloudSim samples utilisation at 1-second intervals. The solver uses continuous integration. Small discrepancies (< 1%) are expected and handled by SDS's clamp to [0, 1].

3. **Affinity enforced via CP-SAT for all waves.** Later waves without affinity constraints use greedy assignment for better handling of fragmented capacity.

4. **Soft affinity not supported.** Only hard affinity/anti-affinity.

5. **Solver timeout: 10 seconds per wave.** For very large scenarios, the solver may return a feasible (but not provably optimal) solution.
