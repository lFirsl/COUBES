package org.example.testSuite;

import org.cloudbus.cloudsim.*;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.power.PowerHost;
import org.cloudbus.cloudsim.power.models.PowerModelLinear;
import org.cloudbus.cloudsim.provisioners.BwProvisionerSimple;
import org.cloudbus.cloudsim.provisioners.PeProvisionerSimple;
import org.cloudbus.cloudsim.provisioners.RamProvisionerSimple;
import org.example.kubernetes_broker.*;
import org.example.metrics.SimulationMetrics;

import java.util.*;

/**
 * Gang Scheduling Test — demonstrates all-or-nothing scheduling semantics.
 *
 * Setup: 4 VMs × 4 PEs = 16 PE slots
 * Gang A: 4 pods × 2 PEs = 8 PEs (gangId="A")
 * Gang B: 4 pods × 2 PEs = 8 PEs (gangId="B")
 *
 * Both gangs are submitted simultaneously. The cluster has enough capacity for both.
 * With Volcano (gang plugin): both gangs are scheduled atomically — all 4 members
 * of each gang are placed together.
 * With kube-scheduler + broker gang logic: same behaviour (broker holds partial gangs).
 *
 * The key difference shows when capacity is tight:
 * Gang C: 4 pods × 4 PEs = 16 PEs (submitted as wave 2 after A completes)
 * Gang C fills the entire cluster — Volcano ensures all 4 are placed together.
 */
public class Gang_Scheduling_Test {

    public static void main(String[] args) throws Exception {
        CloudSim.init(2, Calendar.getInstance(), false);

        // 4 hosts × 1 VM each, 4 PEs per host at 250 MIPS
        int numHosts = 4;
        int numPes = 4;
        int mips = 250;
        int ram = 2048;
        long bw = 10000;
        long storage = 100000;

        List<Host> hostList = new ArrayList<>();
        for (int i = 0; i < numHosts; i++) {
            List<Pe> peList = new ArrayList<>();
            for (int p = 0; p < numPes; p++) {
                peList.add(new Pe(p, new PeProvisionerSimple(mips)));
            }
            hostList.add(new PowerHost(i,
                    new RamProvisionerSimple(ram),
                    new BwProvisionerSimple(bw),
                    storage, peList,
                    new VmSchedulerTimeShared(peList),
                    new PowerModelLinear(500, 0.1)));
        }

        DatacenterCharacteristics chars = new DatacenterCharacteristics(
                "x86", "Linux", "Xen", hostList, 10.0, 3.0, 0.05, 0.1, 0.1);
        PowerDatacenterCustom dc = new PowerDatacenterCustom(
                "Datacenter_0", chars, new VmAllocationPolicySimple(hostList),
                new LinkedList<>(), 1, true);
        dc.setDisableMigrations(true);

        Live_Kubernetes_Broker_Ex broker = new Live_Kubernetes_Broker_Ex("Broker_0");
        int brokerId = broker.getId();

        // Create 4 VMs (one per host)
        List<Vm> vmList = new ArrayList<>();
        for (int i = 0; i < numHosts; i++) {
            vmList.add(new PowerVmCustom(i, brokerId, mips, numPes, ram, bw, storage,
                    0, "Xen", new CloudletSchedulerTimeShared(), 1, i));
        }
        broker.submitGuestList(vmList);

        // Wave 1: Gang A (4 pods × 2 PEs) + Gang B (4 pods × 2 PEs)
        List<Cloudlet> wave1 = new ArrayList<>();
        UtilizationModel full = new UtilizationModelFull();

        // Gang A: cloudlets 0-3
        for (int i = 0; i < 4; i++) {
            wave1.add(new CoubesCloudlet(i, 40000, 2, 300, 300,
                    full, full, full, 0, Collections.emptyMap(),
                    null, null, true, true, "A"));
        }
        // Gang B: cloudlets 4-7
        for (int i = 4; i < 8; i++) {
            wave1.add(new CoubesCloudlet(i, 40000, 2, 300, 300,
                    full, full, full, 0, Collections.emptyMap(),
                    null, null, true, true, "B"));
        }
        broker.submitCloudletList(wave1);

        // Wave 2: Gang C (4 pods × 4 PEs) — needs full cluster, arrives after wave 1
        List<Cloudlet> wave2 = new ArrayList<>();
        for (int i = 100; i < 104; i++) {
            wave2.add(new CoubesCloudlet(i, 80000, 4, 300, 300,
                    full, full, full, 0, Collections.emptyMap(),
                    null, null, true, true, "C"));
        }
        broker.submitCloudletList(wave2, 200); // arrives at t=200

        CloudSim.resumeSimulation();
        SimulationMetrics metrics = new SimulationMetrics(dc, vmList);
        metrics.startWallClock();
        double lastClock = CloudSim.startSimulation();
        CloudSim.stopSimulation();

        // Print results
        List<Cloudlet> results = broker.getCloudletReceivedList();
        Log.printLine("========== Gang_Scheduling_Test Results ==========");
        Log.printLine("Cloudlet ID    Status    VM    PEs    Length    Start    Finish");
        results.sort(Comparator.comparingInt(Cloudlet::getCloudletId));
        for (Cloudlet cl : results) {
            Log.printlnConcat("    ", cl.getCloudletId(), "        ",
                    cl.getStatus(), "    ", cl.getGuestId(),
                    "    ", cl.getNumberOfPes(),
                    "    ", cl.getCloudletLength(),
                    "    ", cl.getExecStartTime(),
                    "    ", cl.getFinishTime());
        }

        metrics.stopWallClock();
        metrics.printSummary(lastClock, broker.tpOverall());

        // Verify gang atomicity: all members of each gang should have the same start time
        Map<String, List<Double>> gangStartTimes = new HashMap<>();
        for (Cloudlet cl : results) {
            if (cl instanceof CoubesCloudlet cc && cc.getGangId() != null) {
                gangStartTimes.computeIfAbsent(cc.getGangId(), k -> new ArrayList<>())
                        .add(cl.getExecStartTime());
            }
        }
        Log.printLine("\n----- Gang Atomicity Check -----");
        for (var entry : gangStartTimes.entrySet()) {
            List<Double> starts = entry.getValue();
            double min = starts.stream().mapToDouble(d -> d).min().orElse(0);
            double max = starts.stream().mapToDouble(d -> d).max().orElse(0);
            boolean atomic = (max - min) < 1.0; // all started within 1 simulated second
            Log.printlnConcat("Gang '", entry.getKey(), "': ", starts.size(), " members, ",
                    "start range [", String.format("%.1f", min), " - ", String.format("%.1f", max), "] → ",
                    atomic ? "ATOMIC ✓" : "NOT ATOMIC ✗");
        }

        try { broker.sendResetRequestToControlPlane(); } catch (Exception ignored) {}
        Log.printLine("\nGang_Scheduling_Test finished!");
    }
}
