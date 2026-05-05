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
 * Gang Partial Fit Test — gang that almost fits, needs fillers to complete first.
 *
 * Setup: 4 VMs × 4 PEs = 16 PE slots
 * Wave 1: 2 filler pods × 2 PEs (non-gang, short duration) + Gang A: 4 pods × 4 PEs = 16 PEs needed
 * Available at submission: 16 PEs, but fillers take 4 PEs → only 12 free → gang needs 16.
 *
 * Volcano: Refuses to schedule gang (can't fit all 4 simultaneously). Schedules fillers.
 *          After fillers complete (freeing 4 PEs → 16 available), gang is scheduled.
 * kube-scheduler: Schedules fillers + 3 gang members (12 PEs). Holds 3 in waiting room.
 *          4th gang member can't fit (only 0 PEs left). Deadlock until fillers complete,
 *          then 4th member placed, gang released. But 3 members sat idle the whole time.
 *
 * Key metric: TTC and energy — Volcano avoids idle waiting, kube-scheduler wastes resources.
 */
public class Gang_PartialFit_Test {

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

        // All submitted together: 2 fillers + 4 gang members
        List<Cloudlet> wave1 = new ArrayList<>();
        UtilizationModel full = new UtilizationModelFull();

        // Fillers: short tasks (length=10000 → 40s at 250 MIPS), 2 PEs each
        for (int i = 0; i < 2; i++) {
            wave1.add(new CoubesCloudlet(i, 10000, 2, 300, 300,
                    full, full, full, 0, Collections.emptyMap(),
                    null, null, true, true, null)); // no gang
        }
        // Gang A: 4 pods × 4 PEs each (needs full cluster)
        for (int i = 10; i < 14; i++) {
            wave1.add(new CoubesCloudlet(i, 40000, 4, 300, 300,
                    full, full, full, 0, Collections.emptyMap(),
                    null, null, true, true, "partial-gang"));
        }
        broker.submitCloudletList(wave1);

        CloudSim.resumeSimulation();
        SimulationMetrics metrics = new SimulationMetrics(dc, vmList);
        metrics.startWallClock();
        double lastClock = CloudSim.startSimulation();
        CloudSim.stopSimulation();
        metrics.stopWallClock();

        List<Cloudlet> results = broker.getCloudletReceivedList();
        Log.printLine("========== Gang_PartialFit_Test Results ==========");
        Log.printLine("ID    Status    VM    PEs    Start      Finish");
        results.sort(Comparator.comparingInt(Cloudlet::getCloudletId));
        for (Cloudlet cl : results) {
            Log.printlnConcat("  ", cl.getCloudletId(), "    ", cl.getStatus(),
                    "    ", cl.getGuestId(), "    ", cl.getNumberOfPes(),
                    "    ", String.format("%.1f", cl.getExecStartTime()),
                    "    ", String.format("%.1f", cl.getFinishTime()));
        }

        metrics.printSummary(lastClock, broker.tpOverall());

        // Gang atomicity check
        double gangStart = results.stream()
                .filter(c -> c instanceof CoubesCloudlet cc && "partial-gang".equals(cc.getGangId()))
                .mapToDouble(Cloudlet::getExecStartTime).min().orElse(-1);
        double gangEnd = results.stream()
                .filter(c -> c instanceof CoubesCloudlet cc && "partial-gang".equals(cc.getGangId()))
                .mapToDouble(Cloudlet::getExecStartTime).max().orElse(-1);
        Log.printlnConcat("\nGang start range: [", String.format("%.1f", gangStart),
                " - ", String.format("%.1f", gangEnd), "] → ",
                (gangEnd - gangStart < 1.0) ? "ATOMIC ✓" : "NOT ATOMIC ✗");

        try { broker.sendResetRequestToControlPlane(); } catch (Exception ignored) {}
        Log.printLine("\nGang_PartialFit_Test finished!");
    }
}
