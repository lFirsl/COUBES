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
 * Overload Comparison Test — large heterogeneous workload with mixed scheduling demands.
 *
 * Infrastructure: 5 heterogeneous VMs
 *   - 2 power-efficient: 4 PEs @ 200 MIPS, PowerModel(300W, 5% static)
 *   - 2 standard:        4 PEs @ 250 MIPS, PowerModel(500W, 10% static)
 *   - 1 fast/expensive:  8 PEs @ 400 MIPS, PowerModel(800W, 15% static)
 *   Total: 24 PE slots
 *
 * Workload: 71 pods across 3 waves
 *   Wave 1 (t=0):   37 pods
 *     - 20 high-priority individual (2 PEs, length=30000)
 *     - 10 batch individual (2 PEs, length=50000)
 *     - Gang "compute-A": 3 pods × 2 PEs, high-priority (length=40000)
 *     - Gang "batch-job": 4 pods × 2 PEs, batch (length=60000)
 *   Wave 2 (t=50):  18 pods
 *     - 15 high-priority individual (2 PEs, length=20000)
 *     - Gang "compute-B": 3 pods × 2 PEs, high-priority (length=30000)
 *   Wave 3 (t=100): 15 pods
 *     - 5 high-priority individual (1 PE, length=10000)
 *     - 10 batch individual (2 PEs, length=40000)
 *
 * Key observations:
 *   - Volcano: proportion plugin guarantees batch access; gang plugin holds gangs atomically
 *   - kube-scheduler: greedy placement, batch starved, gang held by broker logic
 */
public class Overload_Comparison_Test_Higher_Static {

    public static void main(String[] args) throws Exception {
        CloudSim.init(2, Calendar.getInstance(), false);

        // 5 hosts (one VM per host)
        List<Host> hostList = new ArrayList<>();
        int[][] hostSpecs = {
            // {numPes, mips, maxWatts, staticPercent*100}
            {4, 200, 300, 30},   // efficient-0
            {4, 200, 300, 30},   // efficient-1
            {4, 250, 500, 30},  // standard-0
            {4, 250, 500, 30},  // standard-1
            {8, 400, 800, 30},  // fast-0
        };

        for (int i = 0; i < hostSpecs.length; i++) {
            int pes = hostSpecs[i][0];
            int mips = hostSpecs[i][1];
            int watts = hostSpecs[i][2];
            double staticFrac = hostSpecs[i][3] / 100.0;

            List<Pe> peList = new ArrayList<>();
            for (int p = 0; p < pes; p++) {
                peList.add(new Pe(p, new PeProvisionerSimple(mips)));
            }
            hostList.add(new PowerHost(i,
                    new RamProvisionerSimple(4096), new BwProvisionerSimple(10000),
                    100000, peList, new VmSchedulerTimeShared(peList),
                    new PowerModelLinear(watts, staticFrac)));
        }

        DatacenterCharacteristics chars = new DatacenterCharacteristics(
                "x86", "Linux", "Xen", hostList, 10.0, 3.0, 0.05, 0.1, 0.1);
        PowerDatacenterCustom dc = new PowerDatacenterCustom(
                "Datacenter_0", chars, new VmAllocationPolicySimple(hostList),
                new LinkedList<>(), 1, true);
        dc.setDisableMigrations(true);

        Live_Kubernetes_Broker_Ex broker = new Live_Kubernetes_Broker_Ex("Broker_0");
        int brokerId = broker.getId();

        // Create VMs matching hosts
        List<Vm> vmList = new ArrayList<>();
        for (int i = 0; i < hostSpecs.length; i++) {
            vmList.add(new PowerVmCustom(i, brokerId, hostSpecs[i][1], hostSpecs[i][0],
                    4096, 10000, 100000, 0, "Xen",
                    new CloudletSchedulerTimeShared(), 1, i));
        }
        broker.submitGuestList(vmList);

        UtilizationModel full = new UtilizationModelFull();
        int id = 0;

        // === Wave 1 (t=0): 37 pods ===
        List<Cloudlet> wave1 = new ArrayList<>();

        // 20 high-priority individual (2 PEs, length=30000)
        for (int i = 0; i < 20; i++) {
            CoubesCloudlet cl = new CoubesCloudlet(id++, 30000, 2, 300, 300, full, full, full, 0);
            cl.setClassType(1);
            wave1.add(cl);
        }
        // 10 batch individual (2 PEs, length=50000)
        for (int i = 0; i < 10; i++) {
            CoubesCloudlet cl = new CoubesCloudlet(id++, 50000, 2, 300, 300, full, full, full, 0);
            cl.setClassType(2);
            wave1.add(cl);
        }
        // Gang "compute-A": 3 pods × 2 PEs, high-priority (length=40000)
        for (int i = 0; i < 3; i++) {
            CoubesCloudlet cl = new CoubesCloudlet(id++, 40000, 2, 300, 300,
                    full, full, full, 0, Collections.emptyMap(), null, null, true, true, "compute-A");
            cl.setClassType(1);
            wave1.add(cl);
        }
        // Gang "batch-job": 4 pods × 2 PEs, batch (length=60000)
        for (int i = 0; i < 4; i++) {
            CoubesCloudlet cl = new CoubesCloudlet(id++, 60000, 2, 300, 300,
                    full, full, full, 0, Collections.emptyMap(), null, null, true, true, "batch-job");
            cl.setClassType(2);
            wave1.add(cl);
        }
        broker.submitCloudletList(wave1);

        // === Wave 2 (t=50): 18 pods ===
        List<Cloudlet> wave2 = new ArrayList<>();
        // 15 high-priority individual (2 PEs, length=20000)
        for (int i = 0; i < 15; i++) {
            CoubesCloudlet cl = new CoubesCloudlet(id++, 20000, 2, 300, 300, full, full, full, 0);
            cl.setClassType(1);
            wave2.add(cl);
        }
        // Gang "compute-B": 3 pods × 2 PEs, high-priority (length=30000)
        for (int i = 0; i < 3; i++) {
            CoubesCloudlet cl = new CoubesCloudlet(id++, 30000, 2, 300, 300,
                    full, full, full, 0, Collections.emptyMap(), null, null, true, true, "compute-B");
            cl.setClassType(1);
            wave2.add(cl);
        }
        broker.submitCloudletList(wave2, 50);

        // === Wave 3 (t=100): 15 pods ===
        List<Cloudlet> wave3 = new ArrayList<>();
        // 5 high-priority individual (1 PE, length=10000)
        for (int i = 0; i < 5; i++) {
            CoubesCloudlet cl = new CoubesCloudlet(id++, 10000, 1, 300, 300, full, full, full, 0);
            cl.setClassType(1);
            wave3.add(cl);
        }
        // 10 batch individual (2 PEs, length=40000)
        for (int i = 0; i < 10; i++) {
            CoubesCloudlet cl = new CoubesCloudlet(id++, 40000, 2, 300, 300, full, full, full, 0);
            cl.setClassType(2);
            wave3.add(cl);
        }
        broker.submitCloudletList(wave3, 100);

        // === Run ===
        CloudSim.resumeSimulation();
        SimulationMetrics metrics = new SimulationMetrics(dc, vmList);
        metrics.startWallClock();
        double lastClock = CloudSim.startSimulation();
        CloudSim.stopSimulation();
        metrics.stopWallClock();

        // === Results ===
        List<Cloudlet> results = broker.getCloudletReceivedList();
        Log.printLine("========== Overload_Comparison_Test_Higher_Static Results ==========");

        int succeeded = 0, failed = 0;
        for (Cloudlet cl : results) {
            if (cl.getStatus() == Cloudlet.CloudletStatus.SUCCESS) succeeded++;
            else failed++;
        }
        Log.printlnConcat("Completed: ", succeeded, " succeeded, ", failed, " failed (of 71 total)");

        // Gang status
        Map<String, long[]> gangStatus = new HashMap<>();
        for (Cloudlet cl : results) {
            if (cl instanceof CoubesCloudlet cc && cc.getGangId() != null) {
                gangStatus.computeIfAbsent(cc.getGangId(), k -> new long[2]);
                if (cl.getStatus() == Cloudlet.CloudletStatus.SUCCESS) gangStatus.get(cc.getGangId())[0]++;
                else gangStatus.get(cc.getGangId())[1]++;
            }
        }
        Log.printLine("\n----- Gang Status -----");
        for (var entry : gangStatus.entrySet()) {
            Log.printlnConcat("  Gang '", entry.getKey(), "': ", entry.getValue()[0], " succeeded, ",
                    entry.getValue()[1], " failed");
        }

        Log.printLine("");
        metrics.setCompletedCloudlets(results, broker.getCloudletArrivalTimes());
        metrics.printSummary(lastClock, broker.tpOverall(), broker.tpPeak());
        SimulationMetrics.printPerQueueMetrics(results, broker.getCloudletArrivalTimes());

        try { broker.sendResetRequestToControlPlane(); } catch (Exception ignored) {}
        Log.printLine("\nOverload_Comparison_Test_Higher_Static finished!");
    }
}
