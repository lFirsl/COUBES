package org.example.testSuite;

import org.cloudbus.cloudsim.*;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.power.PowerHost;
import org.cloudbus.cloudsim.power.models.PowerModelLinear;
import org.cloudbus.cloudsim.provisioners.BwProvisionerSimple;
import org.cloudbus.cloudsim.provisioners.PeProvisionerSimple;
import org.cloudbus.cloudsim.provisioners.RamProvisionerSimple;
import org.example.kubernetes_broker.Live_Kubernetes_Broker_Ex;
import org.example.kubernetes_broker.PowerDatacenterCustom;
import org.example.kubernetes_broker.PowerVmCustom;
import org.example.metrics.SimulationMetrics;

import java.text.DecimalFormat;
import java.util.*;

/**
 * Heterogeneous node test: nodes with different PE counts.
 *
 * Setup: 3 hosts with 2, 4, and 8 PEs respectively. 3 VMs matching those hosts.
 * 6 pods: three need 1 PE, two need 3 PEs, one needs 7 PEs.
 *
 * Expected scheduling:
 * - The 7-PE pod can only fit on the 8-PE node
 * - The 3-PE pods can fit on the 4-PE or 8-PE node (but not the 2-PE node)
 * - The 1-PE pods fit anywhere
 *
 * All 6 should eventually complete, possibly requiring rescheduling rounds
 * depending on which pods land where first.
 */
public class Heterogeneous_Node_Test {

    public static void main(String[] args) {
        Log.println("Starting Heterogeneous_Node_Test...");
        try {
            CloudSim.init(2, Calendar.getInstance(), false);

            PowerDatacenterCustom dc = createDatacenter("Datacenter_0");
            dc.setLogLevel(PowerDatacenterCustom.LogLevel.QUIET);

            Live_Kubernetes_Broker_Ex broker = new Live_Kubernetes_Broker_Ex("Broker_0", -1);
            int brokerId = broker.getId();

            // 3 VMs with different PE counts: 2, 4, 8
            List<Vm> vms = new ArrayList<>();
            vms.add(new PowerVmCustom(0, brokerId, 250, 2, 512, 1000, 10000, 0, "Xen", new CloudletSchedulerTimeShared(), 1, -1));
            vms.add(new PowerVmCustom(1, brokerId, 250, 4, 512, 1000, 10000, 0, "Xen", new CloudletSchedulerTimeShared(), 1, -1));
            vms.add(new PowerVmCustom(2, brokerId, 250, 8, 512, 1000, 10000, 0, "Xen", new CloudletSchedulerTimeShared(), 1, -1));
            broker.submitGuestList(vms);

            // Mixed pod sizes
            List<Cloudlet> cloudlets = new ArrayList<>();
            UtilizationModel um = new UtilizationModelFull();
            // Three 1-PE pods
            cloudlets.add(new Cloudlet(0, 50000, 1, 300, 300, um, um, um));
            cloudlets.add(new Cloudlet(1, 50000, 1, 300, 300, um, um, um));
            cloudlets.add(new Cloudlet(2, 50000, 1, 300, 300, um, um, um));
            // Two 3-PE pods
            cloudlets.add(new Cloudlet(3, 50000, 3, 300, 300, um, um, um));
            cloudlets.add(new Cloudlet(4, 50000, 3, 300, 300, um, um, um));
            // One 7-PE pod
            cloudlets.add(new Cloudlet(5, 50000, 7, 300, 300, um, um, um));
            for (Cloudlet c : cloudlets) c.setUserId(brokerId);

            broker.submitCloudletList(cloudlets);

            CloudSim.resumeSimulation();
            SimulationMetrics metrics = new SimulationMetrics(dc, vms);
            metrics.startWallClock();
            double lastClock = CloudSim.startSimulation();

            List<Cloudlet> results = broker.getCloudletReceivedList();
            CloudSim.stopSimulation();
            metrics.stopWallClock();

            if (results.size() != 6) {
                throw new RuntimeException("Expected 6 cloudlets but received " + results.size());
            }

            printCloudletList(results);
            metrics.printSummary(lastClock, broker.tpOverall(), broker.tpPeak());
            broker.sendResetRequestToControlPlane();
            Log.println("Heterogeneous_Node_Test finished!");

        } catch (Exception e) {
            e.printStackTrace();
            Log.println("Simulation terminated due to an unexpected error");
        }
    }

    private static PowerDatacenterCustom createDatacenter(String name) {
        List<Host> hostList = new ArrayList<>();
        int[] pesPerHost = {2, 4, 8};
        for (int i = 0; i < 3; i++) {
            List<Pe> peList = new ArrayList<>();
            for (int p = 0; p < pesPerHost[i]; p++) {
                peList.add(new Pe(p, new PeProvisionerSimple(250)));
            }
            hostList.add(new PowerHost(i,
                    new RamProvisionerSimple(16384), new BwProvisionerSimple(10000),
                    1000000, peList, new VmSchedulerTimeShared(peList),
                    new PowerModelLinear(500, 0.1)));
        }
        DatacenterCharacteristics chars = new DatacenterCharacteristics(
                "x86", "Linux", "Xen", hostList, 10.0, 3.0, 0.05, 0.1, 0.1);
        try {
            PowerDatacenterCustom dc = new PowerDatacenterCustom(
                    name, chars, new VmAllocationPolicySimple(hostList), new LinkedList<>(), 1, true);
            dc.setDisableMigrations(true);
            return dc;
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private static void printCloudletList(List<Cloudlet> list) {
        System.out.println("\n========== OUTPUT ==========");
        System.out.println("Cloudlet ID    STATUS    VM ID    PEs    Time    Start    Finish");
        DecimalFormat dft = new DecimalFormat("###.##");
        for (Cloudlet c : list) {
            if (c.getStatus() == Cloudlet.CloudletStatus.SUCCESS) {
                System.out.println("    " + c.getCloudletId() + "        SUCCESS    " +
                        c.getGuestId() + "    " + c.getNumberOfPes() + "    " +
                        dft.format(c.getActualCPUTime()) + "    " +
                        dft.format(c.getExecStartTime()) + "    " + dft.format(c.getExecFinishTime()));
            }
        }
    }
}
