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
 * Gang Energy Test — energy cost comparison of gang waiting patterns.
 *
 * Same scenario as Gang_PartialFit_Test but focused on energy metrics.
 *
 * Setup: 4 VMs × 4 PEs = 16 PE slots, PowerModelLinear(500W, 10% static)
 * Wave 1: 2 fillers × 2 PEs (length=10000 → 40s) + Gang: 4 pods × 4 PEs (length=40000 → 160s)
 *
 * Volcano: Fillers run alone (2 VMs active at partial load, 2 idle).
 *          After fillers complete, gang runs (4 VMs at full load).
 *          Energy = idle_period + filler_period + gang_period
 *
 * kube-scheduler: Places fillers + 3 gang members. 3 gang members held (VMs allocated
 *          but idle — drawing static power with no useful work). After fillers complete,
 *          4th member placed, gang starts. But those 3 VMs drew idle power for 40s.
 *          Energy = higher due to idle gang members consuming static power.
 *
 * Key metric: Total energy (Wh). Volcano should be lower because it doesn't waste
 * power on idle held gang members.
 */
public class Gang_Energy_Test {

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

        // 2 fillers: short (40s), 2 PEs each
        for (int i = 0; i < 2; i++) {
            wave1.add(new CoubesCloudlet(i, 10000, 2, 300, 300,
                    full, full, full, 0, Collections.emptyMap(),
                    null, null, true, true, null));
        }
        // Gang: 4 pods × 4 PEs (needs full cluster), longer duration (160s)
        for (int i = 10; i < 14; i++) {
            wave1.add(new CoubesCloudlet(i, 40000, 4, 300, 300,
                    full, full, full, 0, Collections.emptyMap(),
                    null, null, true, true, "energy-gang"));
        }
        broker.submitCloudletList(wave1);

        CloudSim.resumeSimulation();
        SimulationMetrics metrics = new SimulationMetrics(dc, vmList);
        metrics.startWallClock();
        double lastClock = CloudSim.startSimulation();
        CloudSim.stopSimulation();
        metrics.stopWallClock();

        List<Cloudlet> results = broker.getCloudletReceivedList();
        Log.printLine("========== Gang_Energy_Test Results ==========");
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

        Log.printLine("\n----- Energy Analysis -----");
        metrics.printSummary(lastClock, broker.tpOverall());

        try { broker.sendResetRequestToControlPlane(); } catch (Exception ignored) {}
        Log.printLine("\nGang_Energy_Test finished!");
    }
}
