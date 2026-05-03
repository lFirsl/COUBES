package org.example.metrics;

import com.google.ortools.Loader;
import com.google.ortools.sat.*;

import java.util.*;

/**
 * Computes theoretical min/max bounds for TTC, energy, and consolidation
 * using Google OR-Tools CP-SAT solver.
 *
 * Key design: wave-sequential scheduling. The solver decides wave-1 placement
 * WITHOUT knowledge of future waves (mimicking a real scheduler). Later waves
 * are then placed greedily on whatever capacity remains. This captures the
 * fragmentation effect: a bad wave-1 placement can leave no room for wave-2.
 *
 * Execution time: cloudletLength / mipsPerPe (PEs affect resource consumption,
 * not speed — CloudSim's getCloudletTotalLength = length * pes).
 */
public class BoundsCalculator {

    static { Loader.loadNativeLibraries(); }

    public record VmSpec(int id, int pesNumber, int mipsPerPe, int ramMb) {}

    public record CloudletSpec(int id, long lengthMI, int pesNumber, int ramMb,
                               String affinityGroup, String antiAffinityGroup) {
        public long execTime(int mipsPerPe) {
            return lengthMI / mipsPerPe;
        }
    }

    public record Wave(List<CloudletSpec> cloudlets, double arrivalTime) {}

    public record PowerModelSpec(double maxWatts, double staticFraction) {
        public double power(double utilisation) {
            return maxWatts * (staticFraction + (1.0 - staticFraction) * utilisation);
        }
    }

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

    public static TheoreticalBounds compute(List<VmSpec> vms, List<Wave> waves, PowerModelSpec power) {
        int mipsPerPe = vms.get(0).mipsPerPe();

        // Convert waves to task lists
        List<List<TaskInfo>> waveTasks = new ArrayList<>();
        for (Wave wave : waves) {
            List<TaskInfo> tasks = new ArrayList<>();
            for (CloudletSpec cl : wave.cloudlets())
                tasks.add(new TaskInfo(cl, (long) wave.arrivalTime(), cl.execTime(mipsPerPe)));
            waveTasks.add(tasks);
        }

        // Collect affinity/anti-affinity across all tasks
        List<TaskInfo> allTasks = new ArrayList<>();
        waveTasks.forEach(allTasks::addAll);
        Map<String, List<Integer>> affinityGroups = new HashMap<>();
        Map<String, List<Integer>> antiAffinityGroups = new HashMap<>();
        for (int t = 0; t < allTasks.size(); t++) {
            TaskInfo ti = allTasks.get(t);
            if (ti.spec.affinityGroup() != null && !ti.spec.affinityGroup().isEmpty())
                affinityGroups.computeIfAbsent(ti.spec.affinityGroup(), k -> new ArrayList<>()).add(t);
            if (ti.spec.antiAffinityGroup() != null && !ti.spec.antiAffinityGroup().isEmpty())
                antiAffinityGroups.computeIfAbsent(ti.spec.antiAffinityGroup(), k -> new ArrayList<>()).add(t);
        }

        // Solve with different wave-1 objectives:
        // - SPREAD: even distribution (best for TTC, worst for consolidation)
        // - PACK: concentrate onto fewest VMs (worst for TTC, best for consolidation)
        SimResult spreadResult = solveWaveSequential(vms, waveTasks, affinityGroups, antiAffinityGroups, WaveObjective.SPREAD);
        SimResult packResult = solveWaveSequential(vms, waveTasks, affinityGroups, antiAffinityGroups, WaveObjective.PACK);

        // Compute all metrics for both placements
        double eSpr = computeEnergy(vms, allTasks, spreadResult, power);
        double ePck = computeEnergy(vms, allTasks, packResult, power);
        double cSpr = computeConsolidation(vms, allTasks, spreadResult);
        double cPck = computeConsolidation(vms, allTasks, packResult);

        return new TheoreticalBounds(
                Math.min(spreadResult.makespan, packResult.makespan),
                Math.max(spreadResult.makespan, packResult.makespan),
                Math.min(eSpr, ePck), Math.max(eSpr, ePck),
                Math.min(cSpr, cPck), Math.max(cSpr, cPck),
                formatPlacementDetails("Best-case (spread)", vms, allTasks, spreadResult) + "\n" +
                formatPlacementDetails("Worst-case (packed)", vms, allTasks, packResult));
    }

    // ── Internal types ──────────────────────────────────────────────

    private record TaskInfo(CloudletSpec spec, long arrival, long duration) {}
    private record SimResult(long makespan, int[] vmAssignments, long[] startTimes) {}

    private enum WaveObjective { SPREAD, PACK }
    // ── Wave-sequential solver ──────────────────────────────────────

    /**
     * Solve wave-by-wave: use CP-SAT for wave-1 assignment (spread or pack),
     * then place all subsequent waves greedily one task at a time on whatever
     * VM has capacity. This mirrors real scheduler behaviour — the scheduler
     * only optimises the current batch, not future ones.
     */
    private static SimResult solveWaveSequential(
            List<VmSpec> vms, List<List<TaskInfo>> waveTasks,
            Map<String, List<Integer>> affinityGroups,
            Map<String, List<Integer>> antiAffinityGroups,
            WaveObjective objective) {

        int totalTasks = waveTasks.stream().mapToInt(List::size).sum();
        int[] allAssignments = new int[totalTasks];
        long[] allStartTimes = new long[totalTasks];

        // Track running tasks per VM: list of [endTime, pesUsed, ramUsed]
        @SuppressWarnings("unchecked")
        List<long[]>[] vmRunning = new List[vms.size()];
        for (int v = 0; v < vms.size(); v++) vmRunning[v] = new ArrayList<>();

        int taskOffset = 0;
        for (int waveIdx = 0; waveIdx < waveTasks.size(); waveIdx++) {
            List<TaskInfo> wave = waveTasks.get(waveIdx);
            int W = wave.size();
            if (W == 0) { continue; }

            int[] waveAssignment;
            if (waveIdx == 0) {
                // Use CP-SAT for wave-1: spread or pack
                waveAssignment = solveWaveAssignment(vms, wave, vmRunning, objective == WaveObjective.SPREAD);
            } else {
                // Greedy for subsequent waves
                waveAssignment = greedyWaveAssignment(vms, wave, vmRunning, objective == WaveObjective.SPREAD);
            }

            // Compute start times
            for (int w = 0; w < W; w++) {
                TaskInfo task = wave.get(w);
                int v = waveAssignment[w];
                long earliest = findEarliestStart(task, vms.get(v), vmRunning[v]);

                allAssignments[taskOffset + w] = v;
                allStartTimes[taskOffset + w] = earliest;
                vmRunning[v].add(new long[]{earliest + task.duration, task.spec.pesNumber(), task.spec.ramMb()});
            }
            taskOffset += W;
        }

        long makespan = 0;
        List<TaskInfo> allTasks = new ArrayList<>();
        waveTasks.forEach(allTasks::addAll);
        for (int t = 0; t < totalTasks; t++)
            makespan = Math.max(makespan, allStartTimes[t] + allTasks.get(t).duration);

        return new SimResult(makespan, allAssignments, allStartTimes);
    }

    /**
     * Use CP-SAT to assign this wave's tasks to VMs, considering current
     * VM occupancy. The solver does NOT see future waves.
     *
     * For minimiseTTC=true: spread tasks (maximise minimum free PEs across VMs)
     * For minimiseTTC=false: pack tasks (minimise number of VMs used)
     */
    private static int[] solveWaveAssignment(List<VmSpec> vms, List<TaskInfo> wave,
                                              List<long[]>[] vmRunning, boolean spread) {
        CpModel model = new CpModel();
        int V = vms.size();
        int W = wave.size();

        IntVar[] assign = new IntVar[W];
        for (int w = 0; w < W; w++)
            assign[w] = model.newIntVar(0, V - 1, "assign_" + w);

        // Per-VM: count PEs and RAM assigned from this wave
        IntVar[] vmPesUsed = new IntVar[V];
        IntVar[] vmRamUsed = new IntVar[V];

        for (int v = 0; v < V; v++) {
            // Current occupancy at wave arrival time
            long waveArrival = wave.get(0).arrival;
            int currentPes = 0, currentRam = 0;
            for (long[] running : vmRunning[v]) {
                if (running[0] > waveArrival) { // still running at wave arrival
                    currentPes += (int) running[1];
                    currentRam += (int) running[2];
                }
            }
            int freePes = vms.get(v).pesNumber() - currentPes;
            int freeRam = vms.get(v).ramMb() - currentRam;

            // Sum PEs and RAM from tasks assigned to this VM
            LinearExprBuilder peSum = LinearExpr.newBuilder();
            LinearExprBuilder ramSum = LinearExpr.newBuilder();
            for (int w = 0; w < W; w++) {
                BoolVar isOn = model.newBoolVar("w" + w + "_v" + v);
                model.addEquality(assign[w], v).onlyEnforceIf(isOn);
                model.addDifferent(assign[w], v).onlyEnforceIf(isOn.not());
                peSum.addTerm(isOn, wave.get(w).spec.pesNumber());
                ramSum.addTerm(isOn, wave.get(w).spec.ramMb());
            }

            vmPesUsed[v] = model.newIntVar(0, vms.get(v).pesNumber(), "vmPes_" + v);
            vmRamUsed[v] = model.newIntVar(0, vms.get(v).ramMb(), "vmRam_" + v);
            model.addEquality(vmPesUsed[v], peSum.build());
            model.addEquality(vmRamUsed[v], ramSum.build());

            // Capacity constraints: new tasks must fit in free space
            model.addLessOrEqual(vmPesUsed[v], freePes);
            model.addLessOrEqual(vmRamUsed[v], freeRam);
        }

        // Objective
        if (spread) {
            // Spread: minimise the maximum PEs used on any VM (even distribution)
            IntVar maxPes = model.newIntVar(0, vms.stream().mapToInt(VmSpec::pesNumber).max().orElse(1), "maxPes");
            for (int v = 0; v < V; v++)
                model.addGreaterOrEqual(maxPes, vmPesUsed[v]);
            model.minimize(maxPes);
        } else {
            // Pack: minimise number of VMs used (concentrate onto fewest VMs)
            LinearExprBuilder numUsed = LinearExpr.newBuilder();
            for (int v = 0; v < V; v++) {
                BoolVar vmHasTasks = model.newBoolVar("vmUsed_" + v);
                // vmHasTasks = 1 iff vmPesUsed[v] > 0
                model.addGreaterThan(vmPesUsed[v], 0).onlyEnforceIf(vmHasTasks);
                model.addEquality(vmPesUsed[v], 0).onlyEnforceIf(vmHasTasks.not());
                numUsed.addTerm(vmHasTasks, 1);
            }
            model.minimize(numUsed.build());
        }

        CpSolver solver = new CpSolver();
        solver.getParameters().setMaxTimeInSeconds(10);
        CpSolverStatus status = solver.solve(model);

        if (status != CpSolverStatus.OPTIMAL && status != CpSolverStatus.FEASIBLE)
            throw new RuntimeException("Wave assignment failed: " + status);

        int[] result = new int[W];
        for (int w = 0; w < W; w++)
            result[w] = (int) solver.value(assign[w]);
        return result;
    }

    /**
     * Greedy assignment for later waves. For each task, find the VM where it
     * can start earliest. For min TTC, prefer VMs with most free PEs (spread).
     * For max TTC, prefer VMs with least free PEs that still fit (pack).
     */
    private static int[] greedyWaveAssignment(List<VmSpec> vms, List<TaskInfo> wave,
                                               List<long[]>[] vmRunning, boolean spread) {
        int W = wave.size();
        int V = vms.size();
        int[] assignment = new int[W];

        // Temporary copy of vmRunning so we can track assignments within this wave
        @SuppressWarnings("unchecked")
        List<long[]>[] tempRunning = new List[V];
        for (int v = 0; v < V; v++) tempRunning[v] = new ArrayList<>(vmRunning[v]);

        for (int w = 0; w < W; w++) {
            TaskInfo task = wave.get(w);
            int bestVm = -1;
            long bestStart = Long.MAX_VALUE;
            int bestFreePes = spread ? -1 : Integer.MAX_VALUE;

            for (int v = 0; v < V; v++) {
                long start = findEarliestStart(task, vms.get(v), tempRunning[v]);
                int freePes = vms.get(v).pesNumber();
                final long s = start;
                for (long[] r : tempRunning[v])
                    if (r[0] > s) freePes -= (int) r[1];

                if (spread) {
                    // Prefer earliest start, then most free PEs
                    if (start < bestStart || (start == bestStart && freePes > bestFreePes)) {
                        bestVm = v; bestStart = start; bestFreePes = freePes;
                    }
                } else {
                    // Prefer earliest start, then least free PEs (pack)
                    if (start < bestStart || (start == bestStart && freePes < bestFreePes)) {
                        bestVm = v; bestStart = start; bestFreePes = freePes;
                    }
                }
            }

            assignment[w] = bestVm;
            tempRunning[bestVm].add(new long[]{bestStart + task.duration, task.spec.pesNumber(), task.spec.ramMb()});
        }
        return assignment;
    }

    /**
     * Find the earliest time a task can start on a VM, given currently running tasks.
     */
    private static long findEarliestStart(TaskInfo task, VmSpec vm, List<long[]> running) {
        long earliest = task.arrival;
        while (true) {
            final long t = earliest;
            int usedPes = 0, usedRam = 0;
            for (long[] r : running) {
                if (r[0] > t) { usedPes += (int) r[1]; usedRam += (int) r[2]; }
            }
            if (usedPes + task.spec.pesNumber() <= vm.pesNumber() &&
                usedRam + task.spec.ramMb() <= vm.ramMb())
                return earliest;

            // Jump to next task completion
            long next = running.stream().filter(r -> r[0] > t).mapToLong(r -> r[0]).min().orElse(t + 1);
            if (next <= earliest) next = earliest + 1;
            earliest = next;
        }
    }

    // ── Energy from placement ───────────────────────────────────────

    private static double computeEnergy(List<VmSpec> vms, List<TaskInfo> tasks,
                                         SimResult p, PowerModelSpec power) {
        double totalWs = 0;
        for (int v = 0; v < vms.size(); v++) {
            TreeMap<Long, Integer> events = new TreeMap<>();
            for (int t = 0; t < tasks.size(); t++) {
                if (p.vmAssignments[t] == v) {
                    events.merge(p.startTimes[t], tasks.get(t).spec.pesNumber(), Integer::sum);
                    events.merge(p.startTimes[t] + tasks.get(t).duration, -tasks.get(t).spec.pesNumber(), Integer::sum);
                }
            }
            if (events.isEmpty()) continue;
            int totalPes = vms.get(v).pesNumber();
            int cur = 0; long prev = -1;
            for (var e : events.entrySet()) {
                if (prev >= 0 && cur > 0)
                    totalWs += power.power((double) cur / totalPes) * (e.getKey() - prev);
                cur += e.getValue();
                prev = e.getKey();
            }
        }
        return totalWs / 3600.0;
    }

    // ── Consolidation from placement ────────────────────────────────

    private static double computeConsolidation(List<VmSpec> vms, List<TaskInfo> tasks,
                                                SimResult p) {
        int V = vms.size();
        TreeMap<Long, int[]> events = new TreeMap<>();
        for (int t = 0; t < tasks.size(); t++) {
            int v = p.vmAssignments[t];
            events.computeIfAbsent(p.startTimes[t], k -> new int[V])[v]++;
            events.computeIfAbsent(p.startTimes[t] + tasks.get(t).duration, k -> new int[V])[v]--;
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

    // ── Helpers ─────────────────────────────────────────────────────

    private static int[] countPerVm(int numVms, int[] assigns) {
        int[] c = new int[numVms];
        for (int a : assigns) c[a]++;
        return c;
    }

    /**
     * Format a human-readable placement description showing per-VM task
     * assignments with start/end times.
     */
    private static String formatPlacementDetails(String label, List<VmSpec> vms,
                                                  List<TaskInfo> allTasks, SimResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%s placement (TTC=%d):\n", label, result.makespan));
        for (int v = 0; v < vms.size(); v++) {
            List<String> taskDescs = new ArrayList<>();
            for (int t = 0; t < allTasks.size(); t++) {
                if (result.vmAssignments[t] == v) {
                    TaskInfo ti = allTasks.get(t);
                    taskDescs.add(String.format("  cl-%d (%dPE, %dMI): t=[%d, %d]",
                            ti.spec.id(), ti.spec.pesNumber(), ti.spec.lengthMI(),
                            result.startTimes[t], result.startTimes[t] + ti.duration));
                }
            }
            if (taskDescs.isEmpty()) {
                sb.append(String.format("  VM %d (%dPE): idle\n", v, vms.get(v).pesNumber()));
            } else {
                sb.append(String.format("  VM %d (%dPE): %d tasks\n", v, vms.get(v).pesNumber(), taskDescs.size()));
                taskDescs.forEach(d -> sb.append(d).append("\n"));
            }
        }
        return sb.toString();
    }
}
