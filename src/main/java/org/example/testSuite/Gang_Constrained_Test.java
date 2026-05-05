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
 * Gang Scheduling — Capacity Constrained Test.
 *
 * Demonstrates the difference between Volcano and kube-scheduler when a gang
 * cannot fit entirely in the cluster simultaneously.
 *
 * Setup: 2 VMs × 4 PEs = 8 PE slots
 * Gang A: 6 pods × 2 PEs = 12 PEs needed (exceeds 8 PE capacity)
 *
 * Expected behaviour:
 * - Volcano (gang plugin): refuses to schedule ANY pod (all-or-nothing). All 6 are
 *   unschedulable. The gang can never run → marked FAILED.
 * - kube-scheduler + broker gang logic: places 4 pods immediately (fills cluster),
 *   holds them in the gang waiting room. Remaining 2 can never be placed because
 *   the first 4 consume all capacity → deadlock detected → marked FAILED.
 *
 * This is the key differentiator: Volcano fails fast (immediate feedback), while
 * kube-scheduler creates a deadlock that must be detected by timeout/heuristic.
 */
public class Gang_Constrained_Test {

    public static void main(String[] args) throws Exception {
        CloudSim.init(2, Calendar.getInstance(), false);

        int numHosts = 2;
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

        // 2 VMs (one per host)
        List<Vm> vmList = new ArrayList<>();
        for (int i = 0; i < numHosts; i++) {
            vmList.add(new PowerVmCustom(i, brokerId, mips, numPes, ram, bw, storage,
                    0, "Xen", new CloudletSchedulerTimeShared(), 1, i));
        }
        broker.submitGuestList(vmList);

        // Gang A: 6 pods × 2 PEs — needs 12 PEs but cluster only has 8
        List<Cloudlet> cloudlets = new ArrayList<>();
        UtilizationModel full = new UtilizationModelFull();
        for (int i = 0; i < 6; i++) {
            cloudlets.add(new CoubesCloudlet(i, 40000, 2, 300, 300,
                    full, full, full, 0, Collections.emptyMap(),
                    null, null, true, true, "big-gang"));
        }
        broker.submitCloudletList(cloudlets);

        CloudSim.resumeSimulation();
        SimulationMetrics metrics = new SimulationMetrics(dc, vmList);
        metrics.startWallClock();
        double lastClock = CloudSim.startSimulation();
        CloudSim.stopSimulation();
        metrics.stopWallClock();

        List<Cloudlet> results = broker.getCloudletReceivedList();
        Log.printLine("========== Gang_Constrained_Test Results ==========");
        int succeeded = 0, failed = 0;
        for (Cloudlet cl : results) {
            Log.printlnConcat("    Cloudlet ", cl.getCloudletId(), ": ", cl.getStatus());
            if (cl.getStatus() == Cloudlet.CloudletStatus.SUCCESS) succeeded++;
            else failed++;
        }
        Log.printlnConcat("\nTotal: ", succeeded, " succeeded, ", failed, " failed");
        Log.printlnConcat("Wall-clock Time Elapsed: ", metrics.getWallClockMillis(), " ms");

        if (failed == 6) {
            Log.printLine("EXPECTED: All 6 gang members failed (gang too large for cluster).");
        } else if (succeeded == 6) {
            Log.printLine("UNEXPECTED: All 6 succeeded — this shouldn't happen with 8 PE slots.");
        } else {
            Log.printLine("PARTIAL: " + succeeded + " succeeded, " + failed + " failed.");
        }

        try { broker.sendResetRequestToControlPlane(); } catch (Exception ignored) {}
        Log.printLine("\nGang_Constrained_Test finished!");
    }
}
