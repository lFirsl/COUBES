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
 * Gang Atomicity Benefit Test — demonstrates where gang scheduling HELPS.
 *
 * Infrastructure: 3 nodes × 4 PEs @ 250 MIPS = 12 PE slots
 * Workload: 4 gangs × 3 pods × 2 PEs = 24 PEs needed (only 12 fit simultaneously)
 *
 * Expected behaviour:
 *   - Volcano (gang plugin): places 2 complete gangs in round 1 (12 PEs used).
 *     Remaining 2 gangs wait. After round 1 completes, places the next 2. TTC = 2 rounds.
 *   - kube-scheduler: sees 12 individual 2-PE pods. Places 6 (fills 12 PEs). But those
 *     6 pods are spread across all 4 gangs (greedy, no gang awareness). Result: fewer
 *     gangs complete in round 1, potentially 0-1 gangs complete. TTC = more rounds.
 *
 * This shows gang atomicity preventing fragmentation across gang boundaries.
 */
public class Gang_Atomicity_Benefit_Test {

    public static void main(String[] args) throws Exception {
        CloudSim.init(2, Calendar.getInstance(), false);

        int numHosts = 3;
        int pesPerHost = 4;
        int mips = 250;

        List<Host> hostList = new ArrayList<>();
        for (int i = 0; i < numHosts; i++) {
            List<Pe> peList = new ArrayList<>();
            for (int p = 0; p < pesPerHost; p++) {
                peList.add(new Pe(p, new PeProvisionerSimple(mips)));
            }
            hostList.add(new PowerHost(i,
                    new RamProvisionerSimple(4096), new BwProvisionerSimple(10000),
                    100000, peList, new VmSchedulerTimeShared(peList),
                    new PowerModelLinear(500, 0.30)));
        }

        DatacenterCharacteristics chars = new DatacenterCharacteristics(
                "x86", "Linux", "Xen", hostList, 10.0, 3.0, 0.05, 0.1, 0.1);
        PowerDatacenterCustom dc = new PowerDatacenterCustom(
                "Datacenter_0", chars, new VmAllocationPolicySimple(hostList),
                new LinkedList<>(), 1, true);
        dc.setDisableMigrations(true);

        Live_Kubernetes_Broker_Ex broker = new Live_Kubernetes_Broker_Ex("Broker_0");
        int brokerId = broker.getId();

        List<Vm> vmList = new ArrayList<>();
        for (int i = 0; i < numHosts; i++) {
            vmList.add(new PowerVmCustom(i, brokerId, mips, pesPerHost,
                    4096, 10000, 100000, 0, "Xen",
                    new CloudletSchedulerTimeShared(), 1, i));
        }
        broker.submitGuestList(vmList);

        UtilizationModel full = new UtilizationModelFull();
        int id = 0;

        // 4 gangs × 3 pods × 2 PEs, length=30000 (120s at 250 MIPS)
        // Interleaved submission: A0,B0,C0,D0,A1,B1,C1,D1,A2,B2,C2,D2
        // This forces kube-scheduler to see pods from all gangs mixed together,
        // making it likely to place members from different gangs rather than
        // completing one gang at a time.
        String[] gangNames = {"gang-A", "gang-B", "gang-C", "gang-D"};
        List<Cloudlet> wave1 = new ArrayList<>();
        for (int member = 0; member < 3; member++) {
            for (String gang : gangNames) {
                CoubesCloudlet cl = new CoubesCloudlet(id++, 30000, 2, 300, 300,
                        full, full, full, 0, Collections.emptyMap(), null, null, true, true, gang);
                wave1.add(cl);
            }
        }
        broker.submitCloudletList(wave1);

        // Run
        CloudSim.resumeSimulation();
        SimulationMetrics metrics = new SimulationMetrics(dc, vmList);
        metrics.startWallClock();
        double lastClock = CloudSim.startSimulation();
        CloudSim.stopSimulation();
        metrics.stopWallClock();

        // Results
        List<Cloudlet> results = broker.getCloudletReceivedList();
        Log.printLine("========== Gang_Atomicity_Benefit_Test Results ==========");

        int succeeded = 0, failed = 0;
        for (Cloudlet cl : results) {
            if (cl.getStatus() == Cloudlet.CloudletStatus.SUCCESS) succeeded++;
            else failed++;
        }
        Log.printlnConcat("Completed: ", succeeded, " succeeded, ", failed, " failed (of 12 total)");

        // Gang status
        Map<String, long[]> gangStatus = new LinkedHashMap<>();
        for (String g : gangNames) gangStatus.put(g, new long[2]);
        for (Cloudlet cl : results) {
            if (cl instanceof CoubesCloudlet cc && cc.getGangId() != null) {
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

        try { broker.sendResetRequestToControlPlane(); } catch (Exception ignored) {}
        Log.printLine("\nGang_Atomicity_Benefit_Test finished!");
    }
}
