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
 * Maximum Variance Test — designed to maximise run-to-run TTC variance.
 *
 * 2 nodes: one fast (500 MIPS), one slow (100 MIPS). Both have 2 PEs.
 * Both start empty → identical scores → pure random tie-break.
 *
 * Workload: 1 long pod (2 PEs, 100000 MI) + 2 short pods (1 PE, 10000 MI) arriving after.
 * If long pod lands on fast node: finishes in 200s. Short pods go to slow node (100s each).
 * If long pod lands on slow node: finishes in 1000s. Short pods go to fast node (20s each).
 * TTC difference: 200s vs 1000s (5× variance from a single coin flip).
 */
public class Max_Variance_Test {

    public static void main(String[] args) throws Exception {
        CloudSim.init(2, Calendar.getInstance(), false);

        List<Host> hostList = new ArrayList<>();

        // Node 0: fast (500 MIPS, 2 PEs)
        List<Pe> pesFast = new ArrayList<>();
        pesFast.add(new Pe(0, new PeProvisionerSimple(500)));
        pesFast.add(new Pe(1, new PeProvisionerSimple(500)));
        hostList.add(new PowerHost(0,
                new RamProvisionerSimple(4096), new BwProvisionerSimple(10000),
                100000, pesFast, new VmSchedulerTimeShared(pesFast),
                new PowerModelLinear(500, 0.30)));

        // Node 1: slow (100 MIPS, 2 PEs)
        List<Pe> pesSlow = new ArrayList<>();
        pesSlow.add(new Pe(0, new PeProvisionerSimple(100)));
        pesSlow.add(new Pe(1, new PeProvisionerSimple(100)));
        hostList.add(new PowerHost(1,
                new RamProvisionerSimple(4096), new BwProvisionerSimple(10000),
                100000, pesSlow, new VmSchedulerTimeShared(pesSlow),
                new PowerModelLinear(200, 0.30)));

        DatacenterCharacteristics chars = new DatacenterCharacteristics(
                "x86", "Linux", "Xen", hostList, 10.0, 3.0, 0.05, 0.1, 0.1);
        PowerDatacenterCustom dc = new PowerDatacenterCustom(
                "Datacenter_0", chars, new VmAllocationPolicySimple(hostList),
                new LinkedList<>(), 1, true);
        dc.setDisableMigrations(true);
        dc.setLogLevel(PowerDatacenterCustom.LogLevel.QUIET);

        Live_Kubernetes_Broker_Ex broker = new Live_Kubernetes_Broker_Ex("Broker_0");
        int brokerId = broker.getId();

        // VM 0: fast (500 MIPS, 2 PEs) pinned to host 0
        // VM 1: slow (100 MIPS, 2 PEs) pinned to host 1
        List<Vm> vmList = new ArrayList<>();
        vmList.add(new PowerVmCustom(0, brokerId, 500, 2, 4096, 10000, 100000, 0, "Xen",
                new CloudletSchedulerTimeShared(), 1, 0));
        vmList.add(new PowerVmCustom(1, brokerId, 100, 2, 4096, 10000, 100000, 0, "Xen",
                new CloudletSchedulerTimeShared(), 1, 1));
        broker.submitGuestList(vmList);

        UtilizationModel full = new UtilizationModelFull();

        // Wave 1: 1 long pod needing 2 PEs (fills whichever node it lands on)
        List<Cloudlet> wave1 = new ArrayList<>();
        wave1.add(new Cloudlet(0, 100000, 2, 300, 300, full, full, full));
        broker.submitCloudletList(wave1);

        // Wave 2 (t=1): 2 short pods needing 1 PE each (must go to the other node)
        List<Cloudlet> wave2 = new ArrayList<>();
        wave2.add(new Cloudlet(1, 10000, 1, 300, 300, full, full, full));
        wave2.add(new Cloudlet(2, 10000, 1, 300, 300, full, full, full));
        broker.submitCloudletList(wave2, 1.0);

        CloudSim.resumeSimulation();
        SimulationMetrics metrics = new SimulationMetrics(dc, vmList);
        metrics.startWallClock();
        double lastClock = CloudSim.startSimulation();
        CloudSim.stopSimulation();
        metrics.stopWallClock();

        List<Cloudlet> results = broker.getCloudletReceivedList();
        Log.printLine("========== Max_Variance_Test Results ==========");
        Log.printlnConcat("Simulated Time Elapsed: ", lastClock, " units");
        Log.printlnConcat("Energy consumption: ", String.format("%.2f", dc.getPower() / 3600.0), " Wh");
        Log.printlnConcat("Completed: ", results.size(), "/3");

        try { broker.sendResetRequestToControlPlane(); } catch (Exception ignored) {}
        Log.printLine("Max_Variance_Test finished!");
    }
}
