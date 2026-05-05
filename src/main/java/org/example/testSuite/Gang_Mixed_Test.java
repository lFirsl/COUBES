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
 * Gang Mixed Test — gang + independent pods competing for resources.
 *
 * Setup: 4 VMs × 4 PEs = 16 PE slots
 * Submitted together:
 *   - Gang A: 4 pods × 4 PEs = 16 PEs (needs full cluster)
 *   - 4 independent pods × 2 PEs = 8 PEs
 *
 * Volcano (gang plugin): Sees gang needs 16 PEs but independents take some.
 *   Schedules independents first (they fit individually). Gang waits until
 *   independents complete, then runs atomically.
 * kube-scheduler: Places independents + some gang members greedily. Gang members
 *   held in waiting room. Remaining gang members can't fit → deadlock until
 *   independents complete. But held gang members consume capacity, potentially
 *   blocking independents too.
 *
 * Key metric: Whether the independent pods complete at all, and total TTC.
 */
public class Gang_Mixed_Test {

    public static void main(String[] args) throws Exception {
        CloudSim.init(2, Calendar.getInstance(), false);

        int numHosts = 4;
        int numPes = 4;
        int mips = 250;

        List<Host> hostList = new ArrayList<>();
        for (int i = 0; i < numHosts; i++) {
            List<Pe> peList = new ArrayList<>();
            for (int p = 0; p < numPes; p++) {
                peList.add(new Pe(p, new PeProvisionerSimple(mips)));
            }
            hostList.add(new PowerHost(i,
                    new RamProvisionerSimple(2048), new BwProvisionerSimple(10000),
                    100000, peList, new VmSchedulerTimeShared(peList),
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

        List<Vm> vmList = new ArrayList<>();
        for (int i = 0; i < numHosts; i++) {
            vmList.add(new PowerVmCustom(i, brokerId, mips, numPes, 2048, 10000, 100000,
                    0, "Xen", new CloudletSchedulerTimeShared(), 1, i));
        }
        broker.submitGuestList(vmList);

        UtilizationModel full = new UtilizationModelFull();
        List<Cloudlet> wave1 = new ArrayList<>();

        // 4 independent pods (short, 2 PEs each)
        for (int i = 0; i < 4; i++) {
            wave1.add(new CoubesCloudlet(i, 20000, 2, 300, 300,
                    full, full, full, 0, Collections.emptyMap(),
                    null, null, true, true, null)); // no gang
        }
        // Gang A: 4 pods × 4 PEs (needs full cluster)
        for (int i = 10; i < 14; i++) {
            wave1.add(new CoubesCloudlet(i, 40000, 4, 300, 300,
                    full, full, full, 0, Collections.emptyMap(),
                    null, null, true, true, "full-cluster-gang"));
        }
        broker.submitCloudletList(wave1);

        CloudSim.resumeSimulation();
        SimulationMetrics metrics = new SimulationMetrics(dc, vmList);
        metrics.startWallClock();
        double lastClock = CloudSim.startSimulation();
        CloudSim.stopSimulation();
        metrics.stopWallClock();

        List<Cloudlet> results = broker.getCloudletReceivedList();
        Log.printLine("========== Gang_Mixed_Test Results ==========");
        Log.printLine("ID    Status    VM    PEs    Gang?    Start      Finish");
        results.sort(Comparator.comparingInt(Cloudlet::getCloudletId));
        for (Cloudlet cl : results) {
            String gang = (cl instanceof CoubesCloudlet cc && cc.getGangId() != null) ? cc.getGangId() : "-";
            Log.printlnConcat("  ", cl.getCloudletId(), "    ", cl.getStatus(),
                    "    ", cl.getGuestId(), "    ", cl.getNumberOfPes(),
                    "    ", gang,
                    "    ", String.format("%.1f", cl.getExecStartTime()),
                    "    ", String.format("%.1f", cl.getFinishTime()));
        }

        metrics.printSummary(lastClock, broker.tpOverall());

        long indepCompleted = results.stream()
                .filter(c -> !(c instanceof CoubesCloudlet cc && cc.getGangId() != null))
                .filter(c -> c.getStatus() == Cloudlet.CloudletStatus.SUCCESS).count();
        long gangCompleted = results.stream()
                .filter(c -> c instanceof CoubesCloudlet cc && "full-cluster-gang".equals(cc.getGangId()))
                .filter(c -> c.getStatus() == Cloudlet.CloudletStatus.SUCCESS).count();
        Log.printlnConcat("\nIndependent pods completed: ", indepCompleted, "/4");
        Log.printlnConcat("Gang pods completed: ", gangCompleted, "/4");

        try { broker.sendResetRequestToControlPlane(); } catch (Exception ignored) {}
        Log.printLine("\nGang_Mixed_Test finished!");
    }
}
