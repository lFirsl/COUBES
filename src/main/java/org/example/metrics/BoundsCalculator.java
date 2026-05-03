package org.example.metrics;

import com.google.ortools.Loader;
import com.google.ortools.sat.*;

import java.util.*;

/**
 * Computes theoretical min/max bounds for TTC, energy, and consolidation
 * using Google OR-Tools CP-SAT solver.
 *
 * Supports heterogeneous VMs (different PE counts, MIPS, RAM) and per-VM
 * power models. Execution time depends on which VM a cloudlet lands on:
 * execTime = cloudletLength / vm.mipsPerPe.
 *
 * Wave-sequential scheduling: the solver decides wave-1 placement WITHOUT
 * knowledge of future waves. Later waves are placed greedily.
 */
public class BoundsCalculator {

    static { Loader.loadNativeLibraries(); }

    /** VM specification including its power model. */
    public record VmSpec(int id, int pesNumber, int mipsPerPe, int ramMb,
                         double powerMaxWatts, double powerStaticFraction) {
        /** Convenience constructor for homogeneous setups (power model provided separately). */
        public VmSpec(int id, int pesNumber, int mipsPerPe, int ramMb) {
            this(id, pesNumber, mipsPerPe, ramMb, 0, 0);
        }
        public double power(double utilisation) {
            return powerMaxWatts * (powerStaticFraction + (1.0 - powerStaticFraction) * utilisation);
        }
    }

    public record CloudletSpec(int id, long lengthMI, int pesNumber, int ramMb,
                               String affinityGroup, String antiAffinityGroup) {
        /** Execution time on a specific VM. PEs don't speed up execution in CloudSim. */
        public long execTimeOn(VmSpec vm) {
            return lengthMI / vm.mipsPerPe();
        }
    }

    public record Wave(List<CloudletSpec> cloudlets, double arrivalTime) {}

    /** Legacy power model spec for homogeneous setups. Applied to all VMs. */
    public record PowerModelSpec(double maxWatts, double staticFraction) {}

    public record TheoreticalBounds(
            double minTTC, double maxTTC,
            double minEnergy, double maxEnergy,
            double minConsolidation, double maxConsolidation,
            String details
    ) {
        @Override public String toString() {
            return String.format(
                    "TheoreticalBounds { TTC=[%.1f, %.1f], Energy=[%.2f, %.2f] Wh, Consolidation=[%.2f, %.2f] }",
                    minTTC, maxTTC, minEnergy, maxEnergy, minConsolidation, maxConsolidation);
        }
    }

    /**
     * Compute bounds. If VMs have powerMaxWatts == 0, the PowerModelSpec is applied
     * to all VMs (backward-compatible homogeneous mode).
     */
    public static TheoreticalBounds compute(List<VmSpec> vms, List<Wave> waves, PowerModelSpec power) {
        // If VMs don't have their own power model, apply the shared one
        List<VmSpec> resolvedVms = new ArrayList<>();
        for (VmSpec vm : vms) {
            if (vm.powerMaxWatts() == 0 && power != null) {
                resolvedVms.add(new VmSpec(vm.id(), vm.pesNumber(), vm.mipsPerPe(), vm.ramMb(),
                        power.maxWatts(), power.staticFraction()));
            } else {
                resolvedVms.add(vm);
            }
        }

        // Build wave task lists (without precomputed duration — depends on VM)
        List<List<CloudletSpec>> waveCloudlets = new ArrayList<>();
        List<Long> waveArrivals = new ArrayList<>();
        for (Wave wave : waves) {
            waveCloudlets.add(wave.cloudlets());
            waveArrivals.add((long) wave.arrivalTime());
        }

        // Collect affinity/anti-affinity
        List<CloudletSpec> allCloudlets = new ArrayList<>();
        waveCloudlets.forEach(allCloudlets::addAll);
        Map<String, List<Integer>> affinityGroups = new HashMap<>();
        Map<String, List<Integer>> antiAffinityGroups = new HashMap<>();
        for (int t = 0; t < allCloudlets.size(); t++) {
            CloudletSpec cl = allCloudlets.get(t);
            if (cl.affinityGroup() != null && !cl.affinityGroup().isEmpty())
                affinityGroups.computeIfAbsent(cl.affinityGroup(), k -> new ArrayList<>()).add(t);
            if (cl.antiAffinityGroup() != null && !cl.antiAffinityGroup().isEmpty())
                antiAffinityGroups.computeIfAbsent(cl.antiAffinityGroup(), k -> new ArrayList<>()).add(t);
        }

        // Solve with multiple wave-1 objectives to find true bounds.
        // Different objectives explore different regions of the placement space:
        // - SPREAD: even distribution across VMs (LeastAllocated behaviour)
        // - PACK_FAST: concentrate onto fewest VMs, preferring fastest (highest MIPS)
        // - PACK_EFFICIENT: concentrate onto fewest VMs, preferring most power-efficient
        SimResult spreadResult = solveWaveSequential(resolvedVms, waveCloudlets, waveArrivals, WaveObjective.SPREAD);
        SimResult packFastResult = solveWaveSequential(resolvedVms, waveCloudlets, waveArrivals, WaveObjective.PACK_FAST);
        SimResult packEfficientResult = solveWaveSequential(resolvedVms, waveCloudlets, waveArrivals, WaveObjective.PACK_EFFICIENT);

        // Compute all metrics for all placements
        List<SimResult> results = List.of(spreadResult, packFastResult, packEfficientResult);
        List<String> labels = List.of("Spread", "Pack-fast", "Pack-efficient");

        double minTTC = Long.MAX_VALUE, maxTTC = 0;
        double minE = Double.MAX_VALUE, maxE = 0;
        double minC = Double.MAX_VALUE, maxC = 0;
        StringBuilder details = new StringBuilder();

        for (int i = 0; i < results.size(); i++) {
            SimResult r = results.get(i);
            double e = computeEnergy(resolvedVms, allCloudlets, r);
            double c = computeConsolidation(resolvedVms, allCloudlets, r);
            minTTC = Math.min(minTTC, r.makespan); maxTTC = Math.max(maxTTC, r.makespan);
            minE = Math.min(minE, e); maxE = Math.max(maxE, e);
            minC = Math.min(minC, c); maxC = Math.max(maxC, c);
            details.append(formatPlacement(labels.get(i) + String.format(" (E=%.2fWh, C=%.2f)", e, c),
                    resolvedVms, allCloudlets, r)).append("\n");
        }

        return new TheoreticalBounds(minTTC, maxTTC, minE, maxE, minC, maxC, details.toString());
    }

    // ── Internal types ──────────────────────────────────────────────

    private record SimResult(long makespan, int[] vmAssignments, long[] startTimes, long[] durations) {}
    private enum WaveObjective { SPREAD, PACK_FAST, PACK_EFFICIENT }

    // ── Wave-sequential solver ──────────────────────────────────────

    private static SimResult solveWaveSequential(
            List<VmSpec> vms, List<List<CloudletSpec>> waveCloudlets,
            List<Long> waveArrivals, WaveObjective objective) {

        int totalTasks = waveCloudlets.stream().mapToInt(List::size).sum();
        int[] allAssignments = new int[totalTasks];
        long[] allStartTimes = new long[totalTasks];
        long[] allDurations = new long[totalTasks];

        @SuppressWarnings("unchecked")
        List<long[]>[] vmRunning = new List[vms.size()]; // [endTime, pes, ram]
        for (int v = 0; v < vms.size(); v++) vmRunning[v] = new ArrayList<>();

        int taskOffset = 0;
        for (int waveIdx = 0; waveIdx < waveCloudlets.size(); waveIdx++) {
            List<CloudletSpec> wave = waveCloudlets.get(waveIdx);
            long arrival = waveArrivals.get(waveIdx);
            if (wave.isEmpty()) continue;

            int[] waveAssignment;
            if (waveIdx == 0) {
                waveAssignment = solveWaveAssignment(vms, wave, vmRunning, arrival, objective);
            } else {
                boolean spread = (objective == WaveObjective.SPREAD);
                waveAssignment = greedyWaveAssignment(vms, wave, vmRunning, arrival, spread);
            }

            for (int w = 0; w < wave.size(); w++) {
                CloudletSpec cl = wave.get(w);
                int v = waveAssignment[w];
                long duration = cl.execTimeOn(vms.get(v));
                long earliest = findEarliestStart(cl, arrival, vms.get(v), vmRunning[v]);

                allAssignments[taskOffset + w] = v;
                allStartTimes[taskOffset + w] = earliest;
                allDurations[taskOffset + w] = duration;
                vmRunning[v].add(new long[]{earliest + duration, cl.pesNumber(), cl.ramMb()});
            }
            taskOffset += wave.size();
        }

        long makespan = 0;
        for (int t = 0; t < totalTasks; t++)
            makespan = Math.max(makespan, allStartTimes[t] + allDurations[t]);

        return new SimResult(makespan, allAssignments, allStartTimes, allDurations);
    }

    // ── CP-SAT wave assignment ──────────────────────────────────────

    private static int[] solveWaveAssignment(List<VmSpec> vms, List<CloudletSpec> wave,
                                              List<long[]>[] vmRunning, long arrival,
                                              WaveObjective objective) {
        CpModel model = new CpModel();
        int V = vms.size();
        int W = wave.size();

        IntVar[] assign = new IntVar[W];
        for (int w = 0; w < W; w++)
            assign[w] = model.newIntVar(0, V - 1, "assign_" + w);

        IntVar[] vmPesUsed = new IntVar[V];
        BoolVar[][] isOnVm = new BoolVar[W][V];

        for (int v = 0; v < V; v++) {
            int currentPes = 0, currentRam = 0;
            for (long[] running : vmRunning[v]) {
                if (running[0] > arrival) { currentPes += (int) running[1]; currentRam += (int) running[2]; }
            }
            int freePes = vms.get(v).pesNumber() - currentPes;
            int freeRam = vms.get(v).ramMb() - currentRam;

            LinearExprBuilder peSum = LinearExpr.newBuilder();
            LinearExprBuilder ramSum = LinearExpr.newBuilder();
            for (int w = 0; w < W; w++) {
                isOnVm[w][v] = model.newBoolVar("w" + w + "_v" + v);
                model.addEquality(assign[w], v).onlyEnforceIf(isOnVm[w][v]);
                model.addDifferent(assign[w], v).onlyEnforceIf(isOnVm[w][v].not());
                peSum.addTerm(isOnVm[w][v], wave.get(w).pesNumber());
                ramSum.addTerm(isOnVm[w][v], wave.get(w).ramMb());
            }

            vmPesUsed[v] = model.newIntVar(0, vms.get(v).pesNumber(), "vmPes_" + v);
            IntVar vmRamUsed = model.newIntVar(0, vms.get(v).ramMb(), "vmRam_" + v);
            model.addEquality(vmPesUsed[v], peSum.build());
            model.addEquality(vmRamUsed, ramSum.build());
            model.addLessOrEqual(vmPesUsed[v], freePes);
            model.addLessOrEqual(vmRamUsed, freeRam);
        }

        switch (objective) {
            case SPREAD -> {
                // Minimise max PEs used on any VM (even distribution)
                IntVar maxPes = model.newIntVar(0, vms.stream().mapToInt(VmSpec::pesNumber).max().orElse(1), "maxPes");
                for (int v = 0; v < V; v++) model.addGreaterOrEqual(maxPes, vmPesUsed[v]);
                model.minimize(maxPes);
            }
            case PACK_FAST -> {
                // Minimise VMs used, breaking ties by preferring fastest VMs (highest MIPS)
                // Objective: minimise (numUsed * bigM - sum of MIPS of used VMs)
                // This minimises VMs first, then among equal-VM-count solutions prefers faster VMs
                LinearExprBuilder obj = LinearExpr.newBuilder();
                long bigM = vms.stream().mapToInt(VmSpec::mipsPerPe).sum() + 1;
                for (int v = 0; v < V; v++) {
                    BoolVar used = model.newBoolVar("vmUsed_" + v);
                    model.addGreaterThan(vmPesUsed[v], 0).onlyEnforceIf(used);
                    model.addEquality(vmPesUsed[v], 0).onlyEnforceIf(used.not());
                    obj.addTerm(used, bigM);           // primary: fewer VMs
                    obj.addTerm(used, -vms.get(v).mipsPerPe()); // secondary: prefer fast VMs
                }
                model.minimize(obj.build());
            }
            case PACK_EFFICIENT -> {
                // Minimise VMs used, breaking ties by preferring most power-efficient VMs (lowest maxWatts)
                LinearExprBuilder obj = LinearExpr.newBuilder();
                long bigM = (long) vms.stream().mapToDouble(VmSpec::powerMaxWatts).sum() + 1;
                for (int v = 0; v < V; v++) {
                    BoolVar used = model.newBoolVar("vmUsed_" + v);
                    model.addGreaterThan(vmPesUsed[v], 0).onlyEnforceIf(used);
                    model.addEquality(vmPesUsed[v], 0).onlyEnforceIf(used.not());
                    obj.addTerm(used, bigM);           // primary: fewer VMs
                    obj.addTerm(used, (long) vms.get(v).powerMaxWatts()); // secondary: prefer efficient (low watts)
                }
                model.minimize(obj.build());
            }
        }

        CpSolver solver = new CpSolver();
        solver.getParameters().setMaxTimeInSeconds(10);
        CpSolverStatus status = solver.solve(model);
        if (status != CpSolverStatus.OPTIMAL && status != CpSolverStatus.FEASIBLE)
            throw new RuntimeException("Wave assignment failed: " + status);

        int[] result = new int[W];
        for (int w = 0; w < W; w++) result[w] = (int) solver.value(assign[w]);
        return result;
    }

    // ── Greedy wave assignment ──────────────────────────────────────

    private static int[] greedyWaveAssignment(List<VmSpec> vms, List<CloudletSpec> wave,
                                               List<long[]>[] vmRunning, long arrival,
                                               boolean spread) {
        int W = wave.size(), V = vms.size();
        int[] assignment = new int[W];

        @SuppressWarnings("unchecked")
        List<long[]>[] temp = new List[V];
        for (int v = 0; v < V; v++) temp[v] = new ArrayList<>(vmRunning[v]);

        for (int w = 0; w < W; w++) {
            CloudletSpec cl = wave.get(w);
            int bestVm = -1;
            long bestStart = Long.MAX_VALUE;
            int bestFree = spread ? -1 : Integer.MAX_VALUE;

            for (int v = 0; v < V; v++) {
                long start = findEarliestStart(cl, arrival, vms.get(v), temp[v]);
                final long s = start;
                int free = vms.get(v).pesNumber();
                for (long[] r : temp[v]) if (r[0] > s) free -= (int) r[1];

                boolean better = spread
                        ? (start < bestStart || (start == bestStart && free > bestFree))
                        : (start < bestStart || (start == bestStart && free < bestFree));
                if (better) { bestVm = v; bestStart = start; bestFree = free; }
            }

            assignment[w] = bestVm;
            long dur = cl.execTimeOn(vms.get(bestVm));
            temp[bestVm].add(new long[]{bestStart + dur, cl.pesNumber(), cl.ramMb()});
        }
        return assignment;
    }

    // ── Earliest start ──────────────────────────────────────────────

    private static long findEarliestStart(CloudletSpec cl, long arrival, VmSpec vm, List<long[]> running) {
        long earliest = arrival;
        while (true) {
            final long t = earliest;
            int usedPes = 0, usedRam = 0;
            for (long[] r : running) {
                if (r[0] > t) { usedPes += (int) r[1]; usedRam += (int) r[2]; }
            }
            if (usedPes + cl.pesNumber() <= vm.pesNumber() &&
                usedRam + cl.ramMb() <= vm.ramMb())
                return earliest;
            long next = running.stream().filter(r -> r[0] > t).mapToLong(r -> r[0]).min().orElse(t + 1);
            if (next <= earliest) next = earliest + 1;
            earliest = next;
        }
    }

    // ── Energy (per-VM power model) ─────────────────────────────────

    private static double computeEnergy(List<VmSpec> vms, List<CloudletSpec> tasks, SimResult p) {
        double totalWs = 0;
        for (int v = 0; v < vms.size(); v++) {
            VmSpec vm = vms.get(v);
            TreeMap<Long, Integer> events = new TreeMap<>();
            for (int t = 0; t < tasks.size(); t++) {
                if (p.vmAssignments[t] == v) {
                    events.merge(p.startTimes[t], tasks.get(t).pesNumber(), Integer::sum);
                    events.merge(p.startTimes[t] + p.durations[t], -tasks.get(t).pesNumber(), Integer::sum);
                }
            }
            if (events.isEmpty()) continue;
            int cur = 0; long prev = -1;
            for (var e : events.entrySet()) {
                if (prev >= 0 && cur > 0)
                    totalWs += vm.power((double) cur / vm.pesNumber()) * (e.getKey() - prev);
                cur += e.getValue();
                prev = e.getKey();
            }
        }
        return totalWs / 3600.0;
    }

    // ── Consolidation ───────────────────────────────────────────────

    private static double computeConsolidation(List<VmSpec> vms, List<CloudletSpec> tasks, SimResult p) {
        int V = vms.size();
        TreeMap<Long, int[]> events = new TreeMap<>();
        for (int t = 0; t < tasks.size(); t++) {
            int v = p.vmAssignments[t];
            events.computeIfAbsent(p.startTimes[t], k -> new int[V])[v]++;
            events.computeIfAbsent(p.startTimes[t] + p.durations[t], k -> new int[V])[v]--;
        }
        int[] active = new int[V];
        long prev = -1, start = -1;
        double area = 0;
        for (var e : events.entrySet()) {
            long time = e.getKey();
            if (prev >= 0 && time > prev) {
                int aVMs = 0, aCls = 0;
                for (int v = 0; v < V; v++) if (active[v] > 0) { aVMs++; aCls += active[v]; }
                if (aVMs > 0) area += ((double) aCls / aVMs) * (time - prev);
            }
            if (start < 0) start = time;
            for (int v = 0; v < V; v++) active[v] += e.getValue()[v];
            prev = time;
        }
        return (prev - start) > 0 ? area / (prev - start) : 0;
    }

    // ── Formatting ──────────────────────────────────────────────────

    private static String formatPlacement(String label, List<VmSpec> vms,
                                           List<CloudletSpec> tasks, SimResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%s placement (TTC=%d):\n", label, result.makespan));
        for (int v = 0; v < vms.size(); v++) {
            VmSpec vm = vms.get(v);
            List<String> descs = new ArrayList<>();
            for (int t = 0; t < tasks.size(); t++) {
                if (result.vmAssignments[t] == v) {
                    CloudletSpec cl = tasks.get(t);
                    descs.add(String.format("  cl-%d (%dPE, %dMI, dur=%d): t=[%d, %d]",
                            cl.id(), cl.pesNumber(), cl.lengthMI(), result.durations[t],
                            result.startTimes[t], result.startTimes[t] + result.durations[t]));
                }
            }
            if (descs.isEmpty()) {
                sb.append(String.format("  VM %d (%dPE, %dMIPS, %.0fW): idle\n",
                        v, vm.pesNumber(), vm.mipsPerPe(), vm.powerMaxWatts()));
            } else {
                sb.append(String.format("  VM %d (%dPE, %dMIPS, %.0fW): %d tasks\n",
                        v, vm.pesNumber(), vm.mipsPerPe(), vm.powerMaxWatts(), descs.size()));
                descs.forEach(d -> sb.append(d).append("\n"));
            }
        }
        return sb.toString();
    }
}
